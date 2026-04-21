package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.NodeAddress;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phi accrual failure detector for adaptive node liveness detection.
 *
 * <p>
 * Contract: Maintains a sliding window of heartbeat inter-arrival times per monitored node.
 * Computes phi = -log10(1 - CDF(timeSinceLastHeartbeat)) where CDF is based on a normal
 * distribution fitted to the heartbeat history. A higher phi value indicates a greater likelihood
 * that the node has failed. The detector is stateless for unknown nodes (returns phi = 0.0 until at
 * least two heartbeats have been recorded).
 *
 * <p>
 * Thread-safe: uses concurrent data structures for per-node state.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 *
 * @spec engine.clustering.R18 — phi accrual model: sliding window + phi = -log10(1 - CDF(delay))
 * @spec engine.clustering.R19 — when phi exceeds threshold the detector reports suspicion (membership
 *       protocol handles alive→dead lifecycle; the detector only flags suspicion)
 * @spec engine.clustering.R20 — phi returns 0.0 until at least 2 heartbeats recorded
 * @spec engine.clustering.R21 — sliding window has bounded max size; oldest samples evicted on overflow
 * @spec engine.clustering.R83 — evicting heartbeat history on member departure (see evict() method)
 */
public final class PhiAccrualFailureDetector {

    private final int windowSize;
    private final ConcurrentHashMap<NodeAddress, HeartbeatHistory> heartbeatHistory = new ConcurrentHashMap<>();

    /**
     * Creates a failure detector with the specified sliding window size.
     *
     * @param windowSize the maximum number of heartbeat intervals to retain; must be >= 2
     */
    public PhiAccrualFailureDetector(int windowSize) {
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be >= 2, got: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * Records a heartbeat from the specified node at the current time.
     *
     * @param from the address of the node that sent the heartbeat; must not be null
     */
    public void recordHeartbeat(NodeAddress from) {
        recordHeartbeat(from, System.nanoTime());
    }

    /**
     * Records a heartbeat from the specified node at the given timestamp.
     *
     * @param from the address of the node that sent the heartbeat; must not be null
     * @param timestampNanos the timestamp in nanoseconds (e.g., from {@link System#nanoTime()})
     */
    public void recordHeartbeat(NodeAddress from, long timestampNanos) {
        Objects.requireNonNull(from, "from must not be null");
        heartbeatHistory.compute(from, (key, existing) -> {
            if (existing == null) {
                return new HeartbeatHistory(windowSize, timestampNanos);
            }
            existing.record(timestampNanos);
            return existing;
        });
    }

    /**
     * Removes all heartbeat history for the specified node.
     *
     * <p>
     * Callers should invoke this when a node departs the cluster (graceful leave or confirmed
     * failure) to prevent unbounded growth of the internal history map in long-running clusters
     * with high node churn.
     *
     * @param node the address of the node to remove; must not be null
     */
    public void remove(NodeAddress node) {
        Objects.requireNonNull(node, "node must not be null");
        heartbeatHistory.remove(node);
    }

    /**
     * Computes the current phi value for the specified node.
     *
     * @param node the address of the node to check; must not be null
     * @return the phi value; 0.0 if insufficient history
     */
    public double phi(NodeAddress node) {
        return phi(node, System.nanoTime());
    }

    /**
     * Computes the phi value for the specified node at the given timestamp.
     *
     * @param node the address of the node to check; must not be null
     * @param nowNanos the current timestamp in nanoseconds
     * @return the phi value; 0.0 if insufficient history
     */
    public double phi(NodeAddress node, long nowNanos) {
        Objects.requireNonNull(node, "node must not be null");

        // Snapshot HeartbeatHistory fields atomically inside compute() to prevent torn
        // reads. HeartbeatHistory is not thread-safe — its fields (lastNanos, sum,
        // sumSquares, size) can be mutated by recordHeartbeat() inside compute(). Reading
        // via get() outside compute() risks observing a partially updated state where
        // e.g. lastNanos is updated but sum/size are stale, producing negative elapsed
        // or inconsistent mean/stddev values.
        final double[] snapshot = new double[4]; // [count, lastNanos, mean, stddev]
        final boolean[] present = { false };
        heartbeatHistory.compute(node, (_, history) -> {
            if (history == null) {
                return null;
            }
            present[0] = true;
            snapshot[0] = history.count();
            snapshot[1] = history.lastHeartbeatNanos();
            if (snapshot[0] >= 2) {
                snapshot[2] = history.mean();
                snapshot[3] = history.stddev();
            }
            return history;
        });

        if (!present[0] || snapshot[0] < 2) {
            return 0.0;
        }

        final long elapsed = nowNanos - (long) snapshot[1];
        // In concurrent usage, nowNanos (captured by the caller before acquiring the
        // bucket lock) can be behind lastNanos (read atomically inside compute()). This
        // happens when a recordHeartbeat() acquired the lock first and advanced lastNanos.
        // A negative elapsed means the node heartbeated after the check timestamp — it is
        // definitely alive — so return 0.0 (minimum phi).
        if (elapsed < 0) {
            return 0.0;
        }
        assert elapsed >= 0 : "elapsed time must be non-negative";

        final double elapsedMs = elapsed / 1_000_000.0;
        final double mean = snapshot[2];
        final double stddev = snapshot[3];

        // Fail-safe: if floating-point corruption produced NaN in the heartbeat
        // statistics, treat the node as maximally suspect rather than silently
        // returning 0.0 (which would make a corrupted node appear perfectly healthy).
        if (Double.isNaN(mean) || Double.isNaN(stddev)) {
            return Double.MAX_VALUE;
        }

        // Avoid division by zero: if stddev is essentially zero, use a small epsilon
        final double effectiveStddev = Math.max(stddev, 0.1);

        return computePhi(elapsedMs, mean, effectiveStddev);
    }

    /**
     * Returns whether the specified node is considered available at the given threshold.
     *
     * @param node the address of the node to check; must not be null
     * @param threshold the phi threshold above which the node is considered failed; must be
     *            positive
     * @return {@code true} if phi(node) is below the threshold
     */
    public boolean isAvailable(NodeAddress node, double threshold) {
        return isAvailable(node, threshold, System.nanoTime());
    }

    /**
     * Returns whether the specified node is considered available at the given threshold and
     * timestamp.
     *
     * @param node the address of the node to check; must not be null
     * @param threshold the phi threshold above which the node is considered failed; must be
     *            positive
     * @param nowNanos the current timestamp in nanoseconds
     * @return {@code true} if phi(node) is below the threshold
     */
    public boolean isAvailable(NodeAddress node, double threshold, long nowNanos) {
        Objects.requireNonNull(node, "node must not be null");
        if (!(threshold > 0.0)) {
            throw new IllegalArgumentException("threshold must be positive, got: " + threshold);
        }
        return phi(node, nowNanos) < threshold;
    }

    /**
     * Computes phi = -log10(1 - CDF(elapsed)) using a normal distribution with the given mean and
     * standard deviation.
     *
     * <p>
     * The CDF is approximated using the complementary error function.
     */
    private static double computePhi(double elapsedMs, double mean, double stddev) {
        assert stddev > 0 : "stddev must be positive";
        // Compute the probability that the next heartbeat would arrive after 'elapsedMs'
        // P(X > elapsed) = 1 - CDF(elapsed) = 0.5 * erfc((elapsed - mean) / (stddev * sqrt(2)))
        final double y = (elapsedMs - mean) / stddev;
        final double pLate = 0.5 * erfc(y / Math.sqrt(2.0));

        // phi = -log10(pLate)
        // Clamp pLate to avoid log(0) or negative phi
        if (pLate <= 0.0) {
            return Double.MAX_VALUE;
        }
        final double phi = -Math.log10(pLate);
        return Math.max(0.0, phi);
    }

    /**
     * Approximation of the complementary error function erfc(x). Uses Abramowitz and Stegun
     * approximation 7.1.26, accurate to ~1.5e-7. Iterative — no recursion per coding guidelines.
     */
    private static double erfc(double x) {
        // Handle negative x iteratively: erfc(-x) = 2 - erfc(|x|)
        final boolean negative = x < 0;
        final double absX = negative ? -x : x;

        final double t = 1.0 / (1.0 + 0.3275911 * absX);
        final double t2 = t * t;
        final double t3 = t2 * t;
        final double t4 = t3 * t;
        final double t5 = t4 * t;
        final double poly = 0.254829592 * t - 0.284496736 * t2 + 1.421413741 * t3 - 1.453152027 * t4
                + 1.061405429 * t5;
        final double result = poly * Math.exp(-absX * absX);
        return negative ? 2.0 - result : result;
    }

    /**
     * Sliding window of heartbeat inter-arrival times for a single node. Not thread-safe — callers
     * must synchronize via ConcurrentHashMap.compute().
     */
    private static final class HeartbeatHistory {
        private final double[] intervals;
        private int head;
        private int size;
        private long lastNanos;
        private double sum;
        private double sumSquares;

        HeartbeatHistory(int windowSize, long firstHeartbeatNanos) {
            assert windowSize >= 2 : "windowSize must be >= 2";
            this.intervals = new double[windowSize];
            this.head = 0;
            this.size = 0;
            this.lastNanos = firstHeartbeatNanos;
            this.sum = 0.0;
            this.sumSquares = 0.0;
        }

        void record(long nowNanos) {
            final double intervalMs = (nowNanos - lastNanos) / 1_000_000.0;
            // Runtime guard: non-monotonic timestamps (e.g., from clock adjustments or
            // out-of-order heartbeats) would inject negative intervals into sum/sumSquares,
            // corrupting mean and stddev for the entire window rotation. Skip the negative
            // interval but still advance lastNanos to prevent repeated negatives.
            if (intervalMs < 0) {
                lastNanos = nowNanos;
                return;
            }
            assert intervalMs >= 0 : "interval must be non-negative";
            lastNanos = nowNanos;

            // If window is full, subtract the oldest value
            if (size == intervals.length) {
                final double oldest = intervals[head];
                sum -= oldest;
                sumSquares -= oldest * oldest;
            } else {
                size++;
            }

            intervals[head] = intervalMs;
            sum += intervalMs;
            sumSquares += intervalMs * intervalMs;
            head = (head + 1) % intervals.length;
        }

        int count() {
            // Number of intervals recorded (heartbeat count is intervals + 1,
            // but we need >= 2 heartbeats which means >= 1 interval)
            return size + 1; // +1 to account for the initial heartbeat
        }

        long lastHeartbeatNanos() {
            return lastNanos;
        }

        double mean() {
            assert size > 0 : "cannot compute mean with no intervals";
            return sum / size;
        }

        double stddev() {
            assert size > 0 : "cannot compute stddev with no intervals";
            final double mean = mean();
            final double variance = (sumSquares / size) - (mean * mean);
            return Math.sqrt(Math.max(0.0, variance));
        }
    }
}

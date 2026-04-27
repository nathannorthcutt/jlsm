package jlsm.encryption.internal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import jlsm.encryption.TenantId;

/**
 * Per-tenant counter of unclassified-error events with threshold-based escalation. Default: 100
 * unclassified events in 60s escalates to PERMANENT classification (R76a-2). Emits at most one log
 * line per second per tenant via a token-bucket rate limiter.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R76a-2); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R76a-2
 */
public final class UnclassifiedErrorEscalator {

    /** R76a-2 default threshold: 100 events. */
    private static final int DEFAULT_THRESHOLD = 100;
    /** R76a-2 default observation window: 60 seconds. */
    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);
    /** R76a-2 default log rate-limit: 1 emission/sec/tenant. */
    private static final long LOG_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private static final System.Logger LOG = System
            .getLogger(UnclassifiedErrorEscalator.class.getName());

    private final int threshold;
    private final long windowNanos;

    private final ConcurrentHashMap<TenantId, TenantCounter> counters = new ConcurrentHashMap<>();

    private UnclassifiedErrorEscalator(int threshold, Duration window) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive, got " + threshold);
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        this.threshold = threshold;
        this.windowNanos = window.toNanos();
    }

    /** Construct with the R76a-2 default of 100 events / 60s. */
    public static UnclassifiedErrorEscalator withDefaults() {
        return new UnclassifiedErrorEscalator(DEFAULT_THRESHOLD, DEFAULT_WINDOW);
    }

    /** Construct with explicit threshold and window. */
    public static UnclassifiedErrorEscalator withConfig(int threshold, Duration window) {
        return new UnclassifiedErrorEscalator(threshold, window);
    }

    /**
     * Record one unclassified-error event for {@code tenantId}. Returns true iff the event was the
     * one that pushed the rolling count over {@code threshold} within {@code window}.
     *
     * @throws NullPointerException if {@code tenantId} is null
     */
    public boolean recordUnclassified(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        final TenantCounter counter = counters.computeIfAbsent(tenantId, k -> new TenantCounter());
        return counter.record(threshold, windowNanos);
    }

    /** Current rolling count for {@code tenantId} within the configured window. */
    public long currentCount(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        final TenantCounter counter = counters.get(tenantId);
        if (counter == null) {
            return 0L;
        }
        return counter.currentCount(windowNanos);
    }

    /**
     * Per-tenant rolling-window event counter. The deque holds nanos timestamps; entries older than
     * the window are evicted before any read. Synchronised via {@code synchronized(this)} —
     * per-tenant lock granularity matches per-tenant escalation isolation per R76a-2.
     */
    private static final class TenantCounter {

        private final Deque<Long> timestamps = new ArrayDeque<>();
        /** Last log-emission timestamp (R76a-2 1 emission/sec/tenant rate limit). */
        private long lastLogNanos = Long.MIN_VALUE;
        /** True iff the most recent threshold crossing has not yet been reset by window decay. */
        private boolean escalated = false;

        synchronized boolean record(int threshold, long windowNanos) {
            final long now = System.nanoTime();
            evictExpired(now, windowNanos);
            timestamps.addLast(now);
            final int count = timestamps.size();

            if (count == threshold && !escalated) {
                // Threshold-crossing event. Apply log rate limit.
                if (now - lastLogNanos >= LOG_INTERVAL_NANOS) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Unclassified KMS error storm — threshold (" + threshold
                                    + ") reached within window; escalating to PERMANENT");
                    lastLogNanos = now;
                }
                escalated = true;
                return true;
            }
            if (count < threshold) {
                escalated = false;
            }
            return false;
        }

        synchronized long currentCount(long windowNanos) {
            evictExpired(System.nanoTime(), windowNanos);
            return timestamps.size();
        }

        private void evictExpired(long now, long windowNanos) {
            final long cutoff = now - windowNanos;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }
            if (timestamps.isEmpty()) {
                escalated = false;
            }
        }
    }
}

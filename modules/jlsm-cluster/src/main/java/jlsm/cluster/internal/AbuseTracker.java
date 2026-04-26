package jlsm.cluster.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Per-connection sliding-window abuse counter for {@code transport.multiplexed-framing} R37c.
 *
 * <p>
 * Records violation timestamps; on each {@link #recordViolation()} call, expires entries older than
 * the configured window and returns {@code true} if the post-record count exceeds the configured
 * threshold. Used to bound the denial-of-service surface introduced by R37's silent-drop discipline
 * for inbound oversized requests.
 *
 * <p>
 * Not thread-safe. Single reader thread per connection is the sole accessor.
 *
 * @spec transport.multiplexed-framing.R37c
 */
public final class AbuseTracker {

    /** Default abuse threshold: 4 violations within the configured window. */
    public static final int DEFAULT_THRESHOLD = 4;

    /** Default sliding window: 60 seconds. */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int threshold;
    private final long windowMs;
    private final LongSupplier clock;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public AbuseTracker() {
        this(DEFAULT_THRESHOLD, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public AbuseTracker(int threshold, long windowMs, LongSupplier clock) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive: " + threshold);
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive: " + windowMs);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.threshold = threshold;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /**
     * Records a violation.
     *
     * @return {@code true} if the threshold has been crossed (more than {@code threshold}
     *         violations within the window — connection should be closed)
     */
    public boolean recordViolation() {
        long now = clock.getAsLong();
        long cutoff = now - windowMs;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
        timestamps.addLast(now);
        return timestamps.size() > threshold;
    }

    /** Clears the violation history. */
    public void reset() {
        timestamps.clear();
    }
}

package jlsm.engine.cluster.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A {@link Clock} that is monotonic within a JVM.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Captures {@code (Instant.now(), System.nanoTime())} once at construction as the epoch
 * pair.</li>
 * <li>{@code instant()} returns {@code epochInstant.plusNanos(System.nanoTime() - epochNanos)} —
 * monotonic in wall-clock seconds because {@code System.nanoTime()} is guaranteed non-decreasing
 * within a JVM.</li>
 * <li>{@code getZone()} returns {@link java.time.ZoneOffset#UTC}.</li>
 * <li>{@code withZone(zone)} throws {@link UnsupportedOperationException} — a monotonic tick source
 * does not express calendar concepts; rejecting the call surfaces misuse rather than silently
 * returning a non-monotonic clock.</li>
 * <li>Thread-safe: only reads {@code System.nanoTime()} after construction; all fields are
 * {@code final}.</li>
 * </ul>
 *
 * <p>
 * Governed by: F04.R53
 */
public final class MonotonicClock extends Clock {

    private final long epochNanos;
    private final Instant epochInstant;

    /**
     * Creates a monotonic clock anchored to the wall-clock instant observed at construction time.
     *
     * <p>
     * The epoch pair is captured as {@code System.nanoTime()} followed by {@code Instant.now()}.
     * The small ordering skew (a few microseconds of ordinary system overhead) is immaterial for
     * grace-period duration comparisons measured in seconds-to-minutes. Averaging the pre- and
     * post- {@code nanoTime} reads is omitted for simplicity.
     */
    public MonotonicClock() {
        this.epochNanos = System.nanoTime();
        this.epochInstant = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        throw new UnsupportedOperationException(
                "MonotonicClock has no zone semantics; use getZone() — zone=" + zone);
    }

    @Override
    public Instant instant() {
        // Safe against overflow for up to ~292 years of uptime because the subtraction operates
        // on signed longs and wraps consistently; practical JVM uptimes fall well inside that
        // window.
        final long elapsedNanos = System.nanoTime() - epochNanos;
        assert elapsedNanos >= 0
                : "System.nanoTime() must be non-decreasing since construction; got elapsedNanos="
                        + elapsedNanos;
        return epochInstant.plusNanos(elapsedNanos);
    }
}

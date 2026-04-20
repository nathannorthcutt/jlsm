package jlsm.engine.cluster.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MonotonicClock} — a {@link Clock} anchored at construction whose
 * {@code instant()} is monotonically non-decreasing even across wall-clock corrections (governed by
 * F04.R53).
 */
final class MonotonicClockTest {

    @Test
    @Timeout(5)
    void instant_returnsAnInstantCloseToWallClock() {
        final MonotonicClock clock = new MonotonicClock();
        final Instant sample = clock.instant();
        final Instant wall = Instant.now();
        assertNotNull(sample, "instant() must never return null");
        // Sanity: should be within a few seconds of wall clock, NOT 1970 or a far-future value.
        final Duration delta = Duration.between(sample, wall).abs();
        assertTrue(delta.compareTo(Duration.ofSeconds(5)) < 0,
                "MonotonicClock.instant() should be within 5s of wall clock, got delta=" + delta);
    }

    @Test
    @Timeout(5)
    void instant_isMonotonicNonDecreasingAcrossRepeatedCalls() {
        final MonotonicClock clock = new MonotonicClock();
        final int samples = 10_000;
        Instant previous = clock.instant();
        for (int i = 1; i < samples; i++) {
            final Instant current = clock.instant();
            assertFalse(current.isBefore(previous),
                    "MonotonicClock.instant() went backward at iteration " + i + ": previous="
                            + previous + " current=" + current);
            previous = current;
        }
    }

    @Test
    @Timeout(5)
    void instant_advancesOverTime() throws InterruptedException {
        final MonotonicClock clock = new MonotonicClock();
        final Instant first = clock.instant();
        Thread.sleep(50);
        final Instant second = clock.instant();
        final long elapsedMs = Duration.between(first, second).toMillis();
        // Loose bounds: must advance by at least 20ms but not wildly (5s tolerance for CI jitter).
        assertTrue(elapsedMs >= 20,
                "MonotonicClock should advance by at least 20ms after 50ms sleep; got " + elapsedMs
                        + "ms");
        assertTrue(elapsedMs < 5_000,
                "MonotonicClock advance should be bounded; got " + elapsedMs + "ms");
    }

    @Test
    @Timeout(5)
    void getZone_returnsUtc() {
        final MonotonicClock clock = new MonotonicClock();
        assertEquals(ZoneOffset.UTC, clock.getZone(), "MonotonicClock.getZone() must return UTC");
    }

    @Test
    @Timeout(5)
    void withZone_throwsUnsupportedOperationException() {
        final MonotonicClock clock = new MonotonicClock();
        assertThrows(UnsupportedOperationException.class,
                () -> clock.withZone(ZoneOffset.ofHours(1)),
                "withZone should reject calendar rebinding — monotonic time has no zone");
    }

    @Test
    @Timeout(5)
    void withZone_rejectsNullZone() {
        final MonotonicClock clock = new MonotonicClock();
        assertThrows(NullPointerException.class, () -> clock.withZone(null),
                "withZone(null) must fail fast with NullPointerException before any other check");
    }

    @Test
    @Timeout(5)
    void millis_consistentWithInstant() {
        final MonotonicClock clock = new MonotonicClock();
        final long millis = clock.millis();
        final long instantMillis = clock.instant().toEpochMilli();
        final long skew = Math.abs(instantMillis - millis);
        // Two separate reads of nanoTime can differ by a small margin; allow a generous tolerance.
        assertTrue(skew <= 50,
                "millis() and instant().toEpochMilli() should be consistent; skew=" + skew + "ms");
    }

    @Test
    @Timeout(5)
    void twoInstances_independentEpochs() {
        final MonotonicClock a = new MonotonicClock();
        final MonotonicClock b = new MonotonicClock();
        final Instant sampleA = a.instant();
        final Instant sampleB = b.instant();
        final Instant wall = Instant.now();
        // Both clocks must produce plausible times — not frozen epoch or corrupted state.
        assertTrue(Duration.between(sampleA, wall).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "First instance should produce a plausible time");
        assertTrue(Duration.between(sampleB, wall).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "Second instance should produce a plausible time");
    }
}

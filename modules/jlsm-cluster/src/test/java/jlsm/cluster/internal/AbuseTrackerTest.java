package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AbuseTracker} — sliding-window violation counter for R37c.
 *
 * @spec transport.multiplexed-framing.R37c
 */
class AbuseTrackerTest {

    @Test
    void belowThresholdDoesNotTrigger() {
        AbuseTracker t = new AbuseTracker(4, 60_000L, () -> 1000L);
        assertFalse(t.recordViolation());
        assertFalse(t.recordViolation());
        assertFalse(t.recordViolation());
        assertFalse(t.recordViolation()); // exactly at threshold; not over
    }

    @Test
    void crossingThresholdTriggers() {
        AbuseTracker t = new AbuseTracker(4, 60_000L, () -> 1000L);
        for (int i = 0; i < 4; i++) {
            t.recordViolation();
        }
        assertTrue(t.recordViolation(), "5th violation in window crosses threshold of 4");
    }

    @Test
    void violationsExpireFromWindow() {
        long[] now = { 1000L };
        AbuseTracker t = new AbuseTracker(4, 60_000L, () -> now[0]);
        for (int i = 0; i < 4; i++) {
            t.recordViolation();
        }
        // Advance time past the window
        now[0] = 70_000L; // 69s after first violation
        // Old violations expire; this one is the only one in the new window
        assertFalse(t.recordViolation());
    }

    @Test
    void violationsAtWindowEdgeStillCount() {
        long[] now = { 1000L };
        AbuseTracker t = new AbuseTracker(4, 60_000L, () -> now[0]);
        for (int i = 0; i < 4; i++) {
            t.recordViolation();
        }
        // Just at edge — exactly window-size later means first violation is at the boundary
        now[0] = 60_999L; // 59.999s after first; still within 60s window
        assertTrue(t.recordViolation(), "5th within window crosses threshold");
    }

    @Test
    void rejectsZeroOrNegativeThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new AbuseTracker(0, 60_000L, () -> 0L));
    }

    @Test
    void rejectsZeroOrNegativeWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AbuseTracker(4, 0L, () -> 0L));
    }

    @Test
    void resetClearsCounter() {
        AbuseTracker t = new AbuseTracker(4, 60_000L, () -> 1000L);
        for (int i = 0; i < 4; i++) {
            t.recordViolation();
        }
        t.reset();
        assertFalse(t.recordViolation());
    }
}

package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PhiAccrualFailureDetector}.
 *
 * <p>
 * All timing-sensitive tests use explicit nanosecond timestamps via the overloaded
 * {@code recordHeartbeat(node, timestampNanos)} and {@code phi(node, nowNanos)} methods. This
 * eliminates flakiness from GC pauses, thread scheduling, and {@code Thread.sleep()} variance.
 */
class PhiAccrualFailureDetectorTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 7001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 7002);

    /** Nanoseconds per millisecond — used for readable timestamp arithmetic. */
    private static final long MS = 1_000_000L;

    // --- Constructor validation ---

    @Test
    void constructorRejectsWindowSizeBelowTwo() {
        assertThrows(IllegalArgumentException.class, () -> new PhiAccrualFailureDetector(1));
    }

    @Test
    void constructorRejectsWindowSizeZero() {
        assertThrows(IllegalArgumentException.class, () -> new PhiAccrualFailureDetector(0));
    }

    @Test
    void constructorRejectsNegativeWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new PhiAccrualFailureDetector(-5));
    }

    @Test
    void constructorAcceptsWindowSizeTwo() {
        assertDoesNotThrow(() -> new PhiAccrualFailureDetector(2));
    }

    // --- phi returns 0.0 until >= 2 heartbeats ---

    @Test
    void phiReturnsZeroForUnknownNode() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t = 1_000_000 * MS;
        assertEquals(0.0, detector.phi(NODE_A, t));
    }

    @Test
    void phiReturnsZeroAfterSingleHeartbeat() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t = 1_000_000 * MS;
        detector.recordHeartbeat(NODE_A, t);
        assertEquals(0.0, detector.phi(NODE_A, t + 10 * MS));
    }

    // --- Input validation ---

    @Test
    void recordHeartbeatRejectsNull() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.recordHeartbeat(null));
    }

    @Test
    void phiRejectsNull() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.phi(null));
    }

    @Test
    void isAvailableRejectsNullNode() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.isAvailable(null, 8.0));
    }

    @Test
    void isAvailableRejectsNonPositiveThreshold() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(IllegalArgumentException.class, () -> detector.isAvailable(NODE_A, 0.0));
        assertThrows(IllegalArgumentException.class, () -> detector.isAvailable(NODE_A, -1.0));
    }

    // --- phi computation with known intervals ---

    @Test
    void phiIsFiniteAfterTwoHeartbeats() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;
        detector.recordHeartbeat(NODE_A, t0);
        detector.recordHeartbeat(NODE_A, t0 + 50 * MS);

        // phi queried immediately after second heartbeat — should be near zero
        final double phi = detector.phi(NODE_A, t0 + 50 * MS);
        assertTrue(phi >= 0.0, "phi should be non-negative, got: " + phi);
        assertTrue(Double.isFinite(phi), "phi should be finite, got: " + phi);
    }

    @Test
    void phiIncreasesWithElapsedTime() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;

        // Record heartbeats at exactly 50ms intervals
        for (int i = 0; i < 5; i++) {
            detector.recordHeartbeat(NODE_A, t0 + i * 50 * MS);
        }

        // phi queried just after last heartbeat
        final long tSoon = t0 + 4 * 50 * MS + 1 * MS;
        final double phiSoon = detector.phi(NODE_A, tSoon);

        // phi queried 300ms after last heartbeat
        final long tLater = t0 + 4 * 50 * MS + 300 * MS;
        final double phiLater = detector.phi(NODE_A, tLater);

        assertTrue(phiLater > phiSoon,
                "phi should increase with elapsed time: soon=" + phiSoon + ", later=" + phiLater);
    }

    // --- Multiple nodes tracked independently ---

    @Test
    void nodesTrackedIndependently() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;

        // Record heartbeats for both nodes at 50ms intervals
        for (int i = 0; i < 3; i++) {
            final long t = t0 + i * 50 * MS;
            detector.recordHeartbeat(NODE_A, t);
            detector.recordHeartbeat(NODE_B, t);
        }

        // NODE_A stops heartbeating; NODE_B continues for 3 more intervals
        for (int i = 0; i < 3; i++) {
            detector.recordHeartbeat(NODE_B, t0 + (3 + i) * 50 * MS);
        }

        // Query both at the same instant — eliminates GC-pause flakiness
        final long tNow = t0 + 6 * 50 * MS;
        final double phiA = detector.phi(NODE_A, tNow);
        final double phiB = detector.phi(NODE_B, tNow);

        assertTrue(phiA > phiB, "NODE_A should have higher phi (no recent heartbeats): phiA=" + phiA
                + ", phiB=" + phiB);
    }

    // --- isAvailable ---

    @Test
    void isAvailableReturnsTrueForUnknownNode() {
        final var detector = new PhiAccrualFailureDetector(10);
        // phi=0.0 for unknown node, which is below any positive threshold
        assertTrue(detector.isAvailable(NODE_A, 8.0));
    }

    @Test
    void isAvailableReturnsTrueWhenPhiBelowThreshold() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;
        detector.recordHeartbeat(NODE_A, t0);
        detector.recordHeartbeat(NODE_A, t0 + 50 * MS);

        // Just after a heartbeat, phi should be low
        assertTrue(detector.isAvailable(NODE_A, 8.0, t0 + 50 * MS));
    }

    @Test
    void isAvailableReturnsFalseWhenPhiAboveThreshold() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;

        // Record heartbeats at 20ms intervals
        for (int i = 0; i < 5; i++) {
            detector.recordHeartbeat(NODE_A, t0 + i * 20 * MS);
        }

        // Query 500ms after last heartbeat — far longer than 20ms mean interval
        final long tLate = t0 + 4 * 20 * MS + 500 * MS;
        assertFalse(detector.isAvailable(NODE_A, 0.5, tLate),
                "Node should be unavailable after long silence with low threshold");
    }

    // --- Window saturation ---

    @Test
    void windowSaturation() {
        // Window size of 3 — only the last 3 intervals should be retained
        final var detector = new PhiAccrualFailureDetector(3);
        final long t0 = 1_000_000 * MS;

        // Record 10 heartbeats at 20ms intervals — window should only keep last 3
        for (int i = 0; i < 10; i++) {
            detector.recordHeartbeat(NODE_A, t0 + i * 20 * MS);
        }

        // Query just after last heartbeat
        final double phi = detector.phi(NODE_A, t0 + 9 * 20 * MS + 1 * MS);
        assertTrue(phi >= 0.0, "phi should be non-negative after saturation");
        assertTrue(Double.isFinite(phi), "phi should be finite after saturation");
    }

    // --- Deterministic phi computation ---

    @Test
    void phiComputationWithKnownIntervals() {
        final var detector = new PhiAccrualFailureDetector(10);
        final long t0 = 1_000_000 * MS;

        // Record heartbeats at exactly 100ms intervals
        for (int i = 0; i < 5; i++) {
            detector.recordHeartbeat(NODE_A, t0 + i * 100 * MS);
        }

        // Query 1ms after last heartbeat — should be very low phi
        final double phiClose = detector.phi(NODE_A, t0 + 4 * 100 * MS + 1 * MS);
        assertTrue(phiClose >= 0.0, "phi must be non-negative");
        assertTrue(phiClose < 1.0, "phi should be low right after heartbeat, got: " + phiClose);

        // Query 500ms after last heartbeat (5x the mean interval) — should be high phi
        final double phiFar = detector.phi(NODE_A, t0 + 4 * 100 * MS + 500 * MS);
        assertTrue(phiFar > phiClose, "phi should be higher further from last heartbeat: close="
                + phiClose + ", far=" + phiFar);
    }
}

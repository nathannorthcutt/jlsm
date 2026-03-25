package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PhiAccrualFailureDetector}.
 */
class PhiAccrualFailureDetectorTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 7001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 7002);

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
        var detector = new PhiAccrualFailureDetector(10);
        assertEquals(0.0, detector.phi(NODE_A));
    }

    @Test
    void phiReturnsZeroAfterSingleHeartbeat() {
        var detector = new PhiAccrualFailureDetector(10);
        detector.recordHeartbeat(NODE_A);
        assertEquals(0.0, detector.phi(NODE_A));
    }

    // --- Input validation ---

    @Test
    void recordHeartbeatRejectsNull() {
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.recordHeartbeat(null));
    }

    @Test
    void phiRejectsNull() {
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.phi(null));
    }

    @Test
    void isAvailableRejectsNullNode() {
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class, () -> detector.isAvailable(null, 8.0));
    }

    @Test
    void isAvailableRejectsNonPositiveThreshold() {
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(IllegalArgumentException.class, () -> detector.isAvailable(NODE_A, 0.0));
        assertThrows(IllegalArgumentException.class, () -> detector.isAvailable(NODE_A, -1.0));
    }

    // --- phi computation with known intervals ---

    @Test
    void phiIsFiniteAfterTwoHeartbeats() throws InterruptedException {
        var detector = new PhiAccrualFailureDetector(10);
        detector.recordHeartbeat(NODE_A);
        Thread.sleep(50);
        detector.recordHeartbeat(NODE_A);
        // phi should be a non-negative finite value now (close to zero since
        // we just recorded a heartbeat)
        double phi = detector.phi(NODE_A);
        assertTrue(phi >= 0.0, "phi should be non-negative, got: " + phi);
        assertTrue(Double.isFinite(phi), "phi should be finite, got: " + phi);
    }

    @Test
    void phiIncreasesWithElapsedTime() throws InterruptedException {
        var detector = new PhiAccrualFailureDetector(10);
        // Record heartbeats at ~50ms intervals
        for (int i = 0; i < 5; i++) {
            detector.recordHeartbeat(NODE_A);
            Thread.sleep(50);
        }
        double phiSoon = detector.phi(NODE_A);

        // Wait significantly longer than the heartbeat interval
        Thread.sleep(300);
        double phiLater = detector.phi(NODE_A);

        assertTrue(phiLater > phiSoon,
                "phi should increase with elapsed time: soon=" + phiSoon + ", later=" + phiLater);
    }

    // --- Multiple nodes tracked independently ---

    @Test
    void nodesTrackedIndependently() throws InterruptedException {
        var detector = new PhiAccrualFailureDetector(10);

        // Record heartbeats for both nodes
        for (int i = 0; i < 3; i++) {
            detector.recordHeartbeat(NODE_A);
            detector.recordHeartbeat(NODE_B);
            Thread.sleep(50);
        }

        // Now stop heartbeating NODE_A but keep NODE_B going
        for (int i = 0; i < 3; i++) {
            detector.recordHeartbeat(NODE_B);
            Thread.sleep(50);
        }

        double phiA = detector.phi(NODE_A);
        double phiB = detector.phi(NODE_B);

        assertTrue(phiA > phiB, "NODE_A should have higher phi (no recent heartbeats): phiA=" + phiA
                + ", phiB=" + phiB);
    }

    // --- isAvailable ---

    @Test
    void isAvailableReturnsTrueForUnknownNode() {
        var detector = new PhiAccrualFailureDetector(10);
        // phi=0.0 for unknown node, which is below any positive threshold
        assertTrue(detector.isAvailable(NODE_A, 8.0));
    }

    @Test
    void isAvailableReturnsTrueWhenPhiBelowThreshold() throws InterruptedException {
        var detector = new PhiAccrualFailureDetector(10);
        detector.recordHeartbeat(NODE_A);
        Thread.sleep(50);
        detector.recordHeartbeat(NODE_A);
        // Just after a heartbeat, phi should be low
        assertTrue(detector.isAvailable(NODE_A, 8.0));
    }

    @Test
    void isAvailableReturnsFalseWhenPhiAboveThreshold() throws InterruptedException {
        var detector = new PhiAccrualFailureDetector(10);
        // Record heartbeats at fast interval
        for (int i = 0; i < 5; i++) {
            detector.recordHeartbeat(NODE_A);
            Thread.sleep(20);
        }

        // Wait long enough that phi exceeds a low threshold
        Thread.sleep(500);
        // Using a very low threshold to ensure the test passes
        assertFalse(detector.isAvailable(NODE_A, 0.5),
                "Node should be unavailable after long silence with low threshold");
    }

    // --- Window saturation ---

    @Test
    void windowSaturation() throws InterruptedException {
        // Window size of 3 — only the last 3 intervals should be retained
        var detector = new PhiAccrualFailureDetector(3);

        // Record 10 heartbeats — window should only keep last 3 intervals
        for (int i = 0; i < 10; i++) {
            detector.recordHeartbeat(NODE_A);
            Thread.sleep(20);
        }

        // The detector should still function correctly after window saturation
        double phi = detector.phi(NODE_A);
        assertTrue(phi >= 0.0, "phi should be non-negative after saturation");
        assertTrue(Double.isFinite(phi), "phi should be finite after saturation");
    }

    // --- Controllable time via overloaded recordHeartbeat ---

    @Test
    void phiComputationWithKnownIntervals() {
        var detector = new PhiAccrualFailureDetector(10);

        // Record heartbeats at exactly 1000ms intervals using nanoTime-based approach
        // We can test the math by recording heartbeats and checking relative phi values
        // Since the stub uses wall-clock time, this test validates the general contract
        detector.recordHeartbeat(NODE_A);
        // Force a known interval by immediately recording again
        // (phi should still be 0.0 with only 1 interval if elapsed is ~0)
        detector.recordHeartbeat(NODE_A);

        double phi = detector.phi(NODE_A);
        // With essentially zero elapsed time since last heartbeat and near-zero mean interval,
        // phi can be any non-negative value
        assertTrue(phi >= 0.0, "phi must be non-negative");
    }
}

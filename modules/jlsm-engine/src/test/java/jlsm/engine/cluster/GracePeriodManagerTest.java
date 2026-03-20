package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.GracePeriodManager;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GracePeriodManager} — tracks departed nodes and manages
 * the grace period before permanent removal.
 */
final class GracePeriodManagerTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final Instant BASE = Instant.parse("2026-03-20T00:00:00Z");

    // --- Constructor validation ---

    @Test
    void constructor_nullGracePeriodThrows() {
        assertThrows(NullPointerException.class, () -> new GracePeriodManager(null));
    }

    @Test
    void constructor_zeroGracePeriodThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new GracePeriodManager(Duration.ZERO));
    }

    @Test
    void constructor_negativeGracePeriodThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new GracePeriodManager(Duration.ofSeconds(-1)));
    }

    @Test
    void constructor_positiveGracePeriodSucceeds() {
        assertDoesNotThrow(() -> new GracePeriodManager(Duration.ofSeconds(1)));
    }

    // --- recordDeparture + isInGracePeriod ---

    @Test
    void isInGracePeriod_unknownNodeReturnsFalse() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertFalse(mgr.isInGracePeriod(NODE_A),
                "Unknown node should not be in grace period");
    }

    @Test
    void isInGracePeriod_withinGraceReturnsTrue() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        mgr.recordDeparture(NODE_A, Instant.now());
        // isInGracePeriod checks against current time — the departure was just recorded
        // and 5 minutes haven't passed yet in wall-clock time
        assertTrue(mgr.isInGracePeriod(NODE_A),
                "Recently departed node should be in grace period");
    }

    @Test
    void isInGracePeriod_afterGraceReturnsFalse() {
        // Use a very short grace period so wall-clock passes it
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofNanos(1));
        mgr.recordDeparture(NODE_A, Instant.now().minusSeconds(1));
        assertFalse(mgr.isInGracePeriod(NODE_A),
                "Node whose grace period expired should not be in grace period");
    }

    // --- expiredDepartures ---

    @Test
    void expiredDepartures_emptyWhenNoDepartures() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        Set<NodeAddress> expired = mgr.expiredDepartures();
        assertNotNull(expired);
        assertTrue(expired.isEmpty());
    }

    @Test
    void expiredDepartures_returnsNodesAfterGrace() {
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofNanos(1));
        mgr.recordDeparture(NODE_A, Instant.now().minusSeconds(1));
        mgr.recordDeparture(NODE_B, Instant.now().minusSeconds(1));

        Set<NodeAddress> expired = mgr.expiredDepartures();
        assertTrue(expired.contains(NODE_A));
        assertTrue(expired.contains(NODE_B));
    }

    @Test
    void expiredDepartures_excludesNodesStillInGrace() {
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofHours(1));
        mgr.recordDeparture(NODE_A, Instant.now());
        mgr.recordDeparture(NODE_B, Instant.now());

        Set<NodeAddress> expired = mgr.expiredDepartures();
        assertTrue(expired.isEmpty(),
                "Nodes still within grace period should not appear as expired");
    }

    @Test
    void expiredDepartures_mixedGraceAndExpired() {
        // NODE_A: departed long ago (expired), NODE_B: departed now (in grace)
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofMinutes(5));
        mgr.recordDeparture(NODE_A, Instant.now().minus(Duration.ofMinutes(10)));
        mgr.recordDeparture(NODE_B, Instant.now());

        Set<NodeAddress> expired = mgr.expiredDepartures();
        assertTrue(expired.contains(NODE_A), "NODE_A should be expired");
        assertFalse(expired.contains(NODE_B), "NODE_B should still be in grace");
    }

    // --- recordReturn ---

    @Test
    void recordReturn_cancelsGracePeriod() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        mgr.recordDeparture(NODE_A, Instant.now());
        assertTrue(mgr.isInGracePeriod(NODE_A));

        mgr.recordReturn(NODE_A);
        assertFalse(mgr.isInGracePeriod(NODE_A),
                "Returned node should no longer be in grace period");
    }

    @Test
    void recordReturn_removesFromExpiredDepartures() {
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofNanos(1));
        mgr.recordDeparture(NODE_A, Instant.now().minusSeconds(1));
        // Should be expired now
        assertTrue(mgr.expiredDepartures().contains(NODE_A));

        mgr.recordReturn(NODE_A);
        assertFalse(mgr.expiredDepartures().contains(NODE_A),
                "Returned node should not appear in expired departures");
    }

    @Test
    void recordReturn_noopForUnknownNode() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertDoesNotThrow(() -> mgr.recordReturn(NODE_A),
                "Returning an unknown node should be a no-op");
    }

    // --- Input validation ---

    @Test
    void recordDeparture_nullNodeThrows() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertThrows(NullPointerException.class,
                () -> mgr.recordDeparture(null, BASE));
    }

    @Test
    void recordDeparture_nullInstantThrows() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertThrows(NullPointerException.class,
                () -> mgr.recordDeparture(NODE_A, null));
    }

    @Test
    void isInGracePeriod_nullNodeThrows() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertThrows(NullPointerException.class,
                () -> mgr.isInGracePeriod(null));
    }

    @Test
    void recordReturn_nullNodeThrows() {
        GracePeriodManager mgr = new GracePeriodManager(GRACE);
        assertThrows(NullPointerException.class,
                () -> mgr.recordReturn(null));
    }

    // --- Multiple departures of same node ---

    @Test
    void recordDeparture_updatesTimestampOnRedeparture() {
        GracePeriodManager mgr = new GracePeriodManager(Duration.ofMinutes(5));
        // First departure long ago
        mgr.recordDeparture(NODE_A, Instant.now().minus(Duration.ofMinutes(10)));
        assertTrue(mgr.expiredDepartures().contains(NODE_A));

        // Re-depart now — should reset grace window
        mgr.recordDeparture(NODE_A, Instant.now());
        assertTrue(mgr.isInGracePeriod(NODE_A),
                "Re-departure should reset the grace window");
        assertFalse(mgr.expiredDepartures().contains(NODE_A));
    }
}

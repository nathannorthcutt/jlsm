package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraceGatedRebalancer} — grace-period drain that applies differential rebalancing
 * only to partitions previously owned by expired departures.
 */
final class GraceGatedRebalancerTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "host-a", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "host-b", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "host-c", 8003);

    private ScheduledExecutorService scheduler;
    private ControllableClock clock;
    private GracePeriodManager graceMgr;
    private SpyOwnership ownership;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "grace-gated-test-scheduler");
            t.setDaemon(true);
            return t;
        });
        clock = new ControllableClock(Instant.parse("2026-04-20T00:00:00Z"));
        graceMgr = new GracePeriodManager(Duration.ofMillis(100), clock);
        ownership = new SpyOwnership();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // --- constructor validation ---

    @Test
    void constructor_nullGraceMgr_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(null, ownership,
                () -> view(1), n -> Set.of(), scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullOwnership_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(graceMgr, null,
                () -> view(1), n -> Set.of(), scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullViewSupplier_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(graceMgr, ownership,
                null, n -> Set.of(), scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullPartitionFn_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1), null, scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullScheduler_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1), n -> Set.of(), null, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullInterval_throws() {
        assertThrows(NullPointerException.class, () -> new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1), n -> Set.of(), scheduler, null));
    }

    @Test
    void constructor_zeroInterval_throws() {
        assertThrows(IllegalArgumentException.class, () -> new GraceGatedRebalancer(graceMgr,
                ownership, () -> view(1), n -> Set.of(), scheduler, Duration.ZERO));
    }

    // --- behaviour ---

    @Test
    void start_doesNotRebalanceBeforeGraceExpires() throws Exception {
        graceMgr.recordDeparture(NODE_A, clock.instant());
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of("p1", "p2"), scheduler, Duration.ofMillis(15));
        rebalancer.start();
        try {
            // Advance clock inside grace window
            Thread.sleep(60);
            assertEquals(0, ownership.invocations.size(),
                    "no rebalance should occur while NODE_A is still in grace; got="
                            + ownership.invocations);
        } finally {
            rebalancer.stop();
        }
    }

    @Test
    void start_rebalancesOnlyAffectedPartitionsOnExpiry() throws Exception {
        graceMgr.recordDeparture(NODE_A, clock.instant());
        AtomicReference<MembershipView> viewRef = new AtomicReference<>(view(1, NODE_B, NODE_C));
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                viewRef::get, n -> Set.of("p1", "p2"), scheduler, Duration.ofMillis(15));
        rebalancer.start();
        try {
            // Expire grace by advancing the clock well past the window
            clock.advance(Duration.ofMillis(500));
            assertTrue(waitForInvocations(ownership, 1, 2_000),
                    "rebalancer must call differentialAssign once grace expires");
            DifferentialInvocation inv = ownership.invocations.get(0);
            assertEquals(Set.of("p1", "p2"), inv.partitions,
                    "only the departed node's partitions must be reassigned");
            assertEquals(viewRef.get(), inv.newView, "newView must come from the view supplier");
        } finally {
            rebalancer.stop();
        }
    }

    @Test
    void cancelPending_abortsScheduledRebalance() throws Exception {
        graceMgr.recordDeparture(NODE_A, clock.instant());
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of("p1"), scheduler, Duration.ofMillis(15));
        rebalancer.start();
        try {
            // Return before grace expires
            rebalancer.cancelPending(NODE_A);
            // After cancellation, expire the grace window — the record must have been cleared.
            clock.advance(Duration.ofMillis(500));
            Thread.sleep(100);
            assertEquals(0, ownership.invocations.size(),
                    "cancelPending must prevent subsequent rebalance; got="
                            + ownership.invocations);
            assertFalse(graceMgr.isInGracePeriod(NODE_A),
                    "cancelPending should clear the grace record (recordReturn)");
        } finally {
            rebalancer.stop();
        }
    }

    @Test
    void cancelPending_nullAddress_throws() {
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of(), scheduler, Duration.ofMillis(30));
        assertThrows(NullPointerException.class, () -> rebalancer.cancelPending(null));
    }

    @Test
    void start_isIdempotent() throws Exception {
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of("p1"), scheduler, Duration.ofMillis(15));
        rebalancer.start();
        rebalancer.start();
        try {
            Thread.sleep(60);
            // No departures → no rebalances even after multiple starts
            assertEquals(0, ownership.invocations.size());
        } finally {
            rebalancer.stop();
        }
    }

    @Test
    void stop_isIdempotent() {
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of(), scheduler, Duration.ofMillis(30));
        assertDoesNotThrow(rebalancer::stop, "stop before start must be a no-op");
        assertDoesNotThrow(rebalancer::stop, "stop twice must be a no-op");
    }

    @Test
    void stop_cancelsFurtherWork() throws Exception {
        graceMgr.recordDeparture(NODE_A, clock.instant());
        GraceGatedRebalancer rebalancer = new GraceGatedRebalancer(graceMgr, ownership,
                () -> view(1, NODE_B), n -> Set.of("p1"), scheduler, Duration.ofMillis(15));
        rebalancer.start();
        rebalancer.stop();
        clock.advance(Duration.ofMillis(500));
        Thread.sleep(80);
        assertEquals(0, ownership.invocations.size(),
                "stopped rebalancer must not mutate ownership; got=" + ownership.invocations);
    }

    // --- helpers ---

    private static boolean waitForInvocations(SpyOwnership s, int target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (s.invocations.size() >= target) {
                return true;
            }
            Thread.sleep(10);
        }
        return s.invocations.size() >= target;
    }

    private static MembershipView view(long epoch, NodeAddress... addrs) {
        Set<Member> members = new HashSet<>();
        for (NodeAddress a : addrs) {
            members.add(new Member(a, MemberState.ALIVE, 0));
        }
        return new MembershipView(epoch, members, Instant.parse("2026-04-20T00:00:00Z"));
    }

    /** Captures calls to {@link RendezvousOwnership#differentialAssign(...)}. */
    static final class SpyOwnership extends RendezvousOwnership {
        final List<DifferentialInvocation> invocations = new CopyOnWriteArrayList<>();

        @Override
        public Set<String> differentialAssign(MembershipView oldView, MembershipView newView,
                Set<String> affectedPartitionIds) {
            invocations.add(
                    new DifferentialInvocation(oldView, newView, Set.copyOf(affectedPartitionIds)));
            return Set.copyOf(affectedPartitionIds);
        }
    }

    record DifferentialInvocation(MembershipView oldView, MembershipView newView,
            Set<String> partitions) {
    }

    /** A test {@link Clock} whose instant can be advanced from the outside. */
    static final class ControllableClock extends Clock {
        private final ConcurrentHashMap<String, Instant> ref = new ConcurrentHashMap<>();

        ControllableClock(Instant start) {
            ref.put("now", start);
        }

        void advance(Duration d) {
            ref.compute("now", (k, v) -> v.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ref.get("now");
        }
    }
}

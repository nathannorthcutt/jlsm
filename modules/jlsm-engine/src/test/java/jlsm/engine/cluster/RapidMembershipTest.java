package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RapidMembership}.
 */
class RapidMembershipTest {

    private static final NodeAddress ADDR_1 = new NodeAddress("node-1", "localhost", 8001);
    private static final NodeAddress ADDR_2 = new NodeAddress("node-2", "localhost", 8002);
    private static final NodeAddress ADDR_3 = new NodeAddress("node-3", "localhost", 8003);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception first = null;
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception e) {
                if (first == null)
                    first = e;
                else
                    first.addSuppressed(e);
            }
        }
        closeables.clear();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        if (first != null)
            throw first;
    }

    // --- Constructor validation ---

    @Test
    void constructorRejectsNullLocalAddress() {
        var transport = createTransport(ADDR_1);
        var discovery = new InJvmDiscoveryProvider();
        var config = ClusterConfig.builder().build();
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class,
                () -> new RapidMembership(null, transport, discovery, config, detector));
    }

    @Test
    void constructorRejectsNullTransport() {
        var discovery = new InJvmDiscoveryProvider();
        var config = ClusterConfig.builder().build();
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class,
                () -> new RapidMembership(ADDR_1, null, discovery, config, detector));
    }

    @Test
    void constructorRejectsNullDiscovery() {
        var transport = createTransport(ADDR_1);
        var config = ClusterConfig.builder().build();
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class,
                () -> new RapidMembership(ADDR_1, transport, null, config, detector));
    }

    @Test
    void constructorRejectsNullConfig() {
        var transport = createTransport(ADDR_1);
        var discovery = new InJvmDiscoveryProvider();
        var detector = new PhiAccrualFailureDetector(10);
        assertThrows(NullPointerException.class,
                () -> new RapidMembership(ADDR_1, transport, discovery, null, detector));
    }

    @Test
    void constructorRejectsNullFailureDetector() {
        var transport = createTransport(ADDR_1);
        var discovery = new InJvmDiscoveryProvider();
        var config = ClusterConfig.builder().build();
        assertThrows(NullPointerException.class,
                () -> new RapidMembership(ADDR_1, transport, discovery, config, null));
    }

    // --- Single node cluster ---

    @Test
    void singleNodeStartsCluster() throws Exception {
        var membership = createMembership(ADDR_1);
        membership.start(List.of());

        MembershipView view = membership.currentView();
        assertNotNull(view, "view should not be null after start");
        assertEquals(1, view.members().size(), "single-node cluster should have 1 member");
        assertTrue(view.isMember(ADDR_1), "local node should be a member of its own cluster");
    }

    @Test
    void singleNodeViewHasEpochZeroOrHigher() throws Exception {
        var membership = createMembership(ADDR_1);
        membership.start(List.of());

        MembershipView view = membership.currentView();
        assertTrue(view.epoch() >= 0, "epoch should be non-negative");
    }

    // --- addListener ---

    @Test
    void addListenerRejectsNull() throws Exception {
        var membership = createMembership(ADDR_1);
        assertThrows(NullPointerException.class, () -> membership.addListener(null));
    }

    // --- Multi-node cluster formation ---

    @Test
    void twoNodeClusterFormation() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        // Give the protocol time to converge
        Thread.sleep(500);

        MembershipView view1 = m1.currentView();
        MembershipView view2 = m2.currentView();

        // Both nodes should see each other
        assertTrue(view1.isMember(ADDR_1), "node-1 should see itself");
        assertTrue(view1.isMember(ADDR_2), "node-1 should see node-2");
        assertTrue(view2.isMember(ADDR_1), "node-2 should see node-1");
        assertTrue(view2.isMember(ADDR_2), "node-2 should see itself");
    }

    @Test
    void threeNodeClusterFormation() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));
        discovery.register(ADDR_2);

        var m3 = createMembership(ADDR_3, discovery);
        m3.start(List.of(ADDR_1));

        // Give the protocol time to converge
        Thread.sleep(500);

        MembershipView view1 = m1.currentView();
        assertEquals(3, view1.members().size(), "all 3 nodes should be in the cluster view");
    }

    // --- Listener callbacks ---

    @Test
    void listenerNotifiedOnMemberJoin() throws Exception {
        var discovery = new InJvmDiscoveryProvider();
        var joinedMembers = new CopyOnWriteArrayList<Member>();
        var latch = new CountDownLatch(1);

        var m1 = createMembership(ADDR_1, discovery);
        m1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
            }

            @Override
            public void onMemberJoined(Member member) {
                joinedMembers.add(member);
                latch.countDown();
            }

            @Override
            public void onMemberLeft(Member member) {
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });
        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        boolean notified = latch.await(2, TimeUnit.SECONDS);
        assertTrue(notified, "listener should be notified when a member joins");
        assertFalse(joinedMembers.isEmpty(), "at least one member-joined event expected");
    }

    @Test
    void listenerNotifiedOnViewChange() throws Exception {
        var discovery = new InJvmDiscoveryProvider();
        var viewChanges = new CopyOnWriteArrayList<MembershipView>();
        var latch = new CountDownLatch(1);

        var m1 = createMembership(ADDR_1, discovery);
        m1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
                viewChanges.add(newView);
                latch.countDown();
            }

            @Override
            public void onMemberJoined(Member member) {
            }

            @Override
            public void onMemberLeft(Member member) {
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });
        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        boolean notified = latch.await(2, TimeUnit.SECONDS);
        assertTrue(notified, "listener should be notified of view changes");
    }

    // --- Graceful leave ---

    @Test
    void leaveRemovesNodeFromView() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        Thread.sleep(500);

        // Verify both nodes are in the view
        assertEquals(2, m1.currentView().members().size());

        // Node 2 leaves gracefully
        m2.leave();

        Thread.sleep(500);

        // Node 1 should eventually see node 2 as removed or dead
        MembershipView view1 = m1.currentView();
        long aliveCount = view1.members().stream().filter(m -> m.state() == MemberState.ALIVE)
                .count();
        assertEquals(1, aliveCount, "after leave, only 1 node should be ALIVE in node-1's view");
    }

    // --- Close ---

    @Test
    void closeIsIdempotent() throws Exception {
        var membership = createMembership(ADDR_1);
        membership.start(List.of());
        membership.close();
        assertDoesNotThrow(() -> membership.close(), "close() should be idempotent");
    }

    @Test
    void currentViewAfterStartReturnsNonNull() throws Exception {
        var membership = createMembership(ADDR_1);
        membership.start(List.of());
        assertNotNull(membership.currentView());
    }

    // --- Epoch progression ---

    @Test
    void epochIncreasesOnViewChange() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        m1.start(List.of());
        discovery.register(ADDR_1);

        long initialEpoch = m1.currentView().epoch();

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        Thread.sleep(500);

        long newEpoch = m1.currentView().epoch();
        assertTrue(newEpoch > initialEpoch, "epoch should increase after a view change: initial="
                + initialEpoch + ", new=" + newEpoch);
    }

    // --- Helpers ---

    private InJvmTransport createTransport(NodeAddress addr) {
        var transport = new InJvmTransport(addr);
        closeables.add(transport);
        return transport;
    }

    private RapidMembership createMembership(NodeAddress addr) {
        return createMembership(addr, new InJvmDiscoveryProvider());
    }

    private RapidMembership createMembership(NodeAddress addr, DiscoveryProvider discovery) {
        var transport = createTransport(addr);
        var config = ClusterConfig.builder().protocolPeriod(java.time.Duration.ofMillis(100))
                .pingTimeout(java.time.Duration.ofMillis(50)).build();
        var detector = new PhiAccrualFailureDetector(10);
        var membership = new RapidMembership(addr, transport, discovery, config, detector);
        closeables.add(membership);
        return membership;
    }
}

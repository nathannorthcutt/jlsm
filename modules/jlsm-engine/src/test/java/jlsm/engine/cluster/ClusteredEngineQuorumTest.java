package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link ClusteredEngine#operationalMode()} transitions on quorum loss / recovery (@spec
 * F04.R41, R42).
 */
final class ClusteredEngineQuorumTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 9201);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 9202);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "localhost", 9203);
    private static final NodeAddress NODE_D = new NodeAddress("node-d", "localhost", 9204);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");

    private InJvmTransport transport;
    private StubMembershipProtocol membership;
    private RendezvousOwnership ownership;
    private GracePeriodManager gracePeriod;
    private ClusterConfig config;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        transport = new InJvmTransport(NODE_A);
        membership = new StubMembershipProtocol();
        ownership = new RendezvousOwnership();
        gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        config = ClusterConfig.builder().consensusQuorumPercent(75).build();
    }

    @AfterEach
    void tearDown() {
        transport.close();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @Test
    void operationalMode_initialValueIsNormal() {
        ClusteredEngine engine = buildEngine();
        try {
            assertEquals(ClusterOperationalMode.NORMAL, engine.operationalMode());
        } finally {
            closeQuietly(engine);
        }
    }

    @Test
    void operationalMode_transitionsToReadOnly_onQuorumLoss() throws Exception {
        // Initial view: 4 alive members, quorum achieved at 75%.
        membership.setViewNoNotify(aliveView(1, NODE_A, NODE_B, NODE_C, NODE_D));
        ClusteredEngine engine = buildEngine();
        try {
            // Healthy view — NORMAL.
            assertEquals(ClusterOperationalMode.NORMAL, engine.operationalMode());

            // Transition: most members go SUSPECTED, breaking quorum (DEAD is excluded from
            // the quorum denominator per MembershipView.hasQuorum; SUSPECTED is counted).
            membership.setView(
                    mixedView(2, Map.of(NODE_A, MemberState.ALIVE, NODE_B, MemberState.SUSPECTED,
                            NODE_C, MemberState.SUSPECTED, NODE_D, MemberState.SUSPECTED)));
            // Wait a short interval for the listener dispatch to settle.
            assertTrue(
                    waitFor(() -> engine.operationalMode() == ClusterOperationalMode.READ_ONLY,
                            2_000),
                    "engine must transition to READ_ONLY after quorum loss; mode="
                            + engine.operationalMode());
        } finally {
            closeQuietly(engine);
        }
    }

    @Test
    void operationalMode_transitionsBackToNormal_onQuorumRestoration() throws Exception {
        membership.setViewNoNotify(
                mixedView(1, Map.of(NODE_A, MemberState.ALIVE, NODE_B, MemberState.SUSPECTED,
                        NODE_C, MemberState.SUSPECTED, NODE_D, MemberState.SUSPECTED)));
        ClusteredEngine engine = buildEngine();
        try {
            // Force READ_ONLY by pushing the minority view as a "new" view.
            membership.setView(
                    mixedView(2, Map.of(NODE_A, MemberState.ALIVE, NODE_B, MemberState.SUSPECTED,
                            NODE_C, MemberState.SUSPECTED, NODE_D, MemberState.SUSPECTED)));
            assertTrue(waitFor(() -> engine.operationalMode() == ClusterOperationalMode.READ_ONLY,
                    2_000), "engine must enter READ_ONLY first");

            // Restore quorum: all nodes alive again.
            membership.setView(aliveView(3, NODE_A, NODE_B, NODE_C, NODE_D));
            assertTrue(
                    waitFor(() -> engine.operationalMode() == ClusterOperationalMode.NORMAL, 2_000),
                    "engine must transition back to NORMAL when quorum is restored");
        } finally {
            closeQuietly(engine);
        }
    }

    // --- helpers ---

    private ClusteredEngine buildEngine() {
        return ClusteredEngine.builder().localEngine(new StubEngine()).membership(membership)
                .ownership(ownership).gracePeriodManager(gracePeriod).transport(transport)
                .config(config).localAddress(NODE_A).discovery(new InJvmDiscoveryProvider())
                .build();
    }

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    private static MembershipView aliveView(long epoch, NodeAddress... addrs) {
        java.util.HashSet<Member> members = new java.util.HashSet<>();
        for (NodeAddress a : addrs) {
            members.add(new Member(a, MemberState.ALIVE, 0));
        }
        return new MembershipView(epoch, members, NOW);
    }

    private static MembershipView mixedView(long epoch, Map<NodeAddress, MemberState> states) {
        java.util.HashSet<Member> members = new java.util.HashSet<>();
        for (Map.Entry<NodeAddress, MemberState> e : states.entrySet()) {
            members.add(new Member(e.getKey(), e.getValue(), 0));
        }
        return new MembershipView(epoch, members, NOW);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // --- stubs ---

    private static final class StubMembershipProtocol implements MembershipProtocol {
        final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);

        @Override
        public void start(List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        void setView(MembershipView newView) {
            MembershipView old = this.view;
            this.view = newView;
            for (MembershipListener l : listeners) {
                l.onViewChanged(old, newView);
            }
        }

        void setViewNoNotify(MembershipView v) {
            this.view = v;
        }

        @Override
        public void addListener(MembershipListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(MembershipListener listener) {
            listeners.remove(listener);
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }

    private static final class StubEngine implements Engine {
        volatile boolean closed;

        @Override
        public Table createTable(String name, JlsmSchema schema) throws IOException {
            throw new IOException("not implemented");
        }

        @Override
        public Table getTable(String name) throws IOException {
            throw new IOException("no table: " + name);
        }

        @Override
        public void dropTable(String name) {
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return List.of();
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            return null;
        }

        @Override
        public EngineMetrics metrics() {
            return new EngineMetrics(0, 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

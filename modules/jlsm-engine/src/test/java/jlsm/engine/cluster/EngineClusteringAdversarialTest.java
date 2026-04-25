package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.CatalogClusteredTable;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the engine-clustering feature. Targets bugs found during spec analysis
 * round 1.
 *
 * @spec engine.clustering.R55 — local engine remains functional under clustered wrapper
 * @spec engine.clustering.R57 — join rollback on discovery/transport/protocol start failure
 * @spec engine.clustering.R58 — shutdown is idempotent
 * @spec engine.clustering.R80 — close accumulates errors
 * @spec engine.clustering.R103 — rollback unwinds every prior registration step
 */
final class EngineClusteringAdversarialTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 8002);
    private static final NodeAddress NODE_C = new NodeAddress("node-c", "localhost", 8003);
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @AfterEach
    void tearDown() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    // ================================================================
    // IMPL-RISK-2: PhiAccrualFailureDetector — NaN threshold accepted
    // ================================================================

    /** NaN threshold should be rejected by isAvailable(). */
    @Test
    void phiDetector_nanThreshold_throws() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(IllegalArgumentException.class, () -> detector.isAvailable(NODE_A, Double.NaN),
                "NaN threshold must be rejected as non-positive");
    }

    /** NaN threshold should be rejected by the overloaded isAvailable with timestamp. */
    @Test
    void phiDetector_nanThresholdWithTimestamp_throws() {
        final var detector = new PhiAccrualFailureDetector(10);
        assertThrows(IllegalArgumentException.class,
                () -> detector.isAvailable(NODE_A, Double.NaN, System.nanoTime()),
                "NaN threshold must be rejected");
    }

    // ================================================================
    // IMPL-RISK-3: ClusterConfig — NaN phiThreshold passes validation
    // ================================================================

    /** ClusterConfig must reject NaN phiThreshold. */
    @Test
    void clusterConfig_nanPhiThreshold_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().phiThreshold(Double.NaN).build(),
                "NaN phiThreshold must be rejected");
    }

    /** ClusterConfig must reject positive infinity phiThreshold. */
    @Test
    void clusterConfig_positiveInfinityPhiThreshold_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().phiThreshold(Double.POSITIVE_INFINITY).build(),
                "Infinite phiThreshold must be rejected");
    }

    // ================================================================
    // CONTRACT-GAP-1: GracePeriodManager — expired entries never purged
    // ================================================================

    /** After expiredDepartures() reports expired nodes, a subsequent purge should clean them. */
    @Test
    void gracePeriodManager_expiredDeparturesDoNotAccumulateForever() {
        final var mgr = new GracePeriodManager(Duration.ofNanos(1));

        // Record 100 departures in the past
        for (int i = 0; i < 100; i++) {
            mgr.recordDeparture(new NodeAddress("node-" + i, "host", 8000 + i),
                    Instant.now().minusSeconds(10));
        }

        // All should be expired
        final Set<NodeAddress> expired = mgr.expiredDepartures();
        assertEquals(100, expired.size());

        // Purge them
        for (NodeAddress addr : expired) {
            mgr.recordReturn(addr);
        }

        // After purge, should be empty
        assertTrue(mgr.expiredDepartures().isEmpty(),
                "After purging all expired departures, the map should be empty");
    }

    // ================================================================
    // IMPL-RISK-7: ClusteredEngine — departure detection broken for DEAD members
    // ================================================================

    /**
     * When a member transitions from ALIVE to DEAD (graceful leave), the grace period manager must
     * record the departure. The onViewChanged handler must detect state transitions, not just
     * member absence.
     */
    @Test
    void clusteredEngine_departureDetection_firesForDeadMembers() throws Exception {
        final var transport = new InJvmTransport(NODE_A);
        final var stubMembership = new ControllableMembershipProtocol();
        final var ownership = new RendezvousOwnership();
        final var gracePeriod = new GracePeriodManager(Duration.ofMinutes(5));
        final var config = ClusterConfig.builder().build();

        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(new StubEngine())
                .membership(stubMembership).ownership(ownership).gracePeriodManager(gracePeriod)
                .transport(transport).config(config).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        // Initial view: A and B both ALIVE
        final MembershipView viewBefore = new MembershipView(1,
                Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(NODE_B, MemberState.ALIVE, 0)),
                NOW);
        stubMembership.setView(viewBefore);

        // View change: B transitions to DEAD (graceful leave)
        final MembershipView viewAfter = new MembershipView(2,
                Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(NODE_B, MemberState.DEAD, 0)),
                NOW);
        stubMembership.setView(viewAfter);

        // The grace period manager must have recorded NODE_B's departure.
        // Verify by checking isInGracePeriod — it should be true if recorded.
        assertTrue(gracePeriod.isInGracePeriod(NODE_B),
                "Grace period manager must record departure when member transitions to DEAD");

        engine.close();
        transport.close();
    }

    /**
     * When a member transitions from ALIVE to SUSPECTED, it should also be recorded as departed.
     */
    @Test
    void clusteredEngine_departureDetection_firesForSuspectedMembers() throws Exception {
        final var transport = new InJvmTransport(NODE_A);
        final var stubMembership = new ControllableMembershipProtocol();
        final var ownership = new RendezvousOwnership();
        final var gracePeriod = new GracePeriodManager(Duration.ofMinutes(5));
        final var config = ClusterConfig.builder().build();

        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(new StubEngine())
                .membership(stubMembership).ownership(ownership).gracePeriodManager(gracePeriod)
                .transport(transport).config(config).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        // Initial view: A and B both ALIVE
        final MembershipView viewBefore = new MembershipView(1,
                Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(NODE_B, MemberState.ALIVE, 0)),
                NOW);
        stubMembership.setView(viewBefore);

        // View change: B transitions to SUSPECTED
        final MembershipView viewAfter = new MembershipView(2,
                Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(NODE_B, MemberState.SUSPECTED, 0)),
                NOW);
        stubMembership.setView(viewAfter);

        assertTrue(gracePeriod.isInGracePeriod(NODE_B),
                "Grace period manager must record departure when member transitions to SUSPECTED");

        engine.close();
        transport.close();
    }

    // ================================================================
    // IMPL-RISK-8: CatalogClusteredTable.findLocalAddress() returns wrong node
    // ================================================================

    /**
     * CatalogClusteredTable must use the actual local node address for outgoing messages, not an
     * arbitrary live node. Verifies the constructor now requires a localAddress parameter.
     */
    @Test
    void clusteredTable_requiresLocalAddress() {
        final var transport = new InJvmTransport(NODE_A);
        final var membership = new ControllableMembershipProtocol();
        final var meta = new TableMetadata("users", SCHEMA, NOW, TableMetadata.TableState.READY);

        // localAddress must not be null
        assertThrows(NullPointerException.class,
                () -> CatalogClusteredTable.forEngine(meta, transport, membership, null),
                "CatalogClusteredTable must reject null localAddress");

        transport.close();
    }

    // ================================================================
    // IMPL-RISK-4: RapidMembership — missing DEAD notifications in view change proposal
    // ================================================================

    /**
     * When a view change proposal transitions a member from ALIVE to DEAD, the onMemberLeft
     * listener must be notified.
     */
    @Test
    void rapidMembership_viewChangeProposal_notifiesOnMemberDeath() throws Exception {
        final var discovery = new InJvmDiscoveryProvider();
        final var leftMembers = new CopyOnWriteArrayList<Member>();
        final var latch = new CountDownLatch(1);

        // Start node-1
        final var transport1 = new InJvmTransport(NODE_A);
        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofMillis(100))
                .pingTimeout(Duration.ofMillis(50)).build();
        final var detector1 = new PhiAccrualFailureDetector(10);
        final var m1 = new RapidMembership(NODE_A, transport1, discovery, config, detector1);

        m1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
            }

            @Override
            public void onMemberJoined(Member member) {
            }

            @Override
            public void onMemberLeft(Member member) {
                leftMembers.add(member);
                latch.countDown();
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });

        // Start node-2 and join
        final var transport2 = new InJvmTransport(NODE_B);
        final var detector2 = new PhiAccrualFailureDetector(10);
        final var m2 = new RapidMembership(NODE_B, transport2, discovery, config, detector2);

        m1.start(List.of());
        discovery.register(NODE_A);
        m2.start(List.of(NODE_A));

        // Wait for cluster formation
        Thread.sleep(500);

        // Node-2 leaves gracefully
        m2.leave();

        // Wait for the leave notification to propagate
        boolean notified = latch.await(3, TimeUnit.SECONDS);
        assertTrue(notified,
                "onMemberLeft must be called when a member transitions to DEAD via leave");

        m1.close();
        transport1.close();
        transport2.close();
    }

    // ================================================================
    // IMPL-RISK-5: RapidMembership.start() — TOCTOU on started flag
    // ================================================================

    /** start() after start() must throw IllegalStateException. */
    @Test
    void rapidMembership_doubleStartThrows() throws Exception {
        final var transport = new InJvmTransport(NODE_A);
        final var discovery = new InJvmDiscoveryProvider();
        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofMillis(100))
                .pingTimeout(Duration.ofMillis(50)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, transport, discovery, config, detector);

        membership.start(List.of());
        assertThrows(IllegalStateException.class, () -> membership.start(List.of()),
                "Double start must throw IllegalStateException");

        membership.close();
        transport.close();
    }

    // ================================================================
    // Helper classes
    // ================================================================

    /** MembershipProtocol stub that allows externally setting the view and firing listeners. */
    private static final class ControllableMembershipProtocol implements MembershipProtocol {

        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);
        final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();

        void setView(MembershipView newView) {
            final MembershipView old = this.view;
            this.view = newView;
            for (MembershipListener l : listeners) {
                l.onViewChanged(old, newView);
            }
        }

        @Override
        public void start(List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
            listeners.add(listener);
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }

    /** Minimal stub engine for testing. */
    private static final class StubEngine implements Engine {

        private final java.util.concurrent.ConcurrentHashMap<String, TableMetadata> tables = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Table createTable(String name, JlsmSchema schema) throws IOException {
            final TableMetadata meta = new TableMetadata(name, schema, NOW,
                    TableMetadata.TableState.READY);
            tables.put(name, meta);
            return jlsm.engine.cluster.internal.TestTableStubs.forMetadata(meta);
        }

        @Override
        public Table getTable(String name) throws IOException {
            final TableMetadata meta = tables.get(name);
            if (meta == null) {
                throw new IOException("Table not found: " + name);
            }
            return jlsm.engine.cluster.internal.TestTableStubs.forMetadata(meta);
        }

        @Override
        public void dropTable(String name) {
            tables.remove(name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return List.copyOf(tables.values());
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            return tables.get(name);
        }

        @Override
        public EngineMetrics metrics() {
            return new EngineMetrics(tables.size(), 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
        }
    }

    // R8g migration: StubTable previously declared `implements Table` — replaced with the
    // shared {@code TestTableStubs.forMetadata(...)} factory that returns a real
    // {@link jlsm.engine.cluster.internal.CatalogClusteredTable} subtype with no-op CRUD.
}

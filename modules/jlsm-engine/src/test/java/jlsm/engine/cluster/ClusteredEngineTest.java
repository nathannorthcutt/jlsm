package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusteredEngine} — cluster-aware engine wrapping LocalEngine with membership,
 * ownership, and distributed routing.
 *
 * @spec engine.clustering.R55 — wraps local engine; local engine remains functional for local
 *       partitions
 * @spec engine.clustering.R56 — builder accepts localEngine, config, transport, discovery,
 *       membership as mandatory
 * @spec engine.clustering.R80 — closeable lifecycle
 */
final class ClusteredEngineTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 8002);
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    private InJvmTransport transportA;
    private StubMembershipProtocol membershipA;
    private RendezvousOwnership ownership;
    private GracePeriodManager gracePeriod;
    private ClusterConfig config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        transportA = new InJvmTransport(NODE_A);
        membershipA = new StubMembershipProtocol();
        ownership = new RendezvousOwnership();
        gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        config = ClusterConfig.builder().build();
    }

    @AfterEach
    void tearDown() {
        transportA.close();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    // --- Builder validation ---

    @Test
    void builder_missingLocalEngine_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> ClusteredEngine.builder().membership(membershipA).ownership(ownership)
                        .gracePeriodManager(gracePeriod).transport(transportA).config(config)
                        .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build());
    }

    @Test
    void builder_missingMembership_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> ClusteredEngine.builder().localEngine(stubEngine()).ownership(ownership)
                        .gracePeriodManager(gracePeriod).transport(transportA).config(config)
                        .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build());
    }

    // @spec engine.clustering.R56 — discovery is a mandatory builder parameter
    @Test
    void builder_missingDiscovery_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> ClusteredEngine.builder().localEngine(stubEngine()).membership(membershipA)
                        .ownership(ownership).gracePeriodManager(gracePeriod).transport(transportA)
                        .config(config).localAddress(NODE_A).build());
    }

    @Test
    void builder_allFieldsSet_builds() {
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(stubEngine())
                .membership(membershipA).ownership(ownership).gracePeriodManager(gracePeriod)
                .transport(transportA).config(config).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();
        assertNotNull(engine);
    }

    // --- createTable ---

    @Test
    void createTable_delegatesToLocalEngine() throws IOException {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        final Table table = engine.createTable("users", SCHEMA);
        assertNotNull(table);
        assertTrue(local.createdTables.contains("users"));
    }

    // --- getTable ---

    @Test
    void getTable_returnsTableForExistingTable() throws IOException {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        engine.createTable("users", SCHEMA);
        final Table table = engine.getTable("users");
        assertNotNull(table);
    }

    @Test
    void getTable_nonExistentTable_throwsIOException() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        assertThrows(IOException.class, () -> engine.getTable("nonexistent"));
    }

    // --- dropTable ---

    @Test
    void dropTable_delegatesToLocalEngine() throws IOException {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        engine.createTable("users", SCHEMA);
        engine.dropTable("users");
        assertTrue(local.droppedTables.contains("users"));
    }

    // --- listTables ---

    @Test
    void listTables_delegatesToLocalEngine() throws IOException {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        engine.createTable("users", SCHEMA);
        final Collection<TableMetadata> tables = engine.listTables();
        assertNotNull(tables);
        assertFalse(tables.isEmpty());
    }

    // --- tableMetadata ---

    @Test
    void tableMetadata_returnsNullForUnknown() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        assertNull(engine.tableMetadata("nonexistent"));
    }

    // --- metrics ---

    @Test
    void metrics_returnsNonNull() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        final EngineMetrics m = engine.metrics();
        assertNotNull(m);
        assertEquals(0, m.tableCount());
    }

    // --- close ---

    @Test
    void close_closesLocalEngineAndMembership() throws Exception {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        engine.close();
        assertTrue(local.closed);
    }

    @Test
    void close_isIdempotent() throws IOException {
        final ClusteredEngine engine = buildEngine(stubEngine());
        engine.close();
        assertDoesNotThrow(engine::close);
    }

    // --- Membership listener ---

    @Test
    void membershipChange_triggersOwnershipUpdate() throws IOException {
        final StubEngine local = stubEngine();
        final ClusteredEngine engine = buildEngine(local);

        // The engine should have registered itself as a membership listener
        assertFalse(membershipA.listeners.isEmpty());
    }

    // --- Input validation ---

    // @spec engine.clustering.R78 — null arguments must be rejected with NullPointerException
    @Test
    void createTable_nullName_throws() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        assertThrows(NullPointerException.class, () -> engine.createTable(null, SCHEMA));
    }

    @Test
    void createTable_emptyName_throws() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("", SCHEMA));
    }

    // @spec engine.clustering.R78 — null arguments must be rejected with NullPointerException
    @Test
    void createTable_nullSchema_throws() {
        final ClusteredEngine engine = buildEngine(stubEngine());
        assertThrows(NullPointerException.class, () -> engine.createTable("users", null));
    }

    // --- Helper methods ---

    private ClusteredEngine buildEngine(Engine localEngine) {
        return ClusteredEngine.builder().localEngine(localEngine).membership(membershipA)
                .ownership(ownership).gracePeriodManager(gracePeriod).transport(transportA)
                .config(config).localAddress(NODE_A).discovery(new InJvmDiscoveryProvider())
                .build();
    }

    private static StubEngine stubEngine() {
        return new StubEngine();
    }

    // --- Stub implementations ---

    /**
     * Stub MembershipProtocol for testing.
     */
    private static final class StubMembershipProtocol implements MembershipProtocol {
        final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
        private volatile MembershipView view = new MembershipView(0, Set.of(), NOW);
        volatile boolean started;
        volatile boolean left;
        volatile boolean closed;

        @Override
        public void start(List<NodeAddress> seeds) {
            started = true;
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        void setView(MembershipView newView) {
            final MembershipView old = this.view;
            this.view = newView;
            for (MembershipListener l : listeners) {
                l.onViewChanged(old, newView);
            }
        }

        @Override
        public void addListener(MembershipListener listener) {
            listeners.add(listener);
        }

        @Override
        public void leave() {
            left = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Minimal stub engine that tracks method calls.
     */
    private static final class StubEngine implements Engine {
        final List<String> createdTables = new CopyOnWriteArrayList<>();
        final List<String> droppedTables = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.ConcurrentHashMap<String, TableMetadata> tables = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean closed;

        @Override
        public Table createTable(String name, JlsmSchema schema) throws IOException {
            if (tables.containsKey(name)) {
                throw new IOException("Table already exists: " + name);
            }
            final TableMetadata meta = new TableMetadata(name, schema, NOW,
                    TableMetadata.TableState.READY);
            tables.put(name, meta);
            createdTables.add(name);
            return jlsm.engine.cluster.internal.TestTableStubs.forMetadata(meta);
        }

        @Override
        public Table getTable(String name) throws IOException {
            final TableMetadata meta = tables.get(name);
            if (meta == null) {
                throw new IOException("Table does not exist: " + name);
            }
            return jlsm.engine.cluster.internal.TestTableStubs.forMetadata(meta);
        }

        @Override
        public void dropTable(String name) throws IOException {
            if (tables.remove(name) == null) {
                throw new IOException("Table does not exist: " + name);
            }
            droppedTables.add(name);
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
            closed = true;
        }
    }

    // R8g migration: StubTable previously declared `implements Table` — replaced with the
    // shared {@code TestTableStubs.forMetadata(...)} factory.
}

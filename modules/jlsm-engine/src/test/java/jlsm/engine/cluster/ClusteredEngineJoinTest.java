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
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusteredEngine#join(List)} and discovery registration symmetry on close —
 * covers F04.R56, R57, R58, R78, R79, R80.
 *
 * @spec engine.clustering.R56 — builder accepts mandatory parameters
 * @spec engine.clustering.R57 — join orchestration; rollback on failure
 * @spec engine.clustering.R58 — leave + deregister + stop transport on shutdown
 * @spec engine.clustering.R78 — null-argument rejection on mandatory parameters
 * @spec engine.clustering.R79 — builder rejects null mandatory parameters at build time
 * @spec engine.clustering.R80 — closeable lifecycle accumulates errors
 * @spec engine.clustering.R97 — close deregisters from discovery and transport handlers
 * @spec engine.clustering.R103 — unwind registrations if later step throws
 * @spec engine.clustering.R104 — rollback exceptions attached as suppressed on original cause
 */
final class ClusteredEngineJoinTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 8001);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 8002);
    private static final Instant NOW = Instant.parse("2026-04-19T00:00:00Z");

    private InJvmTransport transport;
    private RendezvousOwnership ownership;
    private GracePeriodManager gracePeriod;
    private ClusterConfig config;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        transport = new InJvmTransport(NODE_A);
        ownership = new RendezvousOwnership();
        gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        config = ClusterConfig.builder().build();
    }

    @AfterEach
    void tearDown() {
        transport.close();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    // --- join: ordering ---

    @Test
    @Timeout(10)
    void join_registersWithDiscoveryBeforeMembershipStart() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        engine.join(List.of(NODE_B));

        assertEquals(1, discovery.registerCalls.size(),
                "discovery.register must be called exactly once during join");
        assertEquals(NODE_A, discovery.registerCalls.getFirst().address);
        assertEquals(1, membership.startCalls.size(),
                "membership.start must be called exactly once during join");
        assertTrue(
                discovery.registerCalls.getFirst().timestampNanos <= membership.startCalls
                        .getFirst().timestampNanos,
                "discovery.register must be called BEFORE membership.start");
    }

    // --- join: rollback on membership start failure ---

    @Test
    @Timeout(10)
    void join_rollsBackDiscoveryOnMembershipStartFailure() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final IOException startFailure = new IOException("membership start failed");
        final ThrowingMembershipProtocol membership = new ThrowingMembershipProtocol(startFailure);
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        final IOException caught = assertThrows(IOException.class,
                () -> engine.join(List.of(NODE_B)));
        assertSame(startFailure, caught,
                "the original membership.start exception must be propagated");

        assertEquals(1, discovery.registerCalls.size(),
                "discovery.register must have been called before the failing start");
        assertEquals(1, discovery.deregisterCalls.size(),
                "discovery.deregister must be called exactly once after membership.start fails");
        assertEquals(NODE_A, discovery.deregisterCalls.getFirst().address);
    }

    // --- join: deregister throws, exception suppressed ---

    @Test
    @Timeout(10)
    void join_deregisterExceptionSuppressedIntoOriginal() throws Exception {
        final RuntimeException deregisterError = new RuntimeException("deregister blew up");
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        discovery.throwOnDeregister = deregisterError;
        final IOException startFailure = new IOException("start failure");
        final ThrowingMembershipProtocol membership = new ThrowingMembershipProtocol(startFailure);
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        final IOException caught = assertThrows(IOException.class,
                () -> engine.join(List.of(NODE_B)));
        assertSame(startFailure, caught,
                "original start failure must be propagated even when deregister throws");
        assertEquals("start failure", caught.getMessage());
        final Throwable[] suppressed = caught.getSuppressed();
        assertEquals(1, suppressed.length, "deregister failure must be recorded as suppressed");
        assertSame(deregisterError, suppressed[0],
                "the deregister throwable must be attached via addSuppressed");
    }

    // --- join: input validation ---

    @Test
    @Timeout(10)
    void join_rejectsNullSeeds() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        assertThrows(NullPointerException.class, () -> engine.join(null));
        assertTrue(discovery.registerCalls.isEmpty(),
                "discovery.register must not be called when seeds is null");
        assertTrue(membership.startCalls.isEmpty(),
                "membership.start must not be called when seeds is null");
    }

    @Test
    @Timeout(10)
    void join_rejectsSeedsWithNullElement() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        final List<NodeAddress> seedsWithNull = new ArrayList<>();
        seedsWithNull.add(NODE_B);
        seedsWithNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> engine.join(seedsWithNull));
        assertTrue(discovery.registerCalls.isEmpty(),
                "discovery.register must not be called when seeds contains null");
        assertTrue(membership.startCalls.isEmpty(),
                "membership.start must not be called when seeds contains null");
    }

    // --- join: engine already closed ---

    @Test
    @Timeout(10)
    void join_onClosedEngine_throwsIOException() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final ClusteredEngine engine = newEngine(new StubEngine(), membership, discovery);

        engine.close();

        assertThrows(IOException.class, () -> engine.join(List.of(NODE_B)));
    }

    // --- close ordering: discovery.deregister after membership.leave/close, before
    // localEngine.close ---

    @Test
    @Timeout(10)
    void close_deregistersFromDiscovery_afterMembershipLeave_andBeforeLocalEngineClose()
            throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final RecordingEngine localEngine = new RecordingEngine();
        final ClusteredEngine engine = newEngine(localEngine, membership, discovery);

        engine.close();

        assertEquals(1, membership.leaveCalls.size(), "membership.leave must be called once");
        assertEquals(1, membership.closeCalls.size(), "membership.close must be called once");
        assertEquals(1, discovery.deregisterCalls.size(),
                "discovery.deregister must be called exactly once during close");
        assertEquals(1, localEngine.closeCalls.size(), "localEngine.close must be called once");

        final long leaveAt = membership.leaveCalls.getFirst().timestampNanos;
        final long deregisterAt = discovery.deregisterCalls.getFirst().timestampNanos;
        final long localCloseAt = localEngine.closeCalls.getFirst().timestampNanos;

        assertTrue(leaveAt <= deregisterAt,
                "discovery.deregister must happen AFTER membership.leave");
        assertTrue(deregisterAt <= localCloseAt,
                "discovery.deregister must happen BEFORE localEngine.close");
    }

    // --- close: deregister exception accumulated into primary ---

    @Test
    @Timeout(10)
    void close_deregisterExceptionAccumulatedIntoPrimary() throws Exception {
        final RecordingDiscoveryProvider discovery = new RecordingDiscoveryProvider();
        discovery.throwOnDeregister = new RuntimeException("deregister failed");
        final RecordingMembershipProtocol membership = new RecordingMembershipProtocol();
        final RecordingEngine localEngine = new RecordingEngine();
        final ClusteredEngine engine = newEngine(localEngine, membership, discovery);

        final IOException caught = assertThrows(IOException.class, engine::close);

        // Primary exception is the first collected error; deregister exception must be
        // present among getSuppressed() OR be the primary itself.
        boolean foundDeregister = false;
        if (caught.getCause() != null
                && "deregister failed".equals(caught.getCause().getMessage())) {
            foundDeregister = true;
        }
        for (Throwable t : caught.getSuppressed()) {
            if ("deregister failed".equals(t.getMessage()) || (t.getCause() != null
                    && "deregister failed".equals(t.getCause().getMessage()))) {
                foundDeregister = true;
            }
        }
        if ("deregister failed".equals(caught.getMessage()) || (caught.getCause() != null
                && "deregister failed".equals(caught.getCause().getMessage()))) {
            foundDeregister = true;
        }
        assertTrue(foundDeregister,
                "the deregister failure must be recorded in the thrown IOException chain");

        // All remaining resources should still have been closed.
        assertEquals(1, localEngine.closeCalls.size(),
                "localEngine.close must still run when discovery.deregister throws");
    }

    // --- builder null guards ---

    @Test
    @Timeout(10)
    void builder_missingDiscovery_buildThrowsNPE() {
        assertThrows(NullPointerException.class,
                () -> ClusteredEngine.builder().localEngine(new StubEngine())
                        .membership(new RecordingMembershipProtocol()).ownership(ownership)
                        .gracePeriodManager(gracePeriod).transport(transport).config(config)
                        .localAddress(NODE_A).build());
    }

    @Test
    @Timeout(10)
    void builder_nullDiscoverySetter_throwsNPE() {
        assertThrows(NullPointerException.class, () -> ClusteredEngine.builder().discovery(null));
    }

    // --- Helpers ---

    private ClusteredEngine newEngine(Engine localEngine, MembershipProtocol membership,
            DiscoveryProvider discovery) {
        return ClusteredEngine.builder().localEngine(localEngine).membership(membership)
                .ownership(ownership).gracePeriodManager(gracePeriod).transport(transport)
                .config(config).localAddress(NODE_A).discovery(discovery).build();
    }

    // --- Test doubles ---

    /**
     * DiscoveryProvider test double that records each register/deregister call (with nanoTime
     * ordering) and optionally throws on deregister.
     */
    private static final class RecordingDiscoveryProvider implements DiscoveryProvider {
        record Call(NodeAddress address, long timestampNanos) {
        }

        final List<Call> registerCalls = new CopyOnWriteArrayList<>();
        final List<Call> deregisterCalls = new CopyOnWriteArrayList<>();
        volatile RuntimeException throwOnDeregister;

        @Override
        public Set<NodeAddress> discoverSeeds() {
            return Set.of();
        }

        @Override
        public void register(NodeAddress self) {
            registerCalls.add(new Call(self, System.nanoTime()));
        }

        @Override
        public void deregister(NodeAddress self) {
            deregisterCalls.add(new Call(self, System.nanoTime()));
            final RuntimeException t = throwOnDeregister;
            if (t != null) {
                throw t;
            }
        }
    }

    /**
     * MembershipProtocol test double that records start/leave/close calls with nanoTime.
     */
    private static final class RecordingMembershipProtocol implements MembershipProtocol {
        record Call(long timestampNanos) {
        }

        final List<Call> startCalls = new CopyOnWriteArrayList<>();
        final List<Call> leaveCalls = new CopyOnWriteArrayList<>();
        final List<Call> closeCalls = new CopyOnWriteArrayList<>();
        private final MembershipView view = new MembershipView(0, Set.of(), NOW);

        @Override
        public void start(List<NodeAddress> seeds) {
            startCalls.add(new Call(System.nanoTime()));
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
        }

        @Override
        public void leave() {
            leaveCalls.add(new Call(System.nanoTime()));
        }

        @Override
        public void close() {
            closeCalls.add(new Call(System.nanoTime()));
        }
    }

    /**
     * MembershipProtocol test double whose start() throws a preconfigured IOException.
     */
    private static final class ThrowingMembershipProtocol implements MembershipProtocol {
        private final IOException startFailure;
        private final MembershipView view = new MembershipView(0, Set.of(), NOW);

        ThrowingMembershipProtocol(IOException startFailure) {
            this.startFailure = startFailure;
        }

        @Override
        public void start(List<NodeAddress> seeds) throws IOException {
            throw startFailure;
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * Stub engine — no close tracking (used where close ordering is not under test).
     */
    private static final class StubEngine implements Engine {
        @Override
        public Table createTable(String name, JlsmSchema schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table getTable(String name) throws IOException {
            throw new IOException("no such table: " + name);
        }

        @Override
        public void dropTable(String name) throws IOException {
            throw new IOException("no such table: " + name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return Collections.emptyList();
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
        }
    }

    /**
     * Engine test double that records close() invocations with nanoTime.
     */
    private static final class RecordingEngine implements Engine {
        record Call(long timestampNanos) {
        }

        final List<Call> closeCalls = new CopyOnWriteArrayList<>();
        private final AtomicLong nothing = new AtomicLong();

        @Override
        public Table createTable(String name, JlsmSchema schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table getTable(String name) throws IOException {
            throw new IOException("no such table: " + name);
        }

        @Override
        public void dropTable(String name) throws IOException {
            throw new IOException("no such table: " + name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return Collections.emptyList();
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            return null;
        }

        @Override
        public EngineMetrics metrics() {
            nothing.incrementAndGet();
            return new EngineMetrics(0, 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
            closeCalls.add(new Call(System.nanoTime()));
        }
    }
}

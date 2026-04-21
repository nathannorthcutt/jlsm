package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link ClusteredTable} local short-circuit routing (F04.R60).
 *
 * <p>
 * Operations targeting locally-owned partitions must invoke the local {@link Engine}'s table
 * directly and must NOT touch the cluster transport.
 *
 * @spec engine.clustering.R60 — locally-owned partitions execute on local engine without transport
 *       round-trip
 * @spec engine.clustering.R59 — partition-aware proxy routes based on ownership
 */
final class ClusteredTableLocalShortCircuitTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9101);
    private static final NodeAddress REMOTE = new NodeAddress("remote", "localhost", 9102);
    private static final Instant NOW = Instant.parse("2026-04-19T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();
    private static final TableMetadata TABLE_META = new TableMetadata("users", SCHEMA, NOW,
            TableMetadata.TableState.READY);

    private CountingTransport localTransport;
    private InJvmTransport remoteTransport;
    private StubMembershipProtocol membership;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new CountingTransport(LOCAL);
        membership = new StubMembershipProtocol();
    }

    @AfterEach
    void tearDown() {
        localTransport.close();
        if (remoteTransport != null) {
            remoteTransport.close();
        }
        InJvmTransport.clearRegistry();
    }

    // --- Single-node (LOCAL only) tests ---

    @Test
    @Timeout(10)
    void create_localOwner_bypassesTransport() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k1", "value", "v1");
            table.create("k1", doc);

            assertEquals(0, localTransport.sendCount.get(),
                    "local-owner create must not send any messages");
            assertEquals(0, localTransport.requestCount.get(),
                    "local-owner create must not issue request() calls");
            assertEquals(1, recording.createCalls.size(),
                    "local engine's table must receive the create call");
            assertEquals("k1", recording.createCalls.getFirst().key);
        } finally {
            table.close();
        }
    }

    @Test
    @Timeout(10)
    void get_localOwner_bypassesTransport() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        final JlsmDocument stored = JlsmDocument.of(SCHEMA, "id", "k1", "value", "v1");
        recording.storedDocs.put("k1", stored);
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            final Optional<JlsmDocument> got = table.get("k1");
            assertTrue(got.isPresent(), "get must return the locally-stored document");
            assertEquals(0, localTransport.requestCount.get(),
                    "local-owner get must not issue request() calls");
            assertEquals(1, recording.getCalls.size());
        } finally {
            table.close();
        }
    }

    @Test
    @Timeout(10)
    void update_localOwner_bypassesTransport() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k1", "value", "updated");
            table.update("k1", doc, UpdateMode.REPLACE);
            assertEquals(0, localTransport.requestCount.get(),
                    "local-owner update must not issue request() calls");
            assertEquals(1, recording.updateCalls.size());
            assertEquals(UpdateMode.REPLACE, recording.updateCalls.getFirst().mode);
        } finally {
            table.close();
        }
    }

    @Test
    @Timeout(10)
    void delete_localOwner_bypassesTransport() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            table.delete("k1");
            assertEquals(0, localTransport.requestCount.get(),
                    "local-owner delete must not issue request() calls");
            assertEquals(1, recording.deleteCalls.size());
        } finally {
            table.close();
        }
    }

    @Test
    @Timeout(10)
    void scan_localOwnerOnly_usesLocalEngineForItsPartition() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        recording.storedDocs.put("a-key", JlsmDocument.of(SCHEMA, "id", "a-key", "value", "a"));
        recording.storedDocs.put("b-key", JlsmDocument.of(SCHEMA, "id", "b-key", "value", "b"));
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            final Iterator<TableEntry<String>> it = table.scan("a", "z");
            assertNotNull(it);
            int n = 0;
            while (it.hasNext()) {
                it.next();
                n++;
            }
            assertEquals(2, n, "scan must yield all locally-stored entries in range");
            assertEquals(0, localTransport.requestCount.get(),
                    "local-owner scan must not issue request() calls");
            assertEquals(1, recording.scanCalls.size());

            final PartialResultMetadata meta = table.lastPartialResultMetadata();
            assertNotNull(meta);
            assertEquals(1, meta.totalPartitionsQueried());
            assertEquals(1, meta.respondingPartitions());
            assertTrue(meta.isComplete());
        } finally {
            table.close();
        }
    }

    // --- Mixed-owner tests (local + remote) ---

    @Test
    @Timeout(10)
    void scan_mixedOwners_localIsShortCircuited_remoteUsesTransport() throws IOException {
        // Set up a real remote InJvmTransport that responds to QUERY_REQUEST with an empty range.
        remoteTransport = new InJvmTransport(REMOTE);
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                                        msg.sequenceNumber(), new byte[0])));

        final RecordingTable recording = new RecordingTable(TABLE_META);
        recording.storedDocs.put("alpha", JlsmDocument.of(SCHEMA, "id", "alpha", "value", "a"));
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = new MembershipView(1, Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                new Member(REMOTE, MemberState.ALIVE, 0)), NOW);
        final ClusteredTable table = newTable(localEngine);

        try {
            final Iterator<TableEntry<String>> it = table.scan("a", "z");
            assertNotNull(it);

            // Remote was contacted via transport (exactly one request call per remote node).
            assertEquals(1, localTransport.requestCount.get(),
                    "remote partition must be reached via transport.request (exactly once)");
            // Local was queried via the local engine (exactly once).
            assertEquals(1, recording.scanCalls.size(),
                    "local engine's table must receive one scan call in mixed-owner scan");

            final PartialResultMetadata meta = table.lastPartialResultMetadata();
            assertNotNull(meta);
            assertEquals(2, meta.totalPartitionsQueried());
            assertEquals(2, meta.respondingPartitions());
            assertTrue(meta.isComplete());
        } finally {
            table.close();
        }
    }

    @Test
    @Timeout(10)
    void scan_localOwnerIOException_countsUnavailable_withLocalAddressNodeId() throws IOException {
        final RecordingTable recording = new RecordingTable(TABLE_META);
        recording.scanThrows = new IOException("local scan failed");
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        membership.view = singleNodeView(LOCAL);
        final ClusteredTable table = newTable(localEngine);

        try {
            table.scan("a", "z");
            final PartialResultMetadata meta = table.lastPartialResultMetadata();
            assertNotNull(meta);
            assertEquals(1, meta.totalPartitionsQueried());
            assertEquals(0, meta.respondingPartitions());
            assertTrue(meta.unavailablePartitions().contains(LOCAL.nodeId()),
                    "unavailable set must include the local node id when the local engine throws");
            assertFalse(meta.isComplete());
        } finally {
            table.close();
        }
    }

    // --- Remote-owner regression guard ---

    @Test
    @Timeout(10)
    void create_remoteOwner_stillUsesTransport() throws IOException {
        remoteTransport = new InJvmTransport(REMOTE);
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                                        msg.sequenceNumber(), new byte[0])));

        final RecordingTable recording = new RecordingTable(TABLE_META);
        final RecordingEngine localEngine = new RecordingEngine(Map.of("users", recording));
        // The view contains ONLY the remote node, so rendezvous resolves owner == REMOTE.
        membership.view = singleNodeView(REMOTE);
        final ClusteredTable table = newTable(localEngine);

        try {
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k1", "value", "v1");
            table.create("k1", doc);

            assertEquals(1, localTransport.requestCount.get(),
                    "remote-owner create must route through transport.request");
            assertTrue(recording.createCalls.isEmpty(),
                    "remote-owner create must not touch the local engine's table");
        } finally {
            table.close();
        }
    }

    // --- Backward-compat (5-arg constructor) test ---

    @Test
    @Timeout(10)
    void null_localEngine_fallsBackToRemoteForAllOwners() throws IOException {
        // Build a ClusteredTable via the existing 5-arg constructor — no local engine supplied.
        // Even though LOCAL owns the partition (single-node view), the table must still route via
        // transport since it has no local short-circuit path.
        membership.view = singleNodeView(LOCAL);

        final ClusteredTable table = new ClusteredTable(TABLE_META, localTransport, membership,
                LOCAL, new RendezvousOwnership());

        // Register a handler on the local transport so the self-addressed request completes.
        localTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, LOCAL,
                                        msg.sequenceNumber(), new byte[0])));

        try {
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k1", "value", "v1");
            table.create("k1", doc);
            assertEquals(1, localTransport.requestCount.get(),
                    "with null localEngine, create must go through transport.request even when "
                            + "local node owns the partition");
        } finally {
            table.close();
        }
    }

    // --- Helpers ---

    private ClusteredTable newTable(Engine localEngine) {
        return new ClusteredTable(TABLE_META, localTransport, membership, LOCAL,
                new RendezvousOwnership(), localEngine);
    }

    private static MembershipView singleNodeView(NodeAddress node) {
        return new MembershipView(1, Set.of(new Member(node, MemberState.ALIVE, 0)), NOW);
    }

    // --- Test doubles ---

    /**
     * ClusterTransport wrapper that counts send() and request() calls and delegates to a real
     * {@link InJvmTransport}. Uses composition because {@link InJvmTransport} is final.
     */
    private static final class CountingTransport implements ClusterTransport {
        final AtomicInteger sendCount = new AtomicInteger();
        final AtomicInteger requestCount = new AtomicInteger();
        private final InJvmTransport delegate;

        CountingTransport(NodeAddress localAddress) {
            this.delegate = new InJvmTransport(localAddress);
        }

        @Override
        public void send(NodeAddress target, Message msg) throws IOException {
            sendCount.incrementAndGet();
            delegate.send(target, msg);
        }

        @Override
        public CompletableFuture<Message> request(NodeAddress target, Message msg) {
            requestCount.incrementAndGet();
            return delegate.request(target, msg);
        }

        @Override
        public void registerHandler(MessageType type, MessageHandler handler) {
            delegate.registerHandler(type, handler);
        }

        @Override
        public void deregisterHandler(MessageType type) {
            delegate.deregisterHandler(type);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Stub membership protocol that exposes a single view for the duration of a test.
     */
    private static final class StubMembershipProtocol implements MembershipProtocol {
        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);

        @Override
        public void start(List<NodeAddress> seeds) {
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
     * Engine test double: routes {@code getTable(name)} to a pre-seeded map of named tables.
     */
    private static final class RecordingEngine implements Engine {
        private final Map<String, Table> tables;

        RecordingEngine(Map<String, Table> tables) {
            this.tables = new ConcurrentHashMap<>(tables);
        }

        @Override
        public Table createTable(String name, JlsmSchema schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table getTable(String name) throws IOException {
            final Table t = tables.get(name);
            if (t == null) {
                throw new IOException("no such table: " + name);
            }
            return t;
        }

        @Override
        public void dropTable(String name) {
            tables.remove(name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            final List<TableMetadata> list = new ArrayList<>();
            for (Table t : tables.values()) {
                list.add(t.metadata());
            }
            return list;
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            final Table t = tables.get(name);
            return t == null ? null : t.metadata();
        }

        @Override
        public EngineMetrics metrics() {
            return new EngineMetrics(tables.size(), 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
        }
    }

    /**
     * Table test double that records every CRUD/scan call and optionally throws from scan.
     */
    private static final class RecordingTable implements Table {
        record CreateCall(String key, JlsmDocument doc) {
        }

        record GetCall(String key) {
        }

        record UpdateCall(String key, JlsmDocument doc, UpdateMode mode) {
        }

        record DeleteCall(String key) {
        }

        record ScanCall(String fromKey, String toKey) {
        }

        final TableMetadata metadata;
        final Map<String, JlsmDocument> storedDocs = new ConcurrentHashMap<>();
        final List<CreateCall> createCalls = new CopyOnWriteArrayList<>();
        final List<GetCall> getCalls = new CopyOnWriteArrayList<>();
        final List<UpdateCall> updateCalls = new CopyOnWriteArrayList<>();
        final List<DeleteCall> deleteCalls = new CopyOnWriteArrayList<>();
        final List<ScanCall> scanCalls = new CopyOnWriteArrayList<>();
        volatile IOException scanThrows;

        RecordingTable(TableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
            createCalls.add(new CreateCall(key, doc));
            storedDocs.put(key, doc);
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            getCalls.add(new GetCall(key));
            return Optional.ofNullable(storedDocs.get(key));
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
            updateCalls.add(new UpdateCall(key, doc, mode));
            storedDocs.put(key, doc);
        }

        @Override
        public void delete(String key) {
            deleteCalls.add(new DeleteCall(key));
            storedDocs.remove(key);
        }

        @Override
        public void insert(JlsmDocument doc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
            scanCalls.add(new ScanCall(fromKey, toKey));
            if (scanThrows != null) {
                throw scanThrows;
            }
            final List<TableEntry<String>> matches = new ArrayList<>();
            for (Map.Entry<String, JlsmDocument> e : storedDocs.entrySet()) {
                if (e.getKey().compareTo(fromKey) >= 0 && e.getKey().compareTo(toKey) < 0) {
                    matches.add(new TableEntry<>(e.getKey(), e.getValue()));
                }
            }
            matches.sort((a, b) -> a.key().compareTo(b.key()));
            return Collections.unmodifiableList(matches).iterator();
        }

        @Override
        public TableMetadata metadata() {
            return metadata;
        }

        @Override
        public void close() {
        }
    }
}

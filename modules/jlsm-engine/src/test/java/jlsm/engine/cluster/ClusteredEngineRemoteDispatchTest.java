package jlsm.engine.cluster;

import jlsm.engine.Table;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.QueryRequestHandler;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.engine.internal.LocalEngine;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jlsm.table.UpdateMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the {@link ClusteredEngine} QUERY_REQUEST registration contract and the
 * request/response plumbing between {@link RemotePartitionClient} and {@link QueryRequestHandler}.
 *
 * <p>
 * Delivers: F04.R68.
 */
final class ClusteredEngineRemoteDispatchTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 9201);
    private static final NodeAddress NODE_B = new NodeAddress("node-b", "localhost", 9202);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    @TempDir
    Path tempDirA;

    @TempDir
    Path tempDirB;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        RemotePartitionClient.resetOpenInstanceCounter();
    }

    @AfterEach
    void tearDown() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    // --- build registers handler (verified indirectly via a successful remote call) ---

    @Test
    void build_registersQueryRequestHandlerOnTransport() throws Exception {
        final LocalEngine local = LocalEngine.builder().rootDirectory(tempDirA).build();
        local.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final ClusteredEngine engine;
        try {
            engine = ClusteredEngine.builder().localEngine(local)
                    .membership(new NoopMembershipProtocol()).ownership(new RendezvousOwnership())
                    .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                    .transport(transportA).config(ClusterConfig.builder().build())
                    .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build();
        } catch (RuntimeException ex) {
            transportA.close();
            transportB.close();
            local.close();
            throw ex;
        }

        try {
            // Send a QUERY_REQUEST to the registered engine transport from transportB.
            // Use a get-opcode payload against the "users" table so the handler succeeds.
            final byte[] payload = jlsm.engine.cluster.internal.QueryRequestPayload
                    .encodeGet("users", 0L, "missing-key");
            final Message request = new Message(MessageType.QUERY_REQUEST, NODE_B, 1L, payload);
            final CompletableFuture<Message> future = transportB.request(NODE_A, request);
            final Message response = future.get(5, TimeUnit.SECONDS);
            assertEquals(MessageType.QUERY_RESPONSE, response.type());
        } finally {
            engine.close();
            transportB.close();
        }
    }

    @Test
    void close_deregistersQueryRequestHandler() throws Exception {
        final LocalEngine local = LocalEngine.builder().rootDirectory(tempDirA).build();
        local.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(local)
                .membership(new NoopMembershipProtocol()).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                .transport(transportA).config(ClusterConfig.builder().build()).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        // Close the engine — this should deregister the QUERY_REQUEST handler.
        engine.close();

        try {
            final byte[] payload = jlsm.engine.cluster.internal.QueryRequestPayload
                    .encodeGet("users", 0L, "k");
            final Message request = new Message(MessageType.QUERY_REQUEST, NODE_B, 1L, payload);
            final CompletableFuture<Message> future = transportB.request(NODE_A, request);
            final ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, ex.getCause());
        } finally {
            transportB.close();
        }
    }

    // --- End-to-end RemotePartitionClient + QueryRequestHandler plumbing ---

    @Test
    void endToEnd_remoteClientGet_routesToLocalTable() throws Exception {
        // LocalEngine B owns the table data; QueryRequestHandler bound to B's engine is
        // registered on transportB. Client on transportA hits transportB via the transport.
        final LocalEngine engineB = LocalEngine.builder().rootDirectory(tempDirB).build();
        engineB.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final QueryRequestHandler handlerB = new QueryRequestHandler(engineB, NODE_B);
        transportB.registerHandler(MessageType.QUERY_REQUEST, handlerB);

        try {
            // Pre-populate the remote table directly.
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "hello");
            engineB.getTable("users").create("key1", doc);

            final PartitionDescriptor desc = new PartitionDescriptor(0L,
                    MemorySegment.ofArray(new byte[0]),
                    MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), NODE_B.nodeId(), 0L);

            try (RemotePartitionClient client = new RemotePartitionClient(desc, NODE_B, transportA,
                    NODE_A, SCHEMA, "users")) {
                final Optional<JlsmDocument> result = client.get("key1");
                assertTrue(result.isPresent(),
                        "Remote client must retrieve document routed via QueryRequestHandler");
                assertEquals("user-1", result.get().getString("id"));
                assertEquals("hello", result.get().getString("value"));
            }
        } finally {
            transportA.close();
            transportB.close();
            engineB.close();
        }
    }

    @Test
    void endToEnd_remoteClientScan_returnsEntries() throws Exception {
        final LocalEngine engineB = LocalEngine.builder().rootDirectory(tempDirB).build();
        engineB.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final QueryRequestHandler handlerB = new QueryRequestHandler(engineB, NODE_B);
        transportB.registerHandler(MessageType.QUERY_REQUEST, handlerB);

        try {
            final Table users = engineB.getTable("users");
            users.create("apple", JlsmDocument.of(SCHEMA, "id", "a", "value", "1"));
            users.create("banana", JlsmDocument.of(SCHEMA, "id", "b", "value", "2"));
            users.create("cherry", JlsmDocument.of(SCHEMA, "id", "c", "value", "3"));

            final PartitionDescriptor desc = new PartitionDescriptor(0L,
                    MemorySegment.ofArray(new byte[0]),
                    MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), NODE_B.nodeId(), 0L);

            try (RemotePartitionClient client = new RemotePartitionClient(desc, NODE_B, transportA,
                    NODE_A, SCHEMA, "users")) {
                final Iterator<TableEntry<String>> iter = client.getRange("a", "z");
                int count = 0;
                while (iter.hasNext()) {
                    iter.next();
                    count++;
                }
                assertEquals(3, count);
            }
        } finally {
            transportA.close();
            transportB.close();
            engineB.close();
        }
    }

    // --- Adversarial (hardening-cycle 1) ---

    // Finding: H-RL-8
    // Bug: ClusteredEngine.close() may invoke transport.close() before deregisterHandler,
    // and the closed transport would reject deregistration with IllegalStateException,
    // breaking the multi-close chain.
    // Correct behavior: deregisterHandler runs before transport.close; post-close QUERY_REQUEST
    // fails fast because the handler is no longer registered (or transport is closed).
    // Fix location: ClusteredEngine.close ordering
    // Regression watch: successful pre-close QUERY_REQUEST still returns QUERY_RESPONSE.
    @Test
    void close_deregistersHandlerBeforeTransportClose() throws Exception {
        final LocalEngine local = LocalEngine.builder().rootDirectory(tempDirA).build();
        local.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(local)
                .membership(new NoopMembershipProtocol()).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                .transport(transportA).config(ClusterConfig.builder().build()).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        try {
            // Pre-close: a QUERY_REQUEST succeeds via the handler.
            final byte[] payload = jlsm.engine.cluster.internal.QueryRequestPayload
                    .encodeGet("users", 0L, "missing");
            final Message preRequest = new Message(MessageType.QUERY_REQUEST, NODE_B, 1L, payload);
            final Message preResponse = transportB.request(NODE_A, preRequest).get(5,
                    TimeUnit.SECONDS);
            assertEquals(MessageType.QUERY_RESPONSE, preResponse.type());
        } catch (Exception e) {
            engine.close();
            transportB.close();
            throw e;
        }

        // close() must not throw — deregister must happen before transport.close.
        assertDoesNotThrow(engine::close,
                "close() must succeed — deregisterHandler must run before transport.close");

        try {
            // Post-close: QUERY_REQUEST fails fast with IOException cause (no handler / closed).
            final byte[] payload = jlsm.engine.cluster.internal.QueryRequestPayload
                    .encodeGet("users", 0L, "missing");
            final Message postRequest = new Message(MessageType.QUERY_REQUEST, NODE_B, 2L, payload);
            final CompletableFuture<Message> postFuture = transportB.request(NODE_A, postRequest);
            assertThrows(ExecutionException.class, () -> postFuture.get(2, TimeUnit.SECONDS));
        } finally {
            transportB.close();
        }
    }

    // Finding: H-RL-10
    // Bug: If transport.registerHandler throws during ClusteredEngine construction (closed
    // transport), the engine may swallow the failure and leak a partially-initialized instance.
    // Correct behavior: The construction failure propagates (IllegalStateException from the closed
    // transport); no usable engine instance is returned.
    // Fix location: ClusteredEngine ctor (must not swallow registerHandler failures)
    // Regression watch: Normal build on a non-closed transport continues to succeed.
    @Test
    void ctor_registerHandlerThrows_buildFails() throws IOException {
        final LocalEngine local = LocalEngine.builder().rootDirectory(tempDirA).build();
        try {
            final InJvmTransport closedTransport = new InJvmTransport(NODE_A);
            closedTransport.close();
            // The transport is now closed — registerHandler must throw IllegalStateException
            // during ctor, which must propagate out of build().
            assertThrows(IllegalStateException.class,
                    () -> ClusteredEngine.builder().localEngine(local)
                            .membership(new NoopMembershipProtocol())
                            .ownership(new RendezvousOwnership())
                            .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                            .transport(closedTransport).config(ClusterConfig.builder().build())
                            .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build());
        } finally {
            local.close();
        }
    }

    // Finding: H-RL-9 / H-CC-9
    // Bug: Post-close QUERY_REQUEST arriving via the shared transport could hang indefinitely
    // or return a silent empty payload instead of signaling the handler absence.
    // Correct behavior: After engine.close(), new QUERY_REQUESTs from a remote client complete
    // exceptionally — no silent drops, no hangs. F04.R81 semantics on transport side.
    // Fix location: ClusteredEngine.close (ensure deregister runs; transport.request semantics
    // hold)
    // Regression watch: Pre-close requests still succeed.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void close_definedBehavior_postCloseRequestFailsFast() throws Exception {
        final LocalEngine local = LocalEngine.builder().rootDirectory(tempDirA).build();
        local.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(local)
                .membership(new NoopMembershipProtocol()).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                .transport(transportA).config(ClusterConfig.builder().build()).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        try {
            engine.close();
            final byte[] payload = jlsm.engine.cluster.internal.QueryRequestPayload
                    .encodeGet("users", 0L, "k");
            final Message request = new Message(MessageType.QUERY_REQUEST, NODE_B, 99L, payload);
            final CompletableFuture<Message> future = transportB.request(NODE_A, request);
            // Must not hang — either fails fast (handler gone / transport closed) or succeeds
            // because the in-flight call happened to race before deregistration.
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        } finally {
            transportB.close();
        }
    }

    // Finding: H-DT-8
    // Bug: The payload encoder and handler decoder can drift — a format change on one side not
    // mirrored on the other would pass unit tests but break the round-trip on every opcode.
    // Correct behavior: Full round-trip through RemotePartitionClient encoder → handler decoder →
    // handler response encoder → client response decoder succeeds for all opcodes (create,
    // get, update, delete, getRange).
    // Fix location: Format contract between RemotePartitionClient and QueryRequestHandler
    // Regression watch: Individual unit-tests for each opcode continue to pass.
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void endToEnd_crossModuleRoundTrip_allOpcodes() throws Exception {
        final LocalEngine engineB = LocalEngine.builder().rootDirectory(tempDirB).build();
        engineB.createTable("users", SCHEMA);

        final InJvmTransport transportA = new InJvmTransport(NODE_A);
        final InJvmTransport transportB = new InJvmTransport(NODE_B);
        final QueryRequestHandler handlerB = new QueryRequestHandler(engineB, NODE_B);
        transportB.registerHandler(MessageType.QUERY_REQUEST, handlerB);

        try {
            final PartitionDescriptor desc = new PartitionDescriptor(0L,
                    MemorySegment.ofArray(new byte[0]),
                    MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), NODE_B.nodeId(), 0L);

            try (RemotePartitionClient client = new RemotePartitionClient(desc, NODE_B, transportA,
                    NODE_A, SCHEMA, "users")) {
                // CREATE
                final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "u1", "value", "v1");
                client.create("key1", doc);
                // GET
                final Optional<JlsmDocument> got = client.get("key1");
                assertTrue(got.isPresent(),
                        "End-to-end GET must retrieve document persisted by end-to-end CREATE");
                assertEquals("u1", got.get().getString("id"));
                // UPDATE
                final JlsmDocument updated = JlsmDocument.of(SCHEMA, "id", "u1", "value", "v2");
                client.update("key1", updated, UpdateMode.REPLACE);
                final Optional<JlsmDocument> afterUpdate = client.get("key1");
                assertTrue(afterUpdate.isPresent());
                assertEquals("v2", afterUpdate.get().getString("value"));
                // DELETE
                client.delete("key1");
                assertTrue(client.get("key1").isEmpty(),
                        "End-to-end DELETE must remove the document");
                // RANGE
                client.create("apple", JlsmDocument.of(SCHEMA, "id", "a", "value", "1"));
                client.create("banana", JlsmDocument.of(SCHEMA, "id", "b", "value", "2"));
                final Iterator<jlsm.table.TableEntry<String>> iter = client.getRange("a", "z");
                int count = 0;
                while (iter.hasNext()) {
                    iter.next();
                    count++;
                }
                assertEquals(2, count);
            }
        } finally {
            transportA.close();
            transportB.close();
            engineB.close();
        }
    }

    // Helper to reference StandardCharsets import.
    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // --- Helpers ---

    /** Minimal no-op MembershipProtocol for building a ClusteredEngine without join(). */
    private static final class NoopMembershipProtocol implements MembershipProtocol {
        @Override
        public void start(java.util.List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return new MembershipView(0, java.util.Set.of(), java.time.Instant.EPOCH);
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

    /** Reference to {@link TimeoutException} so tooling keeps the import if we need it. */
    @SuppressWarnings("unused")
    private static void unusedTimeout(TimeoutException t) {
    }
}

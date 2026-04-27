package jlsm.engine.cluster.internal;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;
import jlsm.engine.internal.LocalEngine;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueryRequestHandler} — server-side dispatcher that routes QUERY_REQUEST messages
 * to a local {@link Engine}'s tables based on the table name in the payload header.
 *
 * <p>
 * Delivers: F04.R68.
 */
final class QueryRequestHandlerTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9101);
    private static final NodeAddress SENDER = new NodeAddress("sender", "localhost", 9102);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    @TempDir
    Path tempDir;

    private LocalEngine engine;
    private QueryRequestHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        engine = LocalEngine.builder().rootDirectory(tempDir).build();
        engine.createTable("users", SCHEMA);
        handler = new QueryRequestHandler(engine, LOCAL);
    }

    @AfterEach
    void tearDown() throws IOException {
        engine.close();
    }

    // --- Happy path (7) ---

    @Test
    void ctor_validArgs_succeeds() {
        assertDoesNotThrow(() -> new QueryRequestHandler(engine, LOCAL));
    }

    @Test
    void handle_createOpcode_dispatchesToLocalTable() throws Exception {
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "v1");
        final byte[] payload = QueryRequestPayload.encodeCreate("users", 0L, "k1", doc);
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final Message response = future.get();
        assertNotNull(response);

        final Table users = engine.getTable("users");
        assertTrue(users.get("k1").isPresent());
    }

    @Test
    void handle_getOpcode_returnsDocumentJson() throws Exception {
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "v1");
        engine.getTable("users").create("k1", doc);

        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k1");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 2L, payload);

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final Message response = future.get();
        final byte[] responsePayload = response.payload();
        assertTrue(responsePayload.length > 0,
                "Response payload for a present document must be non-empty");
        final String json = new String(responsePayload, StandardCharsets.UTF_8);
        final JlsmDocument returned = JlsmDocument.fromJson(json, SCHEMA);
        assertEquals("user-1", returned.getString("id"));
    }

    @Test
    void handle_getOpcode_missingKey_returnsEmptyPayload() throws Exception {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "missing");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 3L, payload);

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final Message response = future.get();
        assertEquals(0, response.payload().length);
    }

    @Test
    void handle_updateOpcode_dispatchesToLocalTable() throws Exception {
        final JlsmDocument original = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "old");
        engine.getTable("users").create("k1", original);

        final JlsmDocument updated = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "new");
        final byte[] payload = QueryRequestPayload.encodeUpdate("users", 0L, "k1", updated,
                UpdateMode.REPLACE);
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 4L, payload);

        handler.handle(SENDER, msg).get();
        final Optional<JlsmDocument> actual = engine.getTable("users").get("k1");
        assertTrue(actual.isPresent());
        assertEquals("new", actual.get().getString("value"));
    }

    @Test
    void handle_deleteOpcode_dispatchesToLocalTable() throws Exception {
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "v1");
        engine.getTable("users").create("k1", doc);

        final byte[] payload = QueryRequestPayload.encodeDelete("users", 0L, "k1");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 5L, payload);

        handler.handle(SENDER, msg).get();
        assertTrue(engine.getTable("users").get("k1").isEmpty());
    }

    @Test
    void handle_rangeOpcode_returnsEntriesPayload() throws Exception {
        final Table users = engine.getTable("users");
        users.create("apple", JlsmDocument.of(SCHEMA, "id", "a", "value", "1"));
        users.create("banana", JlsmDocument.of(SCHEMA, "id", "b", "value", "2"));
        users.create("cherry", JlsmDocument.of(SCHEMA, "id", "c", "value", "3"));

        final byte[] payload = QueryRequestPayload.encodeRange("users", 0L, "a", "z");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 6L, payload);

        final Message response = handler.handle(SENDER, msg).get();
        final byte[] responsePayload = response.payload();
        assertTrue(responsePayload.length >= 4,
                "Range response must contain an entry count prefix");

        final ByteBuffer buf = ByteBuffer.wrap(responsePayload);
        final int count = buf.getInt();
        assertEquals(3, count);
    }

    // --- Error (7) ---

    @Test
    void ctor_nullLocalEngine_throws() {
        assertThrows(NullPointerException.class, () -> new QueryRequestHandler(null, LOCAL));
    }

    @Test
    void ctor_nullLocalAddress_throws() {
        assertThrows(NullPointerException.class, () -> new QueryRequestHandler(engine, null));
    }

    @Test
    void handle_nullSender_throws() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);
        assertThrows(NullPointerException.class, () -> handler.handle(null, msg));
    }

    @Test
    void handle_nullMsg_throws() {
        assertThrows(NullPointerException.class, () -> handler.handle(SENDER, null));
    }

    @Test
    void handle_unknownTableName_failedFutureIOException() {
        final byte[] payload = QueryRequestPayload.encodeGet("ghost", 0L, "k");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    void handle_unknownOpcode_failedFutureIOException() {
        // Craft a payload mimicking the header format but with an invalid opcode (99).
        final byte[] tableName = "users".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(4 + tableName.length + 8 + 1);
        buf.putInt(tableName.length);
        buf.put(tableName);
        buf.putLong(0L);
        buf.put((byte) 99);
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, buf.array());

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    void handle_tableOperationIOException_propagated() throws Exception {
        // Create a key; second create with the same key must trigger an IOException from
        // the table layer which the handler should surface as a failed future.
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "v");
        engine.getTable("users").create("dup", doc);

        final byte[] payload = QueryRequestPayload.encodeCreate("users", 0L, "dup", doc);
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);

        final CompletableFuture<Message> future = handler.handle(SENDER, msg);
        final ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }

    // --- Structural (2) ---

    @Test
    void handle_responseSequenceNumberMatchesRequest() throws Exception {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "anything");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1234L, payload);

        final Message response = handler.handle(SENDER, msg).get();
        assertEquals(1234L, response.sequenceNumber());
    }

    @Test
    void handle_responseMessageTypeIsQueryResponse() throws Exception {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "anything");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 7L, payload);

        final Message response = handler.handle(SENDER, msg).get();
        assertEquals(MessageType.QUERY_RESPONSE, response.type());
    }

    // --- Adversarial (hardening-cycle 1) ---

    // Finding: H-CB-5
    // Bug: Response sender field may mistakenly echo msg.sender() (the original requester)
    // rather than the handler's localAddress — misrouting reply paths.
    // Correct behavior: Response Message.sender() equals the handler's localAddress, not the
    // request sender.
    // Fix location: QueryRequestHandler.handle
    // Regression watch: Response seq number + QUERY_RESPONSE type still match request seq.
    @Test
    void handle_responseSenderIsLocalAddress_notRequestSender() throws Exception {
        final NodeAddress otherSender = new NodeAddress("remote", "localhost", 9999);
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "anything");
        final Message msg = new Message(MessageType.QUERY_REQUEST, otherSender, 42L, payload);

        final Message response = handler.handle(otherSender, msg).get();
        assertEquals(LOCAL, response.sender(),
                "Response sender must be the handler's localAddress, not the request sender");
        assertNotEquals(otherSender, response.sender());
    }

    // Finding: H-CB-4 / H-CC-1
    // Bug: Concurrent handle() calls may corrupt shared state or interfere via non-thread-safe
    // decoding/lookup, producing failed futures under concurrent load.
    // Correct behavior: Handler is stateless + thread-safe; concurrent invocations all succeed.
    // Fix location: QueryRequestHandler.handle
    // Regression watch: Single-threaded handle() still works; GET semantics unchanged.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void handle_concurrentInvocations_allSucceed() throws Exception {
        // Pre-populate 400 keys on the users table.
        final Table users = engine.getTable("users");
        final int threads = 8;
        final int callsPerThread = 50;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < callsPerThread; i++) {
                users.create("k-" + t + "-" + i,
                        JlsmDocument.of(SCHEMA, "id", "id-" + t + "-" + i, "value", "v"));
            }
        }

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicInteger successes = new AtomicInteger(0);
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tt = t;
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        try {
                            final byte[] payload = QueryRequestPayload.encodeGet("users", 0L,
                                    "k-" + tt + "-" + i);
                            final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER,
                                    (long) tt * 1000 + i, payload);
                            handler.handle(SENDER, msg).get(5, TimeUnit.SECONDS);
                            successes.incrementAndGet();
                        } catch (Exception _) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS),
                "All worker threads must complete within the timeout");
        assertEquals(threads * callsPerThread, successes.get(),
                "All concurrent handle() invocations must succeed — got " + successes.get()
                        + " successes and " + failures.get() + " failures");
        assertEquals(0, failures.get(), "Concurrent handle() invocations must all succeed — got "
                + failures.get() + " failures");
    }

    // Finding: H-RL-11
    // Bug: When the local engine has been closed, handle() may synchronously throw IOException
    // (escaping as RuntimeException via Engine.getTable) rather than returning a failed
    // future — breaking the "handle always returns a future" contract.
    // Correct behavior: Handler returns a future that completes exceptionally with IOException
    // when the local engine is closed; no synchronous throw other than NPE on null params.
    // Fix location: QueryRequestHandler.handle (wrap localEngine.getTable dispatch in try/catch →
    // failedFuture)
    // Regression watch: Open engine continues to return successful completed futures.
    @Test
    void handle_closedLocalEngine_failedFutureNotSyncThrow() throws IOException {
        final LocalEngine separate = LocalEngine.builder().rootDirectory(tempDir.resolve("closed"))
                .build();
        separate.createTable("users", SCHEMA);
        separate.close();
        final QueryRequestHandler onClosed = new QueryRequestHandler(separate, LOCAL);

        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);

        // Must return a future (sync throw of IOException is a contract violation).
        final CompletableFuture<Message> future = onClosed.handle(SENDER, msg);
        assertNotNull(future, "handle() must return a non-null future even for closed engine");
        final ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, ex.getCause());
    }

    // Finding: H-CC-8
    // Bug: A QueryRequestHandler implementation could implicitly rely on ClusteredEngine.join()
    // having completed (e.g. reading membership state), making it unusable pre-join.
    // Correct behavior: Handler accepts requests as soon as it is constructed against a bare
    // LocalEngine — no cluster setup or join() required.
    // Fix location: QueryRequestHandler.handle (must not depend on cluster state)
    // Regression watch: All opcodes continue to dispatch under the normal cluster path.
    @Test
    void handle_beforeEngineJoin_stillDispatches() throws Exception {
        // `engine` here is a bare LocalEngine (set up in @BeforeEach with no ClusteredEngine).
        engine.getTable("users").create("pre-join",
                JlsmDocument.of(SCHEMA, "id", "x", "value", "y"));

        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "pre-join");
        final Message msg = new Message(MessageType.QUERY_REQUEST, SENDER, 1L, payload);

        // The handler was constructed in @BeforeEach against the bare LocalEngine — no
        // ClusteredEngine.build() or join() has ever been called. This must still work.
        final Message response = handler.handle(SENDER, msg).get(5, TimeUnit.SECONDS);
        assertEquals(MessageType.QUERY_RESPONSE, response.type());
        assertTrue(response.payload().length > 0,
                "GET for a present key must return non-empty document payload pre-join");
    }

    /**
     * A no-op utility method to keep unused imports referenced. Not intended as a test.
     */
    @SuppressWarnings("unused")
    private static Iterator<TableEntry<String>> unused() {
        return null;
    }

    /**
     * A no-op utility to keep {@link Engine}/{@link TableMetadata}/{@link EngineMetrics}/
     * {@link Collection} imports referenced in case a sub-case needs them later.
     */
    @SuppressWarnings("unused")
    private static void unusedCollection(Engine e, TableMetadata m, EngineMetrics em,
            Collection<TableMetadata> c, Instant i) {
    }
}

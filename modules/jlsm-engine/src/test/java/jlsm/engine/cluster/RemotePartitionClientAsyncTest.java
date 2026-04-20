package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.QueryRequestPayload;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RemotePartitionClient#getRangeAsync(String, String)} and the payload-header
 * contract that delivers F04.R68 + F04.R77.
 */
final class RemotePartitionClientAsyncTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9401);
    private static final NodeAddress REMOTE = new NodeAddress("remote", "localhost", 9402);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    private InJvmTransport localTransport;
    private InJvmTransport remoteTransport;
    private PartitionDescriptor descriptor;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        RemotePartitionClient.resetOpenInstanceCounter();
        localTransport = new InJvmTransport(LOCAL);
        remoteTransport = new InJvmTransport(REMOTE);
        descriptor = new PartitionDescriptor(42L, MemorySegment.ofArray(new byte[0]),
                MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), REMOTE.nodeId(), 0L);
    }

    @AfterEach
    void tearDown() {
        localTransport.close();
        remoteTransport.close();
        InJvmTransport.clearRegistry();
    }

    // --- Async future shape ---

    @Test
    void getRangeAsync_returnsNonNullFuture() throws IOException {
        registerEmptyEntriesHandler();
        try (RemotePartitionClient client = newClient()) {
            assertNotNull(client.getRangeAsync("a", "z"));
        }
    }

    @Test
    void getRangeAsync_independentFuturesForConcurrentCalls() throws IOException {
        registerEmptyEntriesHandler();
        try (RemotePartitionClient client = newClient()) {
            final CompletableFuture<Iterator<TableEntry<String>>> f1 = client.getRangeAsync("a",
                    "z");
            final CompletableFuture<Iterator<TableEntry<String>>> f2 = client.getRangeAsync("a",
                    "z");
            assertNotSame(f1, f2);
            // Drain to avoid a hanging future.
            f1.cancel(true);
            f2.cancel(true);
        }
    }

    @Test
    void getRangeAsync_completesWithIteratorOnResponse() throws Exception {
        registerEntriesHandler("apple", "banana");
        try (RemotePartitionClient client = newClient()) {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            final Iterator<TableEntry<String>> iter = future.get(5, TimeUnit.SECONDS);
            assertNotNull(iter);
            assertTrue(iter.hasNext());
            assertEquals("apple", iter.next().key());
            assertTrue(iter.hasNext());
            assertEquals("banana", iter.next().key());
            assertFalse(iter.hasNext());
        }
    }

    @Test
    void getRangeAsync_transportFailure_futureCompletedExceptionally() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST,
                (sender, msg) -> CompletableFuture.failedFuture(new IOException("boom")));
        try (RemotePartitionClient client = newClient()) {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            final ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, ex.getCause());
        }
    }

    @Test
    void getRangeAsync_remoteUnreachable_futureCompletedExceptionally() throws IOException {
        remoteTransport.close();
        try (RemotePartitionClient client = newClient()) {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void getRangeAsync_afterClose_throwsOrFailedFuture() throws IOException {
        registerEmptyEntriesHandler();
        final RemotePartitionClient client = newClient();
        client.close();
        boolean surfaced;
        try {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            assertNotNull(future);
            try {
                future.get(2, TimeUnit.SECONDS);
                surfaced = false;
            } catch (ExecutionException e) {
                surfaced = true;
            } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
                surfaced = false;
            }
        } catch (RuntimeException e) {
            surfaced = true;
        }
        assertTrue(surfaced,
                "getRangeAsync on a closed client must either throw or return a failed future");
    }

    @Test
    void getRangeAsync_timeoutExpires_futureCompletedExceptionally() throws Exception {
        // Handler returns a future that never completes — client must timeout via orTimeout.
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST,
                (sender, msg) -> new CompletableFuture<>());
        final RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                localTransport, LOCAL, SCHEMA, 100L, "users");
        try {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            assertThrows(ExecutionException.class, () -> future.get(1500, TimeUnit.MILLISECONDS));
        } finally {
            client.close();
        }
    }

    // --- Payload header (F04.R68) ---

    @Test
    void payload_headerContainsTableName() throws Exception {
        final AtomicReference<byte[]> captured = new AtomicReference<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            captured.set(msg.payload());
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]));
        });

        try (RemotePartitionClient client = newClient()) {
            client.get("anything");
        }

        final byte[] payload = captured.get();
        assertNotNull(payload);
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals("users", header.tableName());
    }

    @Test
    void payload_headerContainsPartitionId() throws Exception {
        final AtomicReference<byte[]> captured = new AtomicReference<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            captured.set(msg.payload());
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]));
        });

        try (RemotePartitionClient client = newClient()) {
            client.get("anything");
        }

        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(captured.get());
        assertEquals(42L, header.partitionId());
    }

    @Test
    void payload_headerContainsOpcode_forEachOperation() throws Exception {
        final AtomicReference<byte[]> captured = new AtomicReference<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            captured.set(msg.payload());
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), emptyEntriesResponse()));
        });

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "u", "value", "v");
        try (RemotePartitionClient client = newClient()) {
            client.create("k", doc);
            assertEquals(QueryRequestPayload.OP_CREATE,
                    QueryRequestPayload.decodeHeader(captured.get()).opcode());

            client.get("k");
            assertEquals(QueryRequestPayload.OP_GET,
                    QueryRequestPayload.decodeHeader(captured.get()).opcode());

            client.update("k", doc, UpdateMode.REPLACE);
            assertEquals(QueryRequestPayload.OP_UPDATE,
                    QueryRequestPayload.decodeHeader(captured.get()).opcode());

            client.delete("k");
            assertEquals(QueryRequestPayload.OP_DELETE,
                    QueryRequestPayload.decodeHeader(captured.get()).opcode());

            client.getRange("a", "z");
            assertEquals(QueryRequestPayload.OP_RANGE,
                    QueryRequestPayload.decodeHeader(captured.get()).opcode());
        }
    }

    // --- Constructor validation ---

    @Test
    void constructor_nullTableName_throws() {
        assertThrows(NullPointerException.class,
                () -> new RemotePartitionClient(descriptor, REMOTE, localTransport, LOCAL, null));
    }

    @Test
    void constructor_emptyTableName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new RemotePartitionClient(descriptor, REMOTE, localTransport, LOCAL, ""));
    }

    // --- Adversarial (hardening-cycle 1) ---

    // Finding: H-RL-4
    // Bug: client.close() may cancel in-flight getRangeAsync futures, breaking callers that
    // kicked off an async call and then closed the client as part of scatter-gather cleanup
    // (whenComplete). orTimeout is the backstop — close must not propagate cancellation.
    // Correct behavior: A future returned by getRangeAsync before close() either completes
    // normally with the iterator or fails via transport/timeout; it must NOT be
    // CancellationException triggered by close().
    // Fix location: RemotePartitionClient.close (must not cancel inflight async futures)
    // Regression watch: getRangeAsync after close() still surfaces a failed future (R-L-2).
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void clientClose_doesNotCancelInFlightAsyncFuture() throws Exception {
        // Handler completes the response after a delay.
        final CompletableFuture<Message> handlerFuture = new CompletableFuture<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            // Return a future that completes asynchronously with empty-entries response.
            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> {
                final ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(0);
                handlerFuture.complete(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                        msg.sequenceNumber(), buf.array()));
            });
            return handlerFuture;
        });

        final RemotePartitionClient client = newClient();
        final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                "z");
        client.close();
        // The future must resolve — either with an iterator (success) or an IOException-caused
        // ExecutionException. It must NOT be a CancellationException caused by close().
        try {
            final Iterator<TableEntry<String>> iter = future.get(2, TimeUnit.SECONDS);
            assertNotNull(iter);
            assertFalse(iter.hasNext(), "Handler returned empty entries — iterator must be empty");
        } catch (ExecutionException ex) {
            // Acceptable: an underlying IOException or IllegalStateException is OK.
            assertFalse(ex.getCause() instanceof java.util.concurrent.CancellationException,
                    "close() must not cause CancellationException on in-flight async future");
        }
    }

    // Finding: H-CC-2
    // Bug: Concurrent getRangeAsync invocations might share or collide on a non-atomic sequence
    // counter, leading to duplicate sequence numbers in the emitted QUERY_REQUEST messages.
    // Correct behavior: Every invocation produces a distinct sequence number — AtomicLong
    // contract holds under contention (400 calls → 400 distinct sequence numbers).
    // Fix location: RemotePartitionClient.getRangeAsync sequence generation
    // Regression watch: Sequence numbers grow monotonically for single-threaded use.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void getRangeAsync_concurrentCalls_produceDistinctSequenceNumbers() throws Exception {
        final ConcurrentHashMap<Long, Boolean> seenSequences = new ConcurrentHashMap<>();
        final AtomicInteger collisions = new AtomicInteger(0);
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            if (seenSequences.putIfAbsent(msg.sequenceNumber(), Boolean.TRUE) != null) {
                collisions.incrementAndGet();
            }
            final ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(0);
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), buf.array()));
        });

        try (RemotePartitionClient client = newClient()) {
            final int threads = 8;
            final int callsPerThread = 50;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            try {
                                client.getRangeAsync("a", "z").get(5, TimeUnit.SECONDS);
                            } catch (Exception _) {
                                // Ignore — we care about seq-number collisions.
                            }
                        }
                    } catch ( InterruptedException _) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        }
        assertEquals(0, collisions.get(),
                "Concurrent getRangeAsync must produce distinct sequence numbers — got "
                        + collisions.get() + " collisions");
        assertEquals(8 * 50, seenSequences.size(), "All 400 sequence numbers must be distinct");
    }

    // Finding: H-CB-7
    // Bug: If a non-conformant ClusterTransport returns null from request(...), the resulting
    // thenApply(...) will NPE, escaping as an uncaught synchronous exception or corrupted
    // future.
    // Correct behavior: getRangeAsync defensively completes the returned future exceptionally
    // with IOException when the transport returns null.
    // Fix location: RemotePartitionClient.getRangeAsync defensive null-check on transport result
    // Regression watch: Normal non-null futures continue to flow through thenApply.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getRangeAsync_transportReturnsNullFuture_futureFailsIoException() throws IOException {
        final ClusterTransport nullTransport = new NullReturningTransport();
        try (RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                nullTransport, LOCAL, SCHEMA, "users")) {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            assertNotNull(future);
            final ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, ex.getCause());
        }
    }

    // Finding: H-DT-6
    // Bug: A truncated response payload (claims N entries but provides none) could cause a
    // BufferUnderflow inside the parser, or silently return partial/empty results.
    // Correct behavior: getRangeAsync completes the returned future exceptionally with
    // IOException when the transport response payload is malformed.
    // Fix location: RemotePartitionClient.getRangeAsync response deserializer
    // Regression watch: Well-formed responses (including zero-entry) continue to work.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getRangeAsync_malformedResponsePayload_futureFailsIoException() throws IOException {
        // Response claims 99999 entries but provides no body — decoder must detect short-read.
        final ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(99999);
        final byte[] malformed = buf.array();
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                                        msg.sequenceNumber(), malformed)));

        try (RemotePartitionClient client = newClient()) {
            final CompletableFuture<Iterator<TableEntry<String>>> future = client.getRangeAsync("a",
                    "z");
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS),
                    "Malformed response must complete the future exceptionally");
        }
    }

    // --- Helpers ---

    private RemotePartitionClient newClient() {
        return new RemotePartitionClient(descriptor, REMOTE, localTransport, LOCAL, SCHEMA,
                "users");
    }

    private void registerEmptyEntriesHandler() {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST,
                (sender, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                                msg.sequenceNumber(), emptyEntriesResponse())));
    }

    private void registerEntriesHandler(String... keys) {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST,
                (sender, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE,
                                msg.sequenceNumber(), entriesResponse(keys))));
    }

    private static byte[] emptyEntriesResponse() {
        final ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(0);
        return buf.array();
    }

    private static byte[] entriesResponse(String... keys) {
        final byte[][] keyBytes = new byte[keys.length][];
        final byte[][] docBytes = new byte[keys.length][];
        int total = 4;
        for (int i = 0; i < keys.length; i++) {
            keyBytes[i] = keys[i].getBytes(StandardCharsets.UTF_8);
            final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", keys[i], "value", "v");
            docBytes[i] = doc.toJson().getBytes(StandardCharsets.UTF_8);
            total += 4 + keyBytes[i].length + 4 + docBytes[i].length;
        }
        final ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(keys.length);
        for (int i = 0; i < keys.length; i++) {
            buf.putInt(keyBytes[i].length);
            buf.put(keyBytes[i]);
            buf.putInt(docBytes[i].length);
            buf.put(docBytes[i]);
        }
        return buf.array();
    }

    /**
     * ClusterTransport stub that returns null from request(...) — a contract violation used to
     * exercise the defensive null-check in getRangeAsync (H-CB-7).
     */
    private static final class NullReturningTransport implements ClusterTransport {
        @Override
        public void send(NodeAddress target, Message msg) {
        }

        @Override
        public CompletableFuture<Message> request(NodeAddress target, Message msg) {
            return null; // Intentional contract violation.
        }

        @Override
        public void registerHandler(MessageType type, MessageHandler handler) {
        }

        @Override
        public void deregisterHandler(MessageType type) {
        }

        @Override
        public void close() {
        }
    }
}

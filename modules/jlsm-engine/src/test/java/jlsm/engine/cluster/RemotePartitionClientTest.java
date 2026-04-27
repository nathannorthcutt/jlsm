package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;
import jlsm.cluster.Message;
import jlsm.cluster.MessageType;

import jlsm.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionDescriptor;
import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RemotePartitionClient} — serializes CRUD as QUERY_REQUEST messages, sends via
 * ClusterTransport.request(), deserializes QUERY_RESPONSE.
 *
 * @spec engine.clustering.R68 — CRUD serialization with table + partition id
 * @spec engine.clustering.R69 — remote exceptions propagated as local exceptions
 * @spec engine.clustering.R70 — per-request timeout via transport.request() future
 * @spec engine.clustering.R101 — full document + operation mode carried in payload
 */
final class RemotePartitionClientTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9001);
    private static final NodeAddress REMOTE = new NodeAddress("remote", "localhost", 9002);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    private InJvmTransport localTransport;
    private InJvmTransport remoteTransport;
    private RemotePartitionClient client;
    private PartitionDescriptor descriptor;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new InJvmTransport(LOCAL);
        remoteTransport = new InJvmTransport(REMOTE);
        descriptor = new PartitionDescriptor(1L,
                MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray("m".getBytes(StandardCharsets.UTF_8)), REMOTE.nodeId(), 0L);
        client = new RemotePartitionClient(descriptor, REMOTE, localTransport, LOCAL, "users");
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        localTransport.close();
        remoteTransport.close();
        InJvmTransport.clearRegistry();
    }

    // --- Constructor validation ---

    @Test
    void constructor_nullDescriptor_throws() {
        assertThrows(NullPointerException.class,
                () -> new RemotePartitionClient(null, REMOTE, localTransport, LOCAL, "users"));
    }

    @Test
    void constructor_nullOwner_throws() {
        assertThrows(NullPointerException.class,
                () -> new RemotePartitionClient(descriptor, null, localTransport, LOCAL, "users"));
    }

    @Test
    void constructor_nullTransport_throws() {
        assertThrows(NullPointerException.class,
                () -> new RemotePartitionClient(descriptor, REMOTE, null, LOCAL, "users"));
    }

    @Test
    void constructor_nullLocalAddress_throws() {
        assertThrows(NullPointerException.class,
                () -> new RemotePartitionClient(descriptor, REMOTE, localTransport, null, "users"));
    }

    // --- descriptor() ---

    @Test
    void descriptor_returnsConfiguredDescriptor() {
        assertSame(descriptor, client.descriptor());
    }

    // --- create() roundtrip via transport ---

    @Test
    void create_sendsRequestAndCompletesSuccessfully() throws IOException {
        // Register a handler on the remote that echoes back success
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "key1", "value", "val1");
        // Should not throw
        assertDoesNotThrow(() -> client.create("key1", doc));
    }

    // --- get() roundtrip ---

    @Test
    void get_sendsRequestAndReturnsDocument() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            // Respond with a payload indicating a found document
            // For now, we use a simple protocol: non-empty payload = found
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), "found".getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });

        final Optional<JlsmDocument> result = client.get("key1");
        // The implementation should return a result based on the response
        assertNotNull(result);
    }

    // --- delete() roundtrip ---

    @Test
    void delete_sendsRequestAndCompletesSuccessfully() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        assertDoesNotThrow(() -> client.delete("key1"));
    }

    // --- update() roundtrip ---

    @Test
    void update_sendsRequestAndCompletesSuccessfully() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "key1", "value", "updated");
        assertDoesNotThrow(() -> client.update("key1", doc, UpdateMode.REPLACE));
    }

    // --- getRange() ---

    @Test
    void getRange_sendsRequestAndReturnsIterator() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        final Iterator<TableEntry<String>> it = client.getRange("a", "z");
        assertNotNull(it);
    }

    // --- query() ---

    @Test
    void query_sendsRequestAndReturnsScoredEntries() throws IOException {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        final List<ScoredEntry<String>> results = client
                .query(new jlsm.table.Predicate.Eq("value", "test"), 10);
        assertNotNull(results);
    }

    // --- Timeout handling ---

    @Test
    void request_remoteUnreachable_throwsIOException() {
        // Close the remote transport so it's no longer reachable
        remoteTransport.close();

        assertThrows(IOException.class, () -> client.get("key1"));
    }

    @Test
    void request_handlerReturnsFailedFuture_throwsIOException() {
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender,
                msg) -> CompletableFuture.failedFuture(new IOException("simulated failure")));

        assertThrows(IOException.class, () -> client.get("key1"));
    }

    // --- close() ---

    @Test
    void close_isIdempotent() throws IOException {
        client.close();
        assertDoesNotThrow(() -> client.close());
    }
}

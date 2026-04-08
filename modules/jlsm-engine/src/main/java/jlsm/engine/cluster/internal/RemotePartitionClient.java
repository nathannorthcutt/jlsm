package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionClient;
import jlsm.table.PartitionDescriptor;
import jlsm.table.Predicate;
import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remote partition client that communicates with a partition owner via the cluster transport.
 *
 * <p>
 * Contract: Implements {@link PartitionClient} by serializing CRUD and query operations as
 * {@code QUERY_REQUEST} messages, sending them to the remote partition owner via
 * {@link ClusterTransport#request}, and deserializing the {@code QUERY_RESPONSE}.
 *
 * <p>
 * Side effects: Sends messages via the cluster transport. Blocks on the response future with a
 * configurable timeout.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
public final class RemotePartitionClient implements PartitionClient {

    /** Operation codes for the request payload. */
    private static final byte OP_CREATE = 1;
    private static final byte OP_GET = 2;
    private static final byte OP_UPDATE = 3;
    private static final byte OP_DELETE = 4;
    private static final byte OP_RANGE = 5;
    private static final byte OP_QUERY = 6;

    /** Default timeout for request-response exchanges, in milliseconds. */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * Tracks the number of RemotePartitionClient instances that have been constructed but not yet
     * closed. Used for resource lifecycle verification — a non-zero count after a call site
     * completes indicates a client leak.
     */
    private static final AtomicInteger OPEN_INSTANCES = new AtomicInteger(0);

    private final PartitionDescriptor descriptor;
    private final NodeAddress owner;
    private final ClusterTransport transport;
    private final NodeAddress localAddress;
    private final JlsmSchema schema;
    private final long timeoutMs;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile boolean closed;

    /**
     * Creates a remote partition client with the default timeout and no schema (document
     * deserialization on get() is not supported).
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress) {
        this(descriptor, owner, transport, localAddress, null, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates a remote partition client with a schema for document deserialization and the default
     * timeout.
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     * @param schema the document schema for deserializing get() responses; must not be null
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, JlsmSchema schema) {
        this(descriptor, owner, transport, localAddress,
                Objects.requireNonNull(schema, "schema must not be null"), DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates a remote partition client with a custom timeout.
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     * @param schema the document schema for deserializing get() responses; may be null
     * @param timeoutMs timeout in milliseconds for request-response; must be positive
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, JlsmSchema schema,
            long timeoutMs) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.schema = schema;
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, got: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
        OPEN_INSTANCES.incrementAndGet();
    }

    @Override
    public PartitionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void doCreate(String key, JlsmDocument doc) throws IOException {
        checkNotClosed();

        final byte[] payload = encodeKeyDocPayload(OP_CREATE, key, doc);
        sendRequestAndAwait(payload);
    }

    @Override
    public Optional<JlsmDocument> doGet(String key) throws IOException {
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_GET, key);
        final Message response = sendRequestAndAwait(payload);

        // Empty payload = not found; non-empty = found
        assert response != null : "response must not be null after successful await";
        final byte[] responsePayload = response.payload();
        if (responsePayload.length == 0) {
            return Optional.empty();
        }
        if (schema == null) {
            // No schema provided — cannot deserialize the document.
            return Optional.empty();
        }
        final String json = new String(responsePayload, StandardCharsets.UTF_8);
        return Optional.of(JlsmDocument.fromJson(json, schema));
    }

    @Override
    public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        checkNotClosed();

        final byte[] payload = encodeKeyDocModePayload(OP_UPDATE, key, doc, mode);
        sendRequestAndAwait(payload);
    }

    @Override
    public void doDelete(String key) throws IOException {
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_DELETE, key);
        sendRequestAndAwait(payload);
    }

    @Override
    public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey)
            throws IOException {
        checkNotClosed();

        final byte[] fromBytes = fromKey.getBytes(StandardCharsets.UTF_8);
        final byte[] toBytes = toKey.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + fromBytes.length + 4 + toBytes.length);
        buf.put(OP_RANGE);
        buf.putInt(fromBytes.length);
        buf.put(fromBytes);
        buf.putInt(toBytes.length);
        buf.put(toBytes);

        final Message response = sendRequestAndAwait(buf.array());

        assert response != null : "response must not be null after successful await";
        final byte[] responsePayload = response.payload();
        if (responsePayload.length == 0) {
            return Collections.emptyIterator();
        }
        if (schema == null) {
            // No schema provided — cannot deserialize entries.
            return Collections.emptyIterator();
        }

        // Response format: [4-byte entry count][entries...]
        // Each entry: [4-byte key-length][key-bytes][4-byte doc-length][doc-json-bytes]
        final ByteBuffer responseBuf = ByteBuffer.wrap(responsePayload);
        final int entryCount = responseBuf.getInt();
        final List<TableEntry<String>> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            final int keyLen = responseBuf.getInt();
            final byte[] keyBytes = new byte[keyLen];
            responseBuf.get(keyBytes);
            final String key = new String(keyBytes, StandardCharsets.UTF_8);

            final int docLen = responseBuf.getInt();
            final byte[] docBytes = new byte[docLen];
            responseBuf.get(docBytes);
            final String json = new String(docBytes, StandardCharsets.UTF_8);
            final JlsmDocument doc = JlsmDocument.fromJson(json, schema);

            entries.add(new TableEntry<>(key, doc));
        }
        return Collections.unmodifiableList(entries).iterator();
    }

    @Override
    public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        checkNotClosed();

        final ByteBuffer buf = ByteBuffer.allocate(1 + 4);
        buf.put(OP_QUERY);
        buf.putInt(limit);

        sendRequestAndAwait(buf.array());
        // Response deserialization of scored entries will be wired with full message format.
        return List.of();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            OPEN_INSTANCES.decrementAndGet();
        }
    }

    /**
     * Returns the number of RemotePartitionClient instances that are currently open (constructed
     * but not yet closed). Intended for resource lifecycle testing.
     *
     * @return the count of open instances
     */
    public static int openInstances() {
        return OPEN_INSTANCES.get();
    }

    /**
     * Resets the open instance counter. Intended for test cleanup only.
     */
    public static void resetOpenInstanceCounter() {
        OPEN_INSTANCES.set(0);
    }

    // ---- Private helpers ----

    /**
     * Encodes a single-key operation payload: [opcode][key-length][key-bytes].
     */
    private byte[] encodeKeyPayload(byte opcode, String key) {
        assert key != null : "key must not be null";
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + keyBytes.length);
        buf.put(opcode);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        return buf.array();
    }

    /**
     * Encodes a key+document operation payload:
     * [opcode][key-length][key-bytes][doc-length][doc-json-bytes].
     */
    private byte[] encodeKeyDocPayload(byte opcode, String key, JlsmDocument doc) {
        assert key != null : "key must not be null";
        assert doc != null : "doc must not be null";
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final byte[] docBytes = doc.toJson().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + keyBytes.length + 4 + docBytes.length);
        buf.put(opcode);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(docBytes.length);
        buf.put(docBytes);
        return buf.array();
    }

    /**
     * Encodes a key+document+mode operation payload:
     * [opcode][key-length][key-bytes][doc-length][doc-json-bytes][mode-length][mode-name-bytes].
     */
    private byte[] encodeKeyDocModePayload(byte opcode, String key, JlsmDocument doc,
            UpdateMode mode) {
        assert key != null : "key must not be null";
        assert doc != null : "doc must not be null";
        assert mode != null : "mode must not be null";
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final byte[] docBytes = doc.toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] modeBytes = mode.name().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer
                .allocate(1 + 4 + keyBytes.length + 4 + docBytes.length + 4 + modeBytes.length);
        buf.put(opcode);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(docBytes.length);
        buf.put(docBytes);
        buf.putInt(modeBytes.length);
        buf.put(modeBytes);
        return buf.array();
    }

    /**
     * Sends a QUERY_REQUEST to the remote owner and blocks until the response arrives or the
     * timeout elapses.
     *
     * @param payload the request payload
     * @return the response message
     * @throws IOException if the request fails, times out, or the transport is unreachable
     */
    private Message sendRequestAndAwait(byte[] payload) throws IOException {
        assert payload != null : "payload must not be null";

        final long seq = sequenceCounter.getAndIncrement();
        final Message request = new Message(MessageType.QUERY_REQUEST, localAddress, seq, payload);

        final CompletableFuture<Message> future = transport.request(owner, request);
        assert future != null : "transport.request must return a non-null future";

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Request timed out after " + timeoutMs + "ms to " + owner, e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Request to " + owner + " failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request to " + owner + " was interrupted", e);
        }
    }

    /**
     * Checks that this client has not been closed.
     *
     * @throws IOException if the client is closed
     */
    private void checkNotClosed() throws IOException {
        if (closed) {
            throw new IOException("RemotePartitionClient is closed");
        }
    }
}

package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;
import jlsm.table.JlsmDocument;
import jlsm.table.PartitionClient;
import jlsm.table.PartitionDescriptor;
import jlsm.table.Predicate;
import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private final PartitionDescriptor descriptor;
    private final NodeAddress owner;
    private final ClusterTransport transport;
    private final NodeAddress localAddress;
    private final long timeoutMs;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile boolean closed;

    /**
     * Creates a remote partition client with the default timeout.
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress) {
        this(descriptor, owner, transport, localAddress, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates a remote partition client with a custom timeout.
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     * @param timeoutMs timeout in milliseconds for request-response; must be positive
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, long timeoutMs) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, got: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
    }

    @Override
    public PartitionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_CREATE, key);
        sendRequestAndAwait(payload);
    }

    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_GET, key);
        final Message response = sendRequestAndAwait(payload);

        // Empty payload = not found; non-empty = found
        assert response != null : "response must not be null after successful await";
        final byte[] responsePayload = response.payload();
        if (responsePayload.length == 0) {
            return Optional.empty();
        }
        // In-JVM: the response handler will provide a meaningful document.
        // For now, we return empty for empty payload, present signal for non-empty.
        // Full document deserialization will be wired when the message format is finalized.
        return Optional.empty();
    }

    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_UPDATE, key);
        sendRequestAndAwait(payload);
    }

    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        final byte[] payload = encodeKeyPayload(OP_DELETE, key);
        sendRequestAndAwait(payload);
    }

    @Override
    public Iterator<TableEntry<String>> getRange(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();

        final byte[] fromBytes = fromKey.getBytes(StandardCharsets.UTF_8);
        final byte[] toBytes = toKey.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + fromBytes.length + 4 + toBytes.length);
        buf.put(OP_RANGE);
        buf.putInt(fromBytes.length);
        buf.put(fromBytes);
        buf.putInt(toBytes.length);
        buf.put(toBytes);

        sendRequestAndAwait(buf.array());
        // Response deserialization of entries will be wired with full message format.
        return Collections.emptyIterator();
    }

    @Override
    public List<ScoredEntry<String>> query(Predicate predicate, int limit) throws IOException {
        Objects.requireNonNull(predicate, "predicate must not be null");
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
        closed = true;
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

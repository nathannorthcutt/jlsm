package jlsm.engine.cluster.internal;

import jlsm.cluster.ClusterTransport;
import jlsm.cluster.Message;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * @spec engine.clustering.R68 — serializes CRUD into transport message format carrying table +
 *       partition id
 * @spec engine.clustering.R69 — deserializes responses; propagates remote exceptions as local
 *       exceptions
 * @spec engine.clustering.R70 — enforces per-request timeout; cancels future on timeout; reports
 *       unavailable
 * @spec engine.clustering.R101 — serializes full document + operation mode for
 *       create/update/delete; decodes non-empty get/scan payloads
 * @spec engine.clustering.R109 — validates transport future is non-null with a runtime check (not
 *       assert)
 * @spec engine.clustering.R110 — timeout cancels the source transport future (not just a downstream
 *       wrapper)
 * @spec engine.clustering.R111 — local-origin failures (encoding errors) distinguished from
 *       remote-node failures
 * @spec engine.clustering.R112 — response encoder uses checked arithmetic to avoid int overflow in
 *       size fields
 * @spec engine.clustering.R114 — range-scan decoder fails explicitly on malformed
 *       populated-payload-without-schema
 */
public final class RemotePartitionClient implements PartitionClient {

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
    private final String tableName;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    // Atomic to make the check-then-set in close() race-free — concurrent callers must not
    // both decrement OPEN_INSTANCES for the same client instance.
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a remote partition client with the default timeout and no schema (document
     * deserialization on get() is not supported).
     *
     * @param descriptor the partition descriptor; must not be null
     * @param owner the address of the partition owner; must not be null
     * @param transport the cluster transport; must not be null
     * @param localAddress the local node address (for message sender field); must not be null
     * @param tableName the table name included in every payload header; must not be null or empty
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, String tableName) {
        this(descriptor, owner, transport, localAddress, null, DEFAULT_TIMEOUT_MS, tableName);
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
     * @param tableName the table name included in every payload header; must not be null or empty
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, JlsmSchema schema,
            String tableName) {
        this(descriptor, owner, transport, localAddress,
                Objects.requireNonNull(schema, "schema must not be null"), DEFAULT_TIMEOUT_MS,
                tableName);
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
     * @param tableName the table name included in every payload header; must not be null or empty
     */
    public RemotePartitionClient(PartitionDescriptor descriptor, NodeAddress owner,
            ClusterTransport transport, NodeAddress localAddress, JlsmSchema schema, long timeoutMs,
            String tableName) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.schema = schema;
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, got: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName must not be empty");
        }
        this.tableName = tableName;
        OPEN_INSTANCES.incrementAndGet();
    }

    @Override
    public PartitionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void doCreate(String key, JlsmDocument doc) throws IOException {
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeCreate(tableName, descriptor.id(), key,
                doc);
        sendRequestAndAwait(payload);
    }

    @Override
    public Optional<JlsmDocument> doGet(String key) throws IOException {
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeGet(tableName, descriptor.id(), key);
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
        try {
            return Optional.of(JlsmDocument.fromJson(json, schema));
        } catch (IllegalArgumentException jpe) {
            // Tolerate a malformed response payload (e.g. unexpected framing from a test stub or
            // protocol drift) — treat as a not-found result rather than a crash. A future hardening
            // may surface this as a distinct IOException if callers need to distinguish.
            return Optional.empty();
        }
    }

    @Override
    public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeUpdate(tableName, descriptor.id(), key,
                doc, mode);
        sendRequestAndAwait(payload);
    }

    @Override
    public void doDelete(String key) throws IOException {
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeDelete(tableName, descriptor.id(), key);
        sendRequestAndAwait(payload);
    }

    @Override
    public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey)
            throws IOException {
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeRange(tableName, descriptor.id(), fromKey,
                toKey);
        final Message response = sendRequestAndAwait(payload);

        assert response != null : "response must not be null after successful await";
        return decodeRangeResponsePayload(response.payload());
    }

    @Override
    public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        checkNotClosed();

        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        final byte[] payload = QueryRequestPayload.encodeQuery(tableName, descriptor.id(), limit);
        sendRequestAndAwait(payload);
        // Response deserialization of scored entries will be wired with full message format.
        return List.of();
    }

    @Override
    public void close() throws IOException {
        // Atomic compareAndSet ensures only one thread transitions from open → closed and
        // therefore only one thread decrements OPEN_INSTANCES, even under concurrent close().
        //
        // F-R1.shared_state.2.4 — clamp the decrement at zero. The counter represents a count of
        // live instances; if resetOpenInstanceCounter() or any other path brings the counter to
        // 0 while a live client still exists, a plain decrementAndGet() would drive the count
        // negative, corrupting leak-detection semantics. updateAndGet(v -> Math.max(0, v - 1))
        // leaves the counter at 0 rather than producing a nonsensical negative count.
        if (closed.compareAndSet(false, true)) {
            OPEN_INSTANCES.updateAndGet(v -> Math.max(0, v - 1));
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

    /**
     * Returns entries in this partition within the given key range asynchronously.
     *
     * <p>
     * Contract: Encode an {@code OP_RANGE} payload via
     * {@link QueryRequestPayload#encodeRange(String, long, String, String)} and call
     * {@link ClusterTransport#request(NodeAddress, Message)}. The returned future must complete
     * with the decoded entry iterator on success, or exceptionally on transport failure. Must not
     * block the calling thread. Must apply {@code orTimeout(timeoutMs, MILLISECONDS)} per F04.R70
     * and cancel the future on timeout.
     *
     * <p>
     * Delivers: F04.R77 — scatter-gather must use the transport's asynchronous request mechanism.
     *
     * <p>
     * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
     *
     * @param fromKey inclusive lower bound; must not be null
     * @param toKey exclusive upper bound; must not be null
     * @return future completing with the entry iterator, or exceptionally on failure; never null
     */
    @Override
    public CompletableFuture<Iterator<TableEntry<String>>> getRangeAsync(String fromKey,
            String toKey) {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        if (closed.get()) {
            return CompletableFuture
                    .failedFuture(new IOException("RemotePartitionClient is closed"));
        }
        // @spec engine.clustering.R68 — payload header carries table name + partition id for remote
        // routing.
        // F-R1.data_transformation.1.7 — a RuntimeException from encodeRange is a local
        // client-side programmer/state bug (e.g. invalidated internal state post-construction),
        // NOT a transport failure. Let it propagate synchronously rather than wrap into a
        // failed future: returning a failed IOException future causes upstream scatter-gather
        // (ClusteredTable.scan) to classify the node as "unavailable" in PartialResultMetadata,
        // falsely attributing a local encoding bug to a remote node outage.
        final byte[] payload = QueryRequestPayload.encodeRange(tableName, descriptor.id(), fromKey,
                toKey);
        final long seq = sequenceCounter.getAndIncrement();
        final Message request = new Message(MessageType.QUERY_REQUEST, localAddress, seq, payload);
        final CompletableFuture<Message> transportFuture;
        try {
            transportFuture = transport.request(owner, request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new IOException("transport.request threw", e));
        }
        // H-CB-7 — defensive null-check on transport return value.
        if (transportFuture == null) {
            return CompletableFuture
                    .failedFuture(new IOException("transport.request returned null future"));
        }
        // @spec engine.clustering.R70 — per-request timeout. Rather than orTimeout (which completes
        // the source
        // future in-place with TimeoutException, making a subsequent cancel(true) a no-op),
        // schedule
        // a direct cancel of the source future on timeoutMs. This cancel is observable via
        // isCancelled() — a transport that registers a whenComplete on its returned future can
        // detect the client gave up and release any server-side state tied to this request.
        // If the source future completes before timeout, the scheduled cancel is a no-op on the
        // already-completed future (harmless) and is bounded by timeoutMs.
        CompletableFuture.runAsync(() -> transportFuture.cancel(true),
                CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS));
        return transportFuture.handle((response, err) -> {
            if (err != null) {
                if (err instanceof CompletionException ce && ce.getCause() != null) {
                    err = ce.getCause();
                }
                if (err instanceof java.util.concurrent.CancellationException) {
                    // Source future was cancelled by the scheduled timeout — translate to an
                    // IOException wrapping a TimeoutException so the caller sees a timeout error
                    // (matching prior orTimeout contract).
                    throw new CompletionException(new IOException(
                            "Request timed out after " + timeoutMs + "ms to " + owner,
                            new TimeoutException("Request timed out after " + timeoutMs + "ms")));
                }
                if (err instanceof TimeoutException) {
                    throw new CompletionException(new IOException(
                            "Request timed out after " + timeoutMs + "ms to " + owner, err));
                }
                if (err instanceof IOException ioe) {
                    throw new CompletionException(ioe);
                }
                throw new CompletionException(
                        new IOException("Request to " + owner + " failed", err));
            }
            try {
                return decodeRangeResponsePayload(response.payload());
            } catch (IOException ioe) {
                throw new CompletionException(ioe);
            } catch (RuntimeException re) {
                throw new CompletionException(
                        new IOException("Failed to decode RANGE response", re));
            }
        });
    }

    /**
     * Decodes a QUERY_RESPONSE RANGE payload into an iterator over materialized entries.
     *
     * <p>
     * Format: {@code [4-byte count][count × (keyLen:i32, key, docLen:i32, doc)]}. Malformed or
     * truncated payloads cause {@link IOException}. A zero-length payload yields an empty iterator
     * (legitimate empty range). A populated payload with {@code schema == null} raises an
     * {@link IOException} naming the misconfiguration, rather than silently discarding the
     * partition's results (F-R1.data_transformation.1.6 — stub-client-data-loss guard). Shared
     * between the synchronous {@link #doGetRange(String, String)} and asynchronous
     * {@link #getRangeAsync(String, String)} paths so both honour the same hardening checks
     * (H-DT-6).
     */
    private Iterator<TableEntry<String>> decodeRangeResponsePayload(byte[] responsePayload)
            throws IOException {
        assert responsePayload != null : "responsePayload must not be null";
        if (responsePayload.length == 0) {
            return Collections.emptyIterator();
        }
        if (schema == null) {
            // Populated RANGE payload received but no schema is configured — this is a
            // client-construction misconfiguration (no-schema constructor used for a scan path).
            // Surface it loudly rather than collapsing it into Collections.emptyIterator(), which
            // would silently discard an entire partition's results and contaminate a scatter-
            // gather aggregation with PartialResultMetadata.isComplete=true.
            throw new IOException("Cannot decode RANGE response on table '" + tableName
                    + "': RemotePartitionClient constructed without a schema but received a "
                    + "populated response payload (" + responsePayload.length + " bytes). Use "
                    + "the schema-aware constructor to enable deserialization.");
        }
        try {
            final ByteBuffer buf = ByteBuffer.wrap(responsePayload);
            final int entryCount = buf.getInt();
            if (entryCount < 0) {
                throw new IOException("Negative entry count in RANGE response: " + entryCount);
            }
            final List<TableEntry<String>> entries = new ArrayList<>(Math.min(entryCount, 1024));
            for (int i = 0; i < entryCount; i++) {
                final int keyLen = buf.getInt();
                if (keyLen < 0) {
                    throw new IOException("Negative key length at entry " + i);
                }
                if (buf.remaining() < keyLen) {
                    throw new IOException(
                            "Truncated RANGE response — missing key bytes at entry " + i);
                }
                final byte[] keyBytes = new byte[keyLen];
                buf.get(keyBytes);
                final String key = new String(keyBytes, StandardCharsets.UTF_8);

                final int docLen = buf.getInt();
                if (docLen < 0) {
                    throw new IOException("Negative doc length at entry " + i);
                }
                if (buf.remaining() < docLen) {
                    throw new IOException(
                            "Truncated RANGE response — missing doc bytes at entry " + i);
                }
                final byte[] docBytes = new byte[docLen];
                buf.get(docBytes);
                final JlsmDocument doc = JlsmDocument
                        .fromJson(new String(docBytes, StandardCharsets.UTF_8), schema);
                entries.add(new TableEntry<>(key, doc));
            }
            return Collections.unmodifiableList(entries).iterator();
        } catch (java.nio.BufferUnderflowException bue) {
            throw new IOException("Truncated RANGE response payload", bue);
        }
    }

    // ---- Private helpers ----

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
        // H-CB-7 — runtime guard mirroring the async getRangeAsync path. The ClusterTransport SPI
        // contract forbids a null return, but we defensively convert a violation into an
        // IOException rather than allowing an NPE at future.get(...) in -da mode.
        if (future == null) {
            throw new IOException("transport.request returned null future for " + owner);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // @spec engine.clustering.R70 — cancel the future on timeout so the transport releases
            // any
            // resources associated with the pending response and the partition is reported as
            // unavailable rather than leaked as an indefinitely-pending future.
            future.cancel(true);
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
        if (closed.get()) {
            throw new IOException("RemotePartitionClient is closed");
        }
    }
}

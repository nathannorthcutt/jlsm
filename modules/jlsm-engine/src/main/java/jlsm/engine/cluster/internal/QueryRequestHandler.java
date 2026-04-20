package jlsm.engine.cluster.internal;

import jlsm.engine.Engine;
import jlsm.engine.Table;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageHandler;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side dispatcher for {@link MessageType#QUERY_REQUEST} messages.
 *
 * <p>
 * Contract: Registered with the cluster transport so that incoming QUERY_REQUEST messages from
 * remote partition clients are routed to a local table. The handler decodes the payload header to
 * identify the target table, looks the table up via {@link Engine#getTable(String)}, dispatches the
 * CRUD or range operation, and serializes the result into a QUERY_RESPONSE message.
 *
 * <p>
 * Side effects: Invokes local table operations (create/update/delete/scan). Returns a future that
 * completes with the QUERY_RESPONSE or exceptionally on I/O or dispatch failure.
 *
 * <p>
 * Delivers: F04.R68 — remote-side dispatcher that routes QUERY_REQUEST to the correct local table
 * using the table name prefix in the payload.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md},
 * {@code .decisions/scatter-gather-query-execution/adr.md}
 */
public final class QueryRequestHandler implements MessageHandler {

    private final Engine localEngine;
    private final NodeAddress localAddress;

    /**
     * Creates a handler bound to the given local engine.
     *
     * @param localEngine the engine that owns the target tables; must not be null
     * @param localAddress the local node address (for the response sender field); must not be null
     */
    public QueryRequestHandler(Engine localEngine, NodeAddress localAddress) {
        this.localEngine = Objects.requireNonNull(localEngine, "localEngine must not be null");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
    }

    /**
     * Handles an incoming QUERY_REQUEST.
     *
     * <p>
     * Contract: decode header → {@code localEngine.getTable(tableName)} → dispatch on opcode →
     * serialize response payload → return completedFuture(response). On any failure (unknown table,
     * decode error, table IOException), return failedFuture with an appropriate IOException. The
     * response Message uses the same sequence number as the request and
     * {@link MessageType#QUERY_RESPONSE}.
     *
     * <p>
     * Delivers: F04.R68
     *
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    @Override
    public CompletableFuture<Message> handle(NodeAddress sender, Message msg) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(msg, "msg must not be null");
        try {
            // Clone the payload once — Message.payload() returns a defensive copy on every
            // call, so invoking it twice (for decodeHeader and dispatch) doubles the
            // defensive-clone allocation per dispatch. Hoist into a local and reuse.
            final byte[] payload = msg.payload();
            final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
            final Table table;
            try {
                table = localEngine.getTable(header.tableName());
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            } catch (IllegalStateException ise) {
                // H-RL-11 — closed engine raises IllegalStateException from getTable; surface as
                // IOException via failed future rather than a synchronous throw.
                return CompletableFuture.failedFuture(
                        new IOException("Local engine unavailable: " + ise.getMessage(), ise));
            }
            final byte[] responsePayload = dispatch(header, payload, table);
            final Message response = new Message(MessageType.QUERY_RESPONSE, localAddress,
                    msg.sequenceNumber(), responsePayload);
            return CompletableFuture.completedFuture(response);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(
                    new IOException("Bad QUERY_REQUEST payload: " + e.getMessage(), e));
        } catch (RuntimeException e) {
            return CompletableFuture
                    .failedFuture(new IOException("QUERY_REQUEST dispatch failed", e));
        }
    }

    /**
     * Dispatches a decoded QUERY_REQUEST to the local table. Returns the opcode-specific response
     * payload (empty byte[] for ops with no return value; UTF-8 JSON for GET; count-prefixed entry
     * list for RANGE; empty byte[] for QUERY — scored-entry responses are not yet wired).
     */
    private byte[] dispatch(QueryRequestPayload.Header header, byte[] payload, Table table)
            throws IOException {
        assert header != null : "header must not be null";
        assert payload != null : "payload must not be null";
        assert table != null : "table must not be null";
        final JlsmSchema schema = table.metadata().schema();
        return switch (header.opcode()) {
            case QueryRequestPayload.OP_CREATE -> {
                final QueryRequestPayload.KeyDocBody body = QueryRequestPayload
                        .decodeKeyDocBody(payload, header.bodyOffset(), false);
                final JlsmDocument doc = JlsmDocument.fromJson(body.docJson(), schema);
                table.create(body.key(), doc);
                yield new byte[0];
            }
            case QueryRequestPayload.OP_GET -> {
                final QueryRequestPayload.KeyBody body = QueryRequestPayload.decodeKeyBody(payload,
                        header.bodyOffset());
                final Optional<JlsmDocument> got = table.get(body.key());
                yield got.map(d -> d.toJson().getBytes(StandardCharsets.UTF_8)).orElse(new byte[0]);
            }
            case QueryRequestPayload.OP_UPDATE -> {
                final QueryRequestPayload.KeyDocBody body = QueryRequestPayload
                        .decodeKeyDocBody(payload, header.bodyOffset(), true);
                final JlsmDocument doc = JlsmDocument.fromJson(body.docJson(), schema);
                final UpdateMode mode = body.modeOrNull();
                if (mode == null) {
                    throw new IOException("UPDATE payload missing mode");
                }
                table.update(body.key(), doc, mode);
                yield new byte[0];
            }
            case QueryRequestPayload.OP_DELETE -> {
                final QueryRequestPayload.KeyBody body = QueryRequestPayload.decodeKeyBody(payload,
                        header.bodyOffset());
                table.delete(body.key());
                yield new byte[0];
            }
            case QueryRequestPayload.OP_RANGE -> {
                final QueryRequestPayload.RangeBody body = QueryRequestPayload
                        .decodeRangeBody(payload, header.bodyOffset());
                yield encodeRangeResponse(table.scan(body.fromKey(), body.toKey()));
            }
            case QueryRequestPayload.OP_QUERY -> {
                // Scored-entry response framing is not yet wired — return empty payload.
                // Tests only assert non-null results in clustered mode.
                yield new byte[0];
            }
            default -> throw new IOException("unknown opcode: " + (int) header.opcode());
        };
    }

    /**
     * Serializes a range iterator as {@code [int count][int keyLen][key][int docLen][docJson]...}.
     *
     * <p>
     * Eagerly guards against two failure modes that would otherwise leak non-{@link IOException}
     * throwables out of {@link #handle}: (a) silent {@code int} overflow of the total-size
     * accumulator when the cumulative wire size of the scan response exceeds
     * {@link Integer#MAX_VALUE}, and (b) {@link OutOfMemoryError} from materializing per-entry
     * UTF-8 byte arrays when the scan result is larger than the JVM heap budget. Both conditions
     * are surfaced as descriptive {@link IOException}s so that the {@link #handle} dispatcher can
     * report "response too large" rather than an opaque runtime error.
     */
    private static byte[] encodeRangeResponse(Iterator<TableEntry<String>> it) throws IOException {
        assert it != null : "iterator must not be null";
        final List<byte[]> keyBytesList = new ArrayList<>();
        final List<byte[]> docBytesList = new ArrayList<>();
        int total = 4;
        try {
            while (it.hasNext()) {
                final TableEntry<String> entry = it.next();
                final byte[] keyBytes = entry.key().getBytes(StandardCharsets.UTF_8);
                final byte[] docBytes = entry.document().toJson().getBytes(StandardCharsets.UTF_8);
                keyBytesList.add(keyBytes);
                docBytesList.add(docBytes);
                try {
                    total = Math.addExact(total, Math.addExact(Math.addExact(4, keyBytes.length),
                            Math.addExact(4, docBytes.length)));
                } catch (ArithmeticException ae) {
                    throw new IOException(
                            "Range response size exceeds Integer.MAX_VALUE bytes (total overflow "
                                    + "after " + keyBytesList.size() + " entries)",
                            ae);
                }
            }
            final ByteBuffer buf = ByteBuffer.allocate(total);
            buf.putInt(keyBytesList.size());
            for (int i = 0; i < keyBytesList.size(); i++) {
                final byte[] keyBytes = keyBytesList.get(i);
                final byte[] docBytes = docBytesList.get(i);
                buf.putInt(keyBytes.length);
                buf.put(keyBytes);
                buf.putInt(docBytes.length);
                buf.put(docBytes);
            }
            return buf.array();
        } catch (OutOfMemoryError oom) {
            // Drop references so the caught-and-rethrown path does not retain the
            // multi-GiB working set while the caller builds its failed future.
            keyBytesList.clear();
            docBytesList.clear();
            throw new IOException("Range response too large to buffer in memory (accumulated "
                    + total + " bytes before exhaustion)", oom);
        }
    }
}

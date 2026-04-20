package jlsm.engine.cluster.internal;

import jlsm.table.JlsmDocument;
import jlsm.table.UpdateMode;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes and decodes QUERY_REQUEST payloads for the remote partition protocol.
 *
 * <p>
 * Contract: The payload format is
 *
 * <pre>
 *   [4-byte tableName length : int32 BE]
 *   [tableName UTF-8 bytes]
 *   [8-byte partition id : int64 BE]
 *   [1-byte opcode]
 *   [op-specific bytes — key, doc, mode, range bounds, query limit, etc.]
 * </pre>
 *
 * Implementations of {@link jlsm.table.PartitionClient} on the sending side call {@code encode*} to
 * build the payload; the server-side dispatcher calls {@link #decodeHeader(byte[])} to route, then
 * op-specific decoders to extract parameters.
 *
 * <p>
 * Delivers: F04.R68 — payload format carries table name and partition id so the receiver can route
 * to the correct local table.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
public final class QueryRequestPayload {

    /** Opcode: create a document. */
    public static final byte OP_CREATE = 1;
    /** Opcode: get a document by key. */
    public static final byte OP_GET = 2;
    /** Opcode: update a document (with mode). */
    public static final byte OP_UPDATE = 3;
    /** Opcode: delete a document by key. */
    public static final byte OP_DELETE = 4;
    /** Opcode: range scan. */
    public static final byte OP_RANGE = 5;
    /** Opcode: predicate query with limit. */
    public static final byte OP_QUERY = 6;

    private QueryRequestPayload() {
    }

    /** Decoded payload header (table name + partition id + opcode). Body follows. */
    public record Header(String tableName, long partitionId, byte opcode, int bodyOffset) {
    }

    /** Decoded CREATE / UPDATE body: key + doc (+ mode for UPDATE). */
    public record KeyDocBody(String key, String docJson, UpdateMode modeOrNull) {
    }

    /** Decoded GET / DELETE body: key only. */
    public record KeyBody(String key) {
    }

    /** Decoded RANGE body: fromKey, toKey. */
    public record RangeBody(String fromKey, String toKey) {
    }

    /** Decoded QUERY body: limit. */
    public record QueryBody(int limit) {
    }

    /**
     * Encodes a CREATE payload. Contract: [header][key-len][key][doc-len][doc].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeCreate(String tableName, long partitionId, String key,
            JlsmDocument doc) {
        validateTableName(tableName);
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final byte[] docBytes = doc.toJson().getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer
                .allocate(headerSize(tableNameBytes) + 4 + keyBytes.length + 4 + docBytes.length);
        writeHeader(buf, tableNameBytes, partitionId, OP_CREATE);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(docBytes.length);
        buf.put(docBytes);
        return buf.array();
    }

    /**
     * Encodes a GET payload. Contract: [header][key-len][key].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeGet(String tableName, long partitionId, String key) {
        validateTableName(tableName);
        Objects.requireNonNull(key, "key must not be null");

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer
                .allocate(headerSize(tableNameBytes) + 4 + keyBytes.length);
        writeHeader(buf, tableNameBytes, partitionId, OP_GET);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        return buf.array();
    }

    /**
     * Encodes an UPDATE payload. Contract: [header][key-len][key][doc-len][doc][mode-len][mode].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeUpdate(String tableName, long partitionId, String key,
            JlsmDocument doc, UpdateMode mode) {
        validateTableName(tableName);
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final byte[] docBytes = doc.toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] modeBytes = mode.name().getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer.allocate(headerSize(tableNameBytes) + 4 + keyBytes.length
                + 4 + docBytes.length + 4 + modeBytes.length);
        writeHeader(buf, tableNameBytes, partitionId, OP_UPDATE);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(docBytes.length);
        buf.put(docBytes);
        buf.putInt(modeBytes.length);
        buf.put(modeBytes);
        return buf.array();
    }

    /**
     * Encodes a DELETE payload. Contract: [header][key-len][key].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeDelete(String tableName, long partitionId, String key) {
        validateTableName(tableName);
        Objects.requireNonNull(key, "key must not be null");

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer
                .allocate(headerSize(tableNameBytes) + 4 + keyBytes.length);
        writeHeader(buf, tableNameBytes, partitionId, OP_DELETE);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        return buf.array();
    }

    /**
     * Encodes a RANGE payload. Contract: [header][from-len][from][to-len][to].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeRange(String tableName, long partitionId, String fromKey,
            String toKey) {
        validateTableName(tableName);
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);
        final byte[] fromBytes = fromKey.getBytes(StandardCharsets.UTF_8);
        final byte[] toBytes = toKey.getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer
                .allocate(headerSize(tableNameBytes) + 4 + fromBytes.length + 4 + toBytes.length);
        writeHeader(buf, tableNameBytes, partitionId, OP_RANGE);
        buf.putInt(fromBytes.length);
        buf.put(fromBytes);
        buf.putInt(toBytes.length);
        buf.put(toBytes);
        return buf.array();
    }

    /**
     * Encodes a QUERY payload. Contract: [header][limit:int32].
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static byte[] encodeQuery(String tableName, long partitionId, int limit) {
        validateTableName(tableName);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }

        final byte[] tableNameBytes = tableName.getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buf = ByteBuffer.allocate(headerSize(tableNameBytes) + 4);
        writeHeader(buf, tableNameBytes, partitionId, OP_QUERY);
        buf.putInt(limit);
        return buf.array();
    }

    /**
     * Decodes the header (tableName, partitionId, opcode, bodyOffset).
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static Header decodeHeader(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length < 4) {
            throw new IllegalArgumentException(
                    "payload too short to contain tableName length: " + payload.length);
        }

        final ByteBuffer buf = ByteBuffer.wrap(payload);
        final int tableNameLen = buf.getInt();
        if (tableNameLen < 0) {
            throw new IllegalArgumentException(
                    "tableName length must be non-negative, got: " + tableNameLen);
        }
        if (buf.remaining() < tableNameLen + 8 + 1) {
            throw new IllegalArgumentException("payload truncated: tableNameLen=" + tableNameLen
                    + ", remaining=" + buf.remaining());
        }

        final byte[] tableNameBytes = new byte[tableNameLen];
        buf.get(tableNameBytes);
        final String tableName = new String(tableNameBytes, StandardCharsets.UTF_8);
        final long partitionId = buf.getLong();
        final byte opcode = buf.get();
        if (opcode < OP_CREATE || opcode > OP_QUERY) {
            throw new IllegalArgumentException("opcode out of range [1..6]: " + (int) opcode);
        }
        final int bodyOffset = 4 + tableNameLen + 8 + 1;
        return new Header(tableName, partitionId, opcode, bodyOffset);
    }

    /**
     * Decodes a KEY-only body at the given offset.
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static KeyBody decodeKeyBody(byte[] payload, int offset) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (offset < 0 || offset > payload.length) {
            throw new IllegalArgumentException(
                    "offset out of range: " + offset + " (payload length " + payload.length + ")");
        }
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final String key = readString(buf, "key");
            return new KeyBody(key);
        } catch (BufferUnderflowException bue) {
            throw new IllegalArgumentException("KEY body truncated", bue);
        }
    }

    /**
     * Decodes a KEY+DOC body at the given offset. When {@code withMode} is true, a trailing mode
     * field is also decoded and populated on the returned record.
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static KeyDocBody decodeKeyDocBody(byte[] payload, int offset, boolean withMode) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (offset < 0 || offset > payload.length) {
            throw new IllegalArgumentException(
                    "offset out of range: " + offset + " (payload length " + payload.length + ")");
        }
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final String key = readString(buf, "key");
            final String docJson = readString(buf, "doc");
            UpdateMode mode = null;
            if (withMode) {
                final String modeName = readString(buf, "mode");
                try {
                    mode = UpdateMode.valueOf(modeName);
                } catch (IllegalArgumentException iae) {
                    throw new IllegalArgumentException("unknown UpdateMode: " + modeName, iae);
                }
            }
            return new KeyDocBody(key, docJson, mode);
        } catch (BufferUnderflowException bue) {
            throw new IllegalArgumentException("KEY+DOC body truncated", bue);
        }
    }

    /**
     * Decodes a RANGE body at the given offset.
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static RangeBody decodeRangeBody(byte[] payload, int offset) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (offset < 0 || offset > payload.length) {
            throw new IllegalArgumentException(
                    "offset out of range: " + offset + " (payload length " + payload.length + ")");
        }
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final String fromKey = readString(buf, "fromKey");
            final String toKey = readString(buf, "toKey");
            return new RangeBody(fromKey, toKey);
        } catch (BufferUnderflowException bue) {
            throw new IllegalArgumentException("RANGE body truncated", bue);
        }
    }

    /**
     * Decodes a QUERY body at the given offset.
     *
     * <p>
     * Delivers: F04.R68
     * <p>
     * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
     */
    public static QueryBody decodeQueryBody(byte[] payload, int offset) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (offset < 0 || offset > payload.length) {
            throw new IllegalArgumentException(
                    "offset out of range: " + offset + " (payload length " + payload.length + ")");
        }
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final int limit = buf.getInt();
            return new QueryBody(limit);
        } catch (BufferUnderflowException bue) {
            throw new IllegalArgumentException("QUERY body truncated", bue);
        }
    }

    // ---- Private helpers ----

    private static void validateTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName must not be empty");
        }
    }

    private static int headerSize(byte[] tableNameBytes) {
        assert tableNameBytes != null : "tableNameBytes must not be null";
        return 4 + tableNameBytes.length + 8 + 1;
    }

    private static void writeHeader(ByteBuffer buf, byte[] tableNameBytes, long partitionId,
            byte opcode) {
        assert buf != null : "buf must not be null";
        assert tableNameBytes != null : "tableNameBytes must not be null";
        buf.putInt(tableNameBytes.length);
        buf.put(tableNameBytes);
        buf.putLong(partitionId);
        buf.put(opcode);
    }

    private static String readString(ByteBuffer buf, String fieldName) {
        assert buf != null : "buf must not be null";
        assert fieldName != null : "fieldName must not be null";
        final int len = buf.getInt();
        if (len < 0) {
            throw new IllegalArgumentException(
                    fieldName + " length must be non-negative, got: " + len);
        }
        if (buf.remaining() < len) {
            throw new IllegalArgumentException(
                    fieldName + " truncated: expected " + len + " bytes, have " + buf.remaining());
        }
        final byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

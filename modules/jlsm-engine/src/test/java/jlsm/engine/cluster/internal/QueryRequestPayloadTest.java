package jlsm.engine.cluster.internal;

import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueryRequestPayload} — encodes and decodes QUERY_REQUEST payloads so the remote
 * dispatcher can route to the correct local table using the table name + partition id prefix.
 *
 * <p>
 * Delivers: F04.R68.
 */
final class QueryRequestPayloadTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("users", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    private static JlsmDocument sampleDoc() {
        return JlsmDocument.of(SCHEMA, "id", "user-1", "value", "hello");
    }

    // --- Round-trip (6) ---

    @Test
    void encodeCreate_roundTripsThroughDecodeHeaderAndKeyDocBody() {
        final JlsmDocument doc = sampleDoc();
        final byte[] payload = QueryRequestPayload.encodeCreate("users", 7L, "k1", doc);
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(7L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_CREATE, header.opcode());

        final QueryRequestPayload.KeyDocBody body = QueryRequestPayload.decodeKeyDocBody(payload,
                header.bodyOffset(), false);
        assertEquals("k1", body.key());
        assertEquals(doc.toJson(), body.docJson());
        assertNull(body.modeOrNull());
    }

    @Test
    void encodeGet_roundTripsThroughDecodeHeaderAndKeyBody() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 3L, "k2");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(3L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_GET, header.opcode());

        final QueryRequestPayload.KeyBody body = QueryRequestPayload.decodeKeyBody(payload,
                header.bodyOffset());
        assertEquals("k2", body.key());
    }

    @Test
    void encodeUpdate_roundTripsThroughDecodeHeaderAndKeyDocBodyWithMode() {
        final JlsmDocument doc = sampleDoc();
        final byte[] payload = QueryRequestPayload.encodeUpdate("users", 9L, "k3", doc,
                UpdateMode.REPLACE);
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(9L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_UPDATE, header.opcode());

        final QueryRequestPayload.KeyDocBody body = QueryRequestPayload.decodeKeyDocBody(payload,
                header.bodyOffset(), true);
        assertEquals("k3", body.key());
        assertEquals(doc.toJson(), body.docJson());
        assertEquals(UpdateMode.REPLACE, body.modeOrNull());
    }

    @Test
    void encodeDelete_roundTripsThroughDecodeHeaderAndKeyBody() {
        final byte[] payload = QueryRequestPayload.encodeDelete("users", 2L, "k4");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(2L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_DELETE, header.opcode());

        final QueryRequestPayload.KeyBody body = QueryRequestPayload.decodeKeyBody(payload,
                header.bodyOffset());
        assertEquals("k4", body.key());
    }

    @Test
    void encodeRange_roundTripsThroughDecodeHeaderAndRangeBody() {
        final byte[] payload = QueryRequestPayload.encodeRange("users", 11L, "a", "z");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(11L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_RANGE, header.opcode());

        final QueryRequestPayload.RangeBody body = QueryRequestPayload.decodeRangeBody(payload,
                header.bodyOffset());
        assertEquals("a", body.fromKey());
        assertEquals("z", body.toKey());
    }

    @Test
    void encodeQuery_roundTripsThroughDecodeHeaderAndQueryBody() {
        final byte[] payload = QueryRequestPayload.encodeQuery("users", 5L, 42);
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);

        assertEquals("users", header.tableName());
        assertEquals(5L, header.partitionId());
        assertEquals(QueryRequestPayload.OP_QUERY, header.opcode());

        final QueryRequestPayload.QueryBody body = QueryRequestPayload.decodeQueryBody(payload,
                header.bodyOffset());
        assertEquals(42, body.limit());
    }

    // --- Boundary (4) ---

    @Test
    void encodeGet_singleCharTableName_roundTrips() {
        final byte[] payload = QueryRequestPayload.encodeGet("u", 1L, "k");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals("u", header.tableName());
        assertEquals(1L, header.partitionId());
    }

    @Test
    void encodeGet_partitionIdZero_roundTrips() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals(0L, header.partitionId());
    }

    @Test
    void encodeGet_partitionIdMax_roundTrips() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", Long.MAX_VALUE, "k");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals(Long.MAX_VALUE, header.partitionId());
    }

    @Test
    void encodeGet_unicodeTableName_roundTripsViaUtf8() {
        final String unicodeName = "t\u00E2ble_\u65E5\u672C";
        final byte[] payload = QueryRequestPayload.encodeGet(unicodeName, 1L, "k");
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals(unicodeName, header.tableName());
    }

    // --- Error (8) ---

    @Test
    void encodeCreate_nullTableName_throws() {
        assertThrows(NullPointerException.class,
                () -> QueryRequestPayload.encodeCreate(null, 0L, "k", sampleDoc()));
    }

    @Test
    void encodeCreate_emptyTableName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.encodeCreate("", 0L, "k", sampleDoc()));
    }

    @Test
    void encodeCreate_nullKey_throws() {
        assertThrows(NullPointerException.class,
                () -> QueryRequestPayload.encodeCreate("t", 0L, null, sampleDoc()));
    }

    @Test
    void encodeCreate_nullDoc_throws() {
        assertThrows(NullPointerException.class,
                () -> QueryRequestPayload.encodeCreate("t", 0L, "k", null));
    }

    @Test
    void encodeUpdate_nullMode_throws() {
        assertThrows(NullPointerException.class,
                () -> QueryRequestPayload.encodeUpdate("t", 0L, "k", sampleDoc(), null));
    }

    @Test
    void decodeHeader_truncatedPayload_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.decodeHeader(new byte[]{ 0, 0, 0, 1 }));
    }

    @Test
    void decodeHeader_negativeTableNameLen_throws() {
        // Craft a buffer with a negative tableNameLen field (-1).
        final ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 1);
        buf.putInt(-1);
        buf.putLong(0L);
        buf.put((byte) 1);
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.decodeHeader(buf.array()));
    }

    @Test
    void decodeKeyBody_offsetPastEnd_throws() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k");
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.decodeKeyBody(payload, payload.length + 1));
    }

    // --- Structural (2) ---

    @Test
    void encodeCreate_bodyOffsetEqualsHeaderSize() {
        // Header layout: [4-byte tableNameLen][tableName bytes][8-byte partition id][1-byte opcode]
        // For tableName "users" (5 UTF-8 bytes) and any partition id, bodyOffset = 4+5+8+1 = 18.
        final byte[] payload = QueryRequestPayload.encodeCreate("users", 42L, "key", sampleDoc());
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        final int expected = 4 + "users".getBytes(StandardCharsets.UTF_8).length + 8 + 1;
        assertEquals(expected, header.bodyOffset());
    }

    @Test
    void allOpcodes_distinctInHeaderRoundTrip() {
        final byte[] create = QueryRequestPayload.encodeCreate("t", 0L, "k", sampleDoc());
        final byte[] get = QueryRequestPayload.encodeGet("t", 0L, "k");
        final byte[] update = QueryRequestPayload.encodeUpdate("t", 0L, "k", sampleDoc(),
                UpdateMode.REPLACE);
        final byte[] delete = QueryRequestPayload.encodeDelete("t", 0L, "k");
        final byte[] range = QueryRequestPayload.encodeRange("t", 0L, "a", "z");
        final byte[] query = QueryRequestPayload.encodeQuery("t", 0L, 1);

        assertEquals(QueryRequestPayload.OP_CREATE,
                QueryRequestPayload.decodeHeader(create).opcode());
        assertEquals(QueryRequestPayload.OP_GET, QueryRequestPayload.decodeHeader(get).opcode());
        assertEquals(QueryRequestPayload.OP_UPDATE,
                QueryRequestPayload.decodeHeader(update).opcode());
        assertEquals(QueryRequestPayload.OP_DELETE,
                QueryRequestPayload.decodeHeader(delete).opcode());
        assertEquals(QueryRequestPayload.OP_RANGE,
                QueryRequestPayload.decodeHeader(range).opcode());
        assertEquals(QueryRequestPayload.OP_QUERY,
                QueryRequestPayload.decodeHeader(query).opcode());
    }

    // --- Adversarial (hardening-cycle 1) ---

    // Finding: H-CB-1
    // Bug: decodeHeader may NPE on a null payload array without a descriptive message, or
    // worse, produce an AssertionError under -ea rather than a runtime NPE.
    // Correct behavior: decodeHeader(null) throws NullPointerException (runtime check).
    // Fix location: QueryRequestPayload.decodeHeader
    // Regression watch: non-null but empty/short payloads still raise IllegalArgumentException.
    @Test
    void decodeHeader_nullPayload_throwsNpe() {
        assertThrows(NullPointerException.class, () -> QueryRequestPayload.decodeHeader(null));
    }

    // Finding: H-CB-3
    // Bug: decodeHeader may silently accept an opcode of 0 (outside the {1..6} opcode set),
    // deferring detection to the handler and allowing format drift.
    // Correct behavior: decodeHeader rejects any opcode outside {1..6} with
    // IllegalArgumentException.
    // Fix location: QueryRequestPayload.decodeHeader
    // Regression watch: valid opcodes 1..6 continue to decode without throwing.
    @Test
    void decodeHeader_opcodeZero_throwsIae() {
        final byte[] tableNameBytes = "users".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(4 + tableNameBytes.length + 8 + 1);
        buf.putInt(tableNameBytes.length);
        buf.put(tableNameBytes);
        buf.putLong(0L);
        buf.put((byte) 0);
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.decodeHeader(buf.array()));
    }

    // Finding: H-DR-3
    // Bug: decodeHeader may treat the opcode byte as unsigned and miss negative signed-byte
    // values (e.g. 0xFF sign-extends to -1), allowing invalid opcodes through.
    // Correct behavior: decodeHeader rejects 0xFF/-1 opcode byte with IllegalArgumentException.
    // Fix location: QueryRequestPayload.decodeHeader
    // Regression watch: legitimate opcodes 1..6 still decode.
    @Test
    void decodeHeader_opcodeNegativeByte_throwsIae() {
        final byte[] tableNameBytes = "users".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(4 + tableNameBytes.length + 8 + 1);
        buf.putInt(tableNameBytes.length);
        buf.put(tableNameBytes);
        buf.putLong(0L);
        buf.put((byte) -1);
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.decodeHeader(buf.array()));
    }

    // Finding: H-DT-4
    // Bug: encodeQuery may accept non-positive limits, deferring validation to the handler
    // or allowing meaningless queries to cross the wire.
    // Correct behavior: encodeQuery throws IllegalArgumentException when limit <= 0.
    // Fix location: QueryRequestPayload.encodeQuery
    // Regression watch: positive limits continue to encode successfully.
    @Test
    void encodeQuery_nonPositiveLimit_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.encodeQuery("t", 0L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequestPayload.encodeQuery("t", 0L, -1));
    }

    // Finding: H-DT-2
    // Bug: encoders may soft-cap tableName lengths below ByteBuffer.allocate(int) bounds,
    // rejecting legitimate 64KB table names that fit within the int-sized capacity.
    // Correct behavior: A 64KB (65_536 UTF-8 byte) tableName round-trips through encode +
    // decodeHeader.
    // Fix location: QueryRequestPayload encoders + decodeHeader
    // Regression watch: short names still round-trip; no behavior change for ASCII.
    @Test
    void encodeCreate_largeTableName_roundTrips() {
        final char[] chars = new char[65_536];
        java.util.Arrays.fill(chars, 'a');
        final String largeName = new String(chars);
        final byte[] payload = QueryRequestPayload.encodeCreate(largeName, 0L, "k", sampleDoc());
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(payload);
        assertEquals(largeName, header.tableName());
        assertEquals(65_536, largeName.getBytes(StandardCharsets.UTF_8).length);
    }

    // Finding: H-DT-5
    // Bug: decoders may reject payloads with trailing bytes past the expected body end,
    // breaking forward-compatible extension and strictness assumptions.
    // Correct behavior: decodeHeader and decodeKeyBody tolerate trailing bytes — lax decoding.
    // Fix location: QueryRequestPayload decoders
    // Regression watch: truncated payloads still throw IllegalArgumentException.
    @Test
    void decodeHeader_trailingBytesIgnored() {
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "k");
        final byte[] extended = new byte[payload.length + 16];
        System.arraycopy(payload, 0, extended, 0, payload.length);
        // Fill trailing bytes with arbitrary non-zero values.
        for (int i = payload.length; i < extended.length; i++) {
            extended[i] = (byte) (i & 0x7F);
        }
        final QueryRequestPayload.Header header = QueryRequestPayload.decodeHeader(extended);
        assertEquals("users", header.tableName());
        assertEquals(0L, header.partitionId());
        final QueryRequestPayload.KeyBody body = QueryRequestPayload.decodeKeyBody(extended,
                header.bodyOffset());
        assertEquals("k", body.key());
    }
}

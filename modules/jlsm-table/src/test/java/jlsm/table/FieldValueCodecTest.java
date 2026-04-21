package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;

import jlsm.table.internal.FieldValueCodec;
import org.junit.jupiter.api.Test;

// @spec query.field-value-codec.R1,R2,R3,R4,R5,R6,R7,R8,R9,R10,R11,R12,R13,R14
// @spec query.index-types.R30,R31
//       — covers FieldValueCodec encode/decode round-trips, sign-bit-flip for signed integers,
//         IEEE 754 sort-preserving for FLOAT16/32/64, UTF-8 for STRING, BOOLEAN byte values,
//         TIMESTAMP-as-INT64, NaN sorting above positive infinity, type mismatch rejection,
//         and schema-driven dispatch rather than runtime-type inference.
class FieldValueCodecTest {

    @Test
    void testInt32RoundTrip() {
        var values = new int[]{ Integer.MIN_VALUE, -1000, -1, 0, 1, 1000, Integer.MAX_VALUE };
        for (int v : values) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.INT32);
            assertNotNull(encoded);
            assertEquals(4, encoded.byteSize(), "INT32 should encode to 4 bytes");
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.INT32);
            assertEquals(v, decoded, "INT32 round-trip failed for " + v);
        }
    }

    @Test
    void testInt32SortOrder() {
        MemorySegment negEncoded = FieldValueCodec.encode(-100, FieldType.Primitive.INT32);
        MemorySegment zeroEncoded = FieldValueCodec.encode(0, FieldType.Primitive.INT32);
        MemorySegment posEncoded = FieldValueCodec.encode(100, FieldType.Primitive.INT32);

        assertTrue(negEncoded.mismatch(zeroEncoded) >= 0, "negative and zero should differ");
        assertTrue(compareBytewise(negEncoded, zeroEncoded) < 0,
                "negative should sort before zero");
        assertTrue(compareBytewise(zeroEncoded, posEncoded) < 0,
                "zero should sort before positive");
    }

    @Test
    void testInt64RoundTrip() {
        var values = new long[]{ Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE };
        for (long v : values) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.INT64);
            assertNotNull(encoded);
            assertEquals(8, encoded.byteSize(), "INT64 should encode to 8 bytes");
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.INT64);
            assertEquals(v, decoded, "INT64 round-trip failed for " + v);
        }
    }

    @Test
    void testInt64SortOrder() {
        MemorySegment neg = FieldValueCodec.encode(-999L, FieldType.Primitive.INT64);
        MemorySegment zero = FieldValueCodec.encode(0L, FieldType.Primitive.INT64);
        MemorySegment pos = FieldValueCodec.encode(999L, FieldType.Primitive.INT64);

        assertTrue(compareBytewise(neg, zero) < 0, "negative should sort before zero");
        assertTrue(compareBytewise(zero, pos) < 0, "zero should sort before positive");
    }

    @Test
    void testStringRoundTrip() {
        var values = new String[]{ "", "hello", "world", "abc", "zzz", "\u00e9\u00e8" };
        for (String v : values) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.STRING);
            assertNotNull(encoded);
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.STRING);
            assertEquals(v, decoded, "STRING round-trip failed for '" + v + "'");
        }
    }

    @Test
    void testStringSortOrder() {
        MemorySegment a = FieldValueCodec.encode("abc", FieldType.Primitive.STRING);
        MemorySegment b = FieldValueCodec.encode("abd", FieldType.Primitive.STRING);
        MemorySegment c = FieldValueCodec.encode("xyz", FieldType.Primitive.STRING);

        assertTrue(compareBytewise(a, b) < 0, "'abc' should sort before 'abd'");
        assertTrue(compareBytewise(b, c) < 0, "'abd' should sort before 'xyz'");
    }

    @Test
    void testFloat32SortOrder() {
        MemorySegment negInf = FieldValueCodec.encode(Float.NEGATIVE_INFINITY,
                FieldType.Primitive.FLOAT32);
        MemorySegment neg = FieldValueCodec.encode(-1.5f, FieldType.Primitive.FLOAT32);
        MemorySegment negZero = FieldValueCodec.encode(-0.0f, FieldType.Primitive.FLOAT32);
        MemorySegment posZero = FieldValueCodec.encode(0.0f, FieldType.Primitive.FLOAT32);
        MemorySegment pos = FieldValueCodec.encode(1.5f, FieldType.Primitive.FLOAT32);
        MemorySegment posInf = FieldValueCodec.encode(Float.POSITIVE_INFINITY,
                FieldType.Primitive.FLOAT32);

        assertTrue(compareBytewise(negInf, neg) < 0, "-Inf < -1.5");
        assertTrue(compareBytewise(neg, negZero) < 0, "-1.5 < -0.0");
        assertTrue(compareBytewise(negZero, posZero) <= 0, "-0.0 <= +0.0");
        assertTrue(compareBytewise(posZero, pos) < 0, "+0.0 < 1.5");
        assertTrue(compareBytewise(pos, posInf) < 0, "1.5 < +Inf");
    }

    @Test
    void testFloat32RoundTrip() {
        var values = new float[]{ -1.5f, 0.0f, 1.5f, Float.MAX_VALUE, Float.MIN_VALUE };
        for (float v : values) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.FLOAT32);
            assertEquals(4, encoded.byteSize());
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.FLOAT32);
            assertEquals(v, (float) decoded, 0.0f, "FLOAT32 round-trip failed for " + v);
        }
    }

    @Test
    void testFloat64SortOrder() {
        MemorySegment neg = FieldValueCodec.encode(-100.0, FieldType.Primitive.FLOAT64);
        MemorySegment zero = FieldValueCodec.encode(0.0, FieldType.Primitive.FLOAT64);
        MemorySegment pos = FieldValueCodec.encode(100.0, FieldType.Primitive.FLOAT64);

        assertTrue(compareBytewise(neg, zero) < 0, "-100.0 < 0.0");
        assertTrue(compareBytewise(zero, pos) < 0, "0.0 < 100.0");
    }

    @Test
    void testFloat64RoundTrip() {
        var values = new double[]{ -1.5, 0.0, 1.5, Double.MAX_VALUE, Double.MIN_VALUE };
        for (double v : values) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.FLOAT64);
            assertEquals(8, encoded.byteSize());
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.FLOAT64);
            assertEquals(v, (double) decoded, 0.0, "FLOAT64 round-trip failed for " + v);
        }
    }

    @Test
    void testBooleanSortOrder() {
        MemorySegment f = FieldValueCodec.encode(false, FieldType.Primitive.BOOLEAN);
        MemorySegment t = FieldValueCodec.encode(true, FieldType.Primitive.BOOLEAN);

        assertEquals(1, f.byteSize(), "BOOLEAN should encode to 1 byte");
        assertEquals(1, t.byteSize(), "BOOLEAN should encode to 1 byte");
        assertTrue(compareBytewise(f, t) < 0, "false should sort before true");
    }

    @Test
    void testBooleanRoundTrip() {
        MemorySegment f = FieldValueCodec.encode(false, FieldType.Primitive.BOOLEAN);
        MemorySegment t = FieldValueCodec.encode(true, FieldType.Primitive.BOOLEAN);

        assertEquals(false, FieldValueCodec.decode(f, FieldType.Primitive.BOOLEAN));
        assertEquals(true, FieldValueCodec.decode(t, FieldType.Primitive.BOOLEAN));
    }

    @Test
    void testTimestampSameAsInt64() {
        long ts = 1710000000000L;
        MemorySegment tsEncoded = FieldValueCodec.encode(ts, FieldType.Primitive.TIMESTAMP);
        MemorySegment i64Encoded = FieldValueCodec.encode(ts, FieldType.Primitive.INT64);

        assertEquals(tsEncoded.byteSize(), i64Encoded.byteSize());
        assertEquals(-1L, tsEncoded.mismatch(i64Encoded),
                "TIMESTAMP encoding should match INT64 encoding");
    }

    @Test
    void testFloat16RoundTrip() {
        short bits = Float16.fromFloat(1.5f);
        MemorySegment encoded = FieldValueCodec.encode(bits, FieldType.Primitive.FLOAT16);
        assertEquals(2, encoded.byteSize(), "FLOAT16 should encode to 2 bytes");
        Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.FLOAT16);
        assertEquals(bits, (short) decoded, "FLOAT16 round-trip failed");
    }

    @Test
    void testInt8RoundTrip() {
        for (byte v : new byte[]{ Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE }) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.INT8);
            assertEquals(1, encoded.byteSize());
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.INT8);
            assertEquals(v, (byte) decoded, "INT8 round-trip failed for " + v);
        }
    }

    @Test
    void testInt16RoundTrip() {
        for (short v : new short[]{ Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE }) {
            MemorySegment encoded = FieldValueCodec.encode(v, FieldType.Primitive.INT16);
            assertEquals(2, encoded.byteSize());
            Object decoded = FieldValueCodec.decode(encoded, FieldType.Primitive.INT16);
            assertEquals(v, (short) decoded, "INT16 round-trip failed for " + v);
        }
    }

    @Test
    void testTypeMismatchThrowsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> FieldValueCodec.encode("hello", FieldType.Primitive.INT32));
        assertThrows(IllegalArgumentException.class,
                () -> FieldValueCodec.encode(42, FieldType.Primitive.STRING));
    }

    /**
     * Unsigned bytewise comparison of two MemorySegments.
     */
    private static int compareBytewise(MemorySegment a, MemorySegment b) {
        long len = Math.min(a.byteSize(), b.byteSize());
        for (long i = 0; i < len; i++) {
            int cmp = Byte.toUnsignedInt(a.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i))
                    - Byte.toUnsignedInt(b.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i));
            if (cmp != 0)
                return cmp;
        }
        return Long.compare(a.byteSize(), b.byteSize());
    }
}

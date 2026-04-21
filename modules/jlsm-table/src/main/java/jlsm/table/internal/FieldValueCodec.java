package jlsm.table.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jlsm.table.FieldType;

/**
 * Encodes field values into sort-preserving binary form for secondary index keys.
 *
 * <p>
 * Encoding rules (sort-preserving):
 * <ul>
 * <li>INT8/INT16/INT32/INT64: sign-bit-flipped big-endian</li>
 * <li>FLOAT32/FLOAT64: IEEE 754 sort-preserving encoding</li>
 * <li>FLOAT16: sign-bit-flipped big-endian on the 2-byte representation</li>
 * <li>STRING: UTF-8 bytes</li>
 * <li>BOOLEAN: 0x00 for false, 0x01 for true</li>
 * <li>TIMESTAMP: same as INT64 (epoch millis)</li>
 * </ul>
 */
public final class FieldValueCodec {

    private static final ValueLayout.OfByte BE_BYTE = ValueLayout.JAVA_BYTE
            .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort BE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED
            .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED
            .withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED
            .withOrder(ByteOrder.BIG_ENDIAN);

    private FieldValueCodec() {
    }

    private static void requireByteSize(MemorySegment encoded, long expected, String typeName) {
        if (encoded.byteSize() != expected) {
            throw new IllegalArgumentException(
                    typeName + " expects " + expected + " bytes, got: " + encoded.byteSize());
        }
    }

    // @spec query.field-value-codec.R1 — encode returns sort-preserving MemorySegment for non-null
    // value + FieldType
    // @spec query.field-value-codec.R2 — reject null fieldType with NPE
    // @spec query.field-value-codec.R3 — reject null value with NPE
    // @spec query.field-value-codec.R4 — reject non-primitive, non-BoundedString field types with
    // IAE
    // @spec query.index-types.R31 — encoding dispatches on FieldType parameter, not runtime value
    // type
    public static MemorySegment encode(Object value, FieldType fieldType) {
        Objects.requireNonNull(fieldType, "fieldType");
        final FieldType.Primitive p;
        if (fieldType instanceof FieldType.Primitive prim) {
            p = prim;
        } else if (fieldType instanceof FieldType.BoundedString) {
            p = FieldType.Primitive.STRING;
        } else {
            throw new IllegalArgumentException(
                    "Only primitive field types can be encoded, got: " + fieldType);
        }
        Objects.requireNonNull(value, "value");
        return switch (p) {
            case INT8 -> encodeInt8(value);
            case INT16 -> encodeInt16(value);
            case INT32 -> encodeInt32(value);
            case INT64 -> encodeInt64(value);
            case FLOAT16 -> encodeFloat16(value);
            case FLOAT32 -> encodeFloat32(value);
            case FLOAT64 -> encodeFloat64(value);
            case STRING -> encodeString(value);
            case BOOLEAN -> encodeBoolean(value);
            case TIMESTAMP -> encodeTimestamp(value);
        };
    }

    // @spec query.field-value-codec.R12 — decode is inverse of encode for all supported types
    // (round-trip equality)
    public static Object decode(MemorySegment encoded, FieldType fieldType) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(fieldType, "fieldType");
        final FieldType.Primitive p;
        if (fieldType instanceof FieldType.Primitive prim) {
            p = prim;
        } else if (fieldType instanceof FieldType.BoundedString) {
            p = FieldType.Primitive.STRING;
        } else {
            throw new IllegalArgumentException(
                    "Only primitive field types can be decoded, got: " + fieldType);
        }
        return switch (p) {
            case INT8 -> decodeInt8(encoded);
            case INT16 -> decodeInt16(encoded);
            case INT32 -> decodeInt32(encoded);
            case INT64 -> decodeInt64(encoded);
            case FLOAT16 -> decodeFloat16(encoded);
            case FLOAT32 -> decodeFloat32(encoded);
            case FLOAT64 -> decodeFloat64(encoded);
            case STRING -> decodeString(encoded);
            case BOOLEAN -> decodeBoolean(encoded);
            case TIMESTAMP -> decodeTimestamp(encoded);
        };
    }

    // ── INT8 ────────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R5 — INT8 sign-bit-flip; reject non-Byte values with IAE
    // @spec query.index-types.R30 — INT8 sign-bit-flip; reject non-Byte values with IAE
    private static MemorySegment encodeInt8(Object value) {
        if (!(value instanceof Byte b)) {
            throw new IllegalArgumentException(
                    "INT8 requires Byte, got: " + value.getClass().getSimpleName());
        }
        MemorySegment seg = Arena.ofAuto().allocate(1);
        seg.set(BE_BYTE, 0, (byte) (b ^ Byte.MIN_VALUE));
        return seg;
    }

    private static byte decodeInt8(MemorySegment encoded) {
        requireByteSize(encoded, 1, "INT8");
        return (byte) (encoded.get(BE_BYTE, 0) ^ Byte.MIN_VALUE);
    }

    // ── INT16 ───────────────────────────────────────────────────────────

    private static MemorySegment encodeInt16(Object value) {
        if (!(value instanceof Short s)) {
            throw new IllegalArgumentException(
                    "INT16 requires Short, got: " + value.getClass().getSimpleName());
        }
        MemorySegment seg = Arena.ofAuto().allocate(2);
        seg.set(BE_SHORT, 0, (short) (s ^ Short.MIN_VALUE));
        return seg;
    }

    private static short decodeInt16(MemorySegment encoded) {
        requireByteSize(encoded, 2, "INT16");
        return (short) (encoded.get(BE_SHORT, 0) ^ Short.MIN_VALUE);
    }

    // ── INT32 ───────────────────────────────────────────────────────────

    private static MemorySegment encodeInt32(Object value) {
        if (!(value instanceof Integer i)) {
            throw new IllegalArgumentException(
                    "INT32 requires Integer, got: " + value.getClass().getSimpleName());
        }
        MemorySegment seg = Arena.ofAuto().allocate(4);
        seg.set(BE_INT, 0, i ^ Integer.MIN_VALUE);
        return seg;
    }

    private static int decodeInt32(MemorySegment encoded) {
        requireByteSize(encoded, 4, "INT32");
        return encoded.get(BE_INT, 0) ^ Integer.MIN_VALUE;
    }

    // ── INT64 ───────────────────────────────────────────────────────────

    private static MemorySegment encodeInt64(Object value) {
        if (!(value instanceof Long l)) {
            throw new IllegalArgumentException(
                    "INT64 requires Long, got: " + value.getClass().getSimpleName());
        }
        return encodeInt64Raw(l);
    }

    private static MemorySegment encodeInt64Raw(long l) {
        MemorySegment seg = Arena.ofAuto().allocate(8);
        seg.set(BE_LONG, 0, l ^ Long.MIN_VALUE);
        return seg;
    }

    private static long decodeInt64(MemorySegment encoded) {
        requireByteSize(encoded, 8, "INT64");
        return encoded.get(BE_LONG, 0) ^ Long.MIN_VALUE;
    }

    // ── FLOAT16 ─────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R8 — FLOAT16 IEEE 754 sort-preserving (invert all bits if sign
    // bit set;
    // @spec query.index-types.R30 — FLOAT16 IEEE 754 sort-preserving (invert all bits if sign bit
    // set;
    // else set sign bit); reject non-Short raw-bits values with IAE
    private static MemorySegment encodeFloat16(Object value) {
        if (!(value instanceof Short s)) {
            throw new IllegalArgumentException(
                    "FLOAT16 requires Short (raw bits), got: " + value.getClass().getSimpleName());
        }
        MemorySegment seg = Arena.ofAuto().allocate(2);
        int bits = s & 0xFFFF;
        if ((bits & 0x8000) != 0) {
            bits = ~bits & 0xFFFF;
        } else {
            bits = bits | 0x8000;
        }
        seg.set(BE_SHORT, 0, (short) bits);
        return seg;
    }

    private static short decodeFloat16(MemorySegment encoded) {
        requireByteSize(encoded, 2, "FLOAT16");
        int bits = encoded.get(BE_SHORT, 0) & 0xFFFF;
        if ((bits & 0x8000) != 0) {
            bits = bits & 0x7FFF;
        } else {
            bits = ~bits & 0xFFFF;
        }
        return (short) bits;
    }

    // ── FLOAT32 ─────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R6,R13 — FLOAT32 IEEE 754 sort-preserving; NaN sorts above
    // +Inf; reject
    // @spec query.index-types.R30 — FLOAT32 IEEE 754 sort-preserving; NaN sorts above +Inf; reject
    // non-Float IAE
    private static MemorySegment encodeFloat32(Object value) {
        if (!(value instanceof Float f)) {
            throw new IllegalArgumentException(
                    "FLOAT32 requires Float, got: " + value.getClass().getSimpleName());
        }
        int bits = Float.floatToRawIntBits(f);
        if (bits < 0) {
            bits = ~bits;
        } else {
            bits = bits | Integer.MIN_VALUE;
        }
        MemorySegment seg = Arena.ofAuto().allocate(4);
        seg.set(BE_INT, 0, bits);
        return seg;
    }

    private static float decodeFloat32(MemorySegment encoded) {
        requireByteSize(encoded, 4, "FLOAT32");
        int bits = encoded.get(BE_INT, 0);
        if (bits < 0) {
            bits = bits & Integer.MAX_VALUE;
        } else {
            bits = ~bits;
        }
        return Float.intBitsToFloat(bits);
    }

    // ── FLOAT64 ─────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R7,R14 — FLOAT64 IEEE 754 sort-preserving; NaN sorts above
    // +Inf; reject
    // @spec query.index-types.R30 — FLOAT64 IEEE 754 sort-preserving; NaN sorts above +Inf; reject
    // non-Double IAE
    private static MemorySegment encodeFloat64(Object value) {
        if (!(value instanceof Double d)) {
            throw new IllegalArgumentException(
                    "FLOAT64 requires Double, got: " + value.getClass().getSimpleName());
        }
        long bits = Double.doubleToRawLongBits(d);
        if (bits < 0) {
            bits = ~bits;
        } else {
            bits = bits | Long.MIN_VALUE;
        }
        MemorySegment seg = Arena.ofAuto().allocate(8);
        seg.set(BE_LONG, 0, bits);
        return seg;
    }

    private static double decodeFloat64(MemorySegment encoded) {
        requireByteSize(encoded, 8, "FLOAT64");
        long bits = encoded.get(BE_LONG, 0);
        if (bits < 0) {
            bits = bits & Long.MAX_VALUE;
        } else {
            bits = ~bits;
        }
        return Double.longBitsToDouble(bits);
    }

    // ── STRING ──────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R9 — STRING/BoundedString raw UTF-8 bytes (no transformation);
    // reject
    // @spec query.index-types.R30 — STRING/BoundedString raw UTF-8 bytes (no transformation);
    // reject
    // non-String IAE
    private static MemorySegment encodeString(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "STRING requires String, got: " + value.getClass().getSimpleName());
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(utf8.length);
        MemorySegment.copy(utf8, 0, seg, ValueLayout.JAVA_BYTE, 0, utf8.length);
        return seg;
    }

    private static String decodeString(MemorySegment encoded) {
        byte[] bytes = encoded.toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── BOOLEAN ─────────────────────────────────────────────────────────

    // @spec query.field-value-codec.R10 — BOOLEAN 0x00 false, 0x01 true; reject non-Boolean IAE
    // @spec query.index-types.R30 — BOOLEAN 0x00 false, 0x01 true; reject non-Boolean IAE
    private static MemorySegment encodeBoolean(Object value) {
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException(
                    "BOOLEAN requires Boolean, got: " + value.getClass().getSimpleName());
        }
        MemorySegment seg = Arena.ofAuto().allocate(1);
        seg.set(BE_BYTE, 0, b ? (byte) 0x01 : (byte) 0x00);
        return seg;
    }

    private static boolean decodeBoolean(MemorySegment encoded) {
        requireByteSize(encoded, 1, "BOOLEAN");
        return encoded.get(BE_BYTE, 0) != 0;
    }

    // ── TIMESTAMP ───────────────────────────────────────────────────────

    // @spec query.field-value-codec.R11 — TIMESTAMP encodes identically to INT64 (sign-bit-flipped
    // big-endian 8
    // @spec query.index-types.R30 — TIMESTAMP encodes identically to INT64 (sign-bit-flipped
    // big-endian 8
    // bytes)
    private static MemorySegment encodeTimestamp(Object value) {
        if (!(value instanceof Long l)) {
            throw new IllegalArgumentException(
                    "TIMESTAMP requires Long, got: " + value.getClass().getSimpleName());
        }
        return encodeInt64Raw(l);
    }

    private static long decodeTimestamp(MemorySegment encoded) {
        return decodeInt64(encoded);
    }
}

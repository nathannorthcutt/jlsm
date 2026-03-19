package jlsm.table;

import jlsm.core.io.MemorySerializer;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.CiphertextValidator;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Binary serializer for {@link JlsmDocument} values.
 *
 * <p>
 * The binary format consists of a header (schema version, field count, null bitmask, optional
 * boolean bitmask) followed by serialized non-null, non-boolean field values in schema order.
 *
 * <p>
 * Schema versioning: the header stores the write-time field count. On deserialization with a newer
 * schema, only the write-time fields are decoded; additional fields in the current schema are left
 * {@code null}.
 *
 * <p>
 * Contiguous numeric arrays (INT32, INT64, FLOAT32, FLOAT64) use SIMD byte-swap acceleration via
 * {@code jdk.incubator.vector}.
 */
public final class DocumentSerializer {

    private DocumentSerializer() {
    }

    /**
     * Returns a {@link MemorySerializer} for {@link JlsmDocument} values conforming to the given
     * schema.
     *
     * @param schema the schema describing the document structure; must not be null
     * @return a MemorySerializer for JlsmDocument
     */
    public static MemorySerializer<JlsmDocument> forSchema(JlsmSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        return new SchemaSerializer(schema, /* keyHolder= */ null);
    }

    /**
     * Returns a {@link MemorySerializer} for {@link JlsmDocument} values conforming to the given
     * schema, with optional field-level encryption.
     *
     * @param schema the schema describing the document structure; must not be null
     * @param keyHolder the key holder for encryption; may be null (no encryption)
     * @return a MemorySerializer for JlsmDocument
     */
    public static MemorySerializer<JlsmDocument> forSchema(JlsmSchema schema,
            EncryptionKeyHolder keyHolder) {
        Objects.requireNonNull(schema, "schema must not be null");
        return new SchemaSerializer(schema, keyHolder);
    }

    // =========================================================================
    // Implementation
    // =========================================================================

    /** SIMD species for byte-level operations (used for byte-swap shuffles). */
    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    /** Byte-swap shuffle for INT32 / FLOAT32 (4-byte elements). */
    private static final VectorShuffle<Byte> BSWAP32;

    /** Byte-swap shuffle for INT64 / FLOAT64 (8-byte elements). */
    private static final VectorShuffle<Byte> BSWAP64;

    static {
        final int byteLen = BYTE_SPECIES.length();
        final int[] idx32 = new int[byteLen];
        final int[] idx64 = new int[byteLen];
        for (int i = 0; i + 3 < byteLen; i += 4) {
            idx32[i + 0] = i + 3;
            idx32[i + 1] = i + 2;
            idx32[i + 2] = i + 1;
            idx32[i + 3] = i + 0;
        }
        for (int i = 0; i + 7 < byteLen; i += 8) {
            idx64[i + 0] = i + 7;
            idx64[i + 1] = i + 6;
            idx64[i + 2] = i + 5;
            idx64[i + 3] = i + 4;
            idx64[i + 4] = i + 3;
            idx64[i + 5] = i + 2;
            idx64[i + 6] = i + 1;
            idx64[i + 7] = i + 0;
        }
        BSWAP32 = VectorShuffle.fromArray(BYTE_SPECIES, idx32, 0);
        BSWAP64 = VectorShuffle.fromArray(BYTE_SPECIES, idx64, 0);
    }

    // -------------------------------------------------------------------------
    // Serializer implementation
    // -------------------------------------------------------------------------

    /** Decodes a single non-boolean, non-null field from a byte array at the cursor position. */
    @FunctionalInterface
    private interface FieldDecoder {
        Object decode(byte[] buf, Cursor cursor);
    }

    private static final class SchemaSerializer implements MemorySerializer<JlsmDocument> {

        private final JlsmSchema schema;
        private final boolean[] isBoolField;
        private final int[] prefixBoolCount;
        private final int fieldCount;
        private final int boolCount;
        private final int nullMaskBytes;
        private final int boolMaskBytes;
        private final FieldDecoder[] decoders;
        private final FieldEncryptionDispatch encryptionDispatch;

        SchemaSerializer(JlsmSchema schema, EncryptionKeyHolder keyHolder) {
            this.schema = schema;

            final List<FieldDefinition> fields = schema.fields();
            this.fieldCount = fields.size();
            final FieldDefinition[] fieldArray = fields.toArray(new FieldDefinition[0]);

            // Precompute boolean field flags and prefix counts
            this.isBoolField = new boolean[fieldCount];
            this.prefixBoolCount = new int[fieldCount + 1];
            int bools = 0;
            for (int i = 0; i < fieldCount; i++) {
                prefixBoolCount[i] = bools;
                if (fieldArray[i].type() == FieldType.Primitive.BOOLEAN) {
                    isBoolField[i] = true;
                    bools++;
                }
            }
            prefixBoolCount[fieldCount] = bools;
            this.boolCount = bools;

            this.nullMaskBytes = (fieldCount + 7) / 8;
            this.boolMaskBytes = boolCount > 0 ? (boolCount + 7) / 8 : 0;

            // Build dispatch table for non-boolean field decoders
            this.decoders = new FieldDecoder[fieldCount];
            for (int i = 0; i < fieldCount; i++) {
                if (!isBoolField[i]) {
                    final FieldType type = fieldArray[i].type();
                    decoders[i] = (buf, cursor) -> decodeField(buf, cursor, type);
                }
            }

            // Build encryption dispatch (null-safe: null keyHolder means no encryption)
            this.encryptionDispatch = new FieldEncryptionDispatch(schema, keyHolder);
        }

        @Override
        public MemorySegment serialize(JlsmDocument doc) {
            Objects.requireNonNull(doc, "doc must not be null");
            assert doc.schema() == schema || doc.schema().name().equals(schema.name())
                    : "document schema mismatch";

            final List<FieldDefinition> fields = schema.fields();
            final int fieldCount = fields.size();
            final Object[] values = doc.values();
            final boolean preEnc = doc.isPreEncrypted();

            // Count boolean fields
            final int boolCount = countBoolFields(fields);

            // Header size: 2 (version) + 2 (fieldCount) + ceil(fieldCount/8) (null mask)
            // + (boolCount > 0 ? ceil(boolCount/8) : 0) (bool mask)
            final int nullMaskBytes = (fieldCount + 7) / 8;
            final int boolMaskBytes = boolCount > 0 ? (boolCount + 7) / 8 : 0;
            final int headerSize = 2 + 2 + nullMaskBytes + boolMaskBytes;

            if (preEnc) {
                // Pre-encrypted path: skip encryption, validate and write ciphertext directly.
                // For encrypted fields, values are byte[] ciphertext; validate them.
                // For unencrypted fields, measure and encode normally.
                return serializePreEncrypted(fields, fieldCount, values, boolCount, nullMaskBytes,
                        boolMaskBytes, headerSize);
            }

            // Pre-encrypt fields that have encryptors to determine actual payload size.
            // encryptedPayloads[i] holds the ciphertext for encrypted fields, null otherwise.
            final byte[][] encryptedPayloads = new byte[fieldCount][];
            boolean hasEncryptedFields = false;
            for (int i = 0; i < fieldCount; i++) {
                final Object val = values[i];
                if (val == null || isBoolField[i]) {
                    continue;
                }
                final FieldEncryptionDispatch.FieldEncryptor enc = encryptionDispatch
                        .encryptorFor(i);
                if (enc != null) {
                    // Serialize the field value to a temporary buffer, then encrypt
                    final byte[] plainBytes = serializeFieldToBytes(fields.get(i).type(), val);
                    encryptedPayloads[i] = enc.encrypt(plainBytes);
                    hasEncryptedFields = true;
                }
            }

            // Two-pass: measure then encode
            final int payloadSize;
            if (!hasEncryptedFields) {
                payloadSize = measureFields(fields, values);
            } else {
                payloadSize = measureFieldsWithEncryption(fields, values, encryptedPayloads);
            }
            final int totalSize = headerSize + payloadSize;

            final byte[] buf = new byte[totalSize];

            // Write header
            writeShortBE(buf, 0, (short) schema.version());
            writeShortBE(buf, 2, (short) fieldCount);
            // Null bitmask
            buildNullMask(fields, values, buf, 4);
            // Boolean bitmask
            if (boolCount > 0) {
                buildBoolMask(fields, values, buf, 4 + nullMaskBytes);
            }

            // Write values
            final Cursor cursor = new Cursor(buf, headerSize);
            if (!hasEncryptedFields) {
                encodeFields(fields, values, cursor);
            } else {
                encodeFieldsWithEncryption(fields, values, encryptedPayloads, cursor);
            }

            assert cursor.pos == totalSize : "cursor.pos should equal totalSize";
            return MemorySegment.ofArray(buf);
        }

        /**
         * Serializes a pre-encrypted document. Encrypted fields hold byte[] ciphertext that is
         * validated and written directly (as varint-length-prefixed blobs). Unencrypted fields are
         * encoded normally.
         */
        private MemorySegment serializePreEncrypted(List<FieldDefinition> fields, int fieldCount,
                Object[] values, int boolCount, int nullMaskBytes, int boolMaskBytes,
                int headerSize) {

            // Build the ciphertext payloads array: for encrypted fields, cast to byte[];
            // for unencrypted fields, leave null.
            final byte[][] ciphertextPayloads = new byte[fieldCount][];
            boolean hasEncrypted = false;
            for (int i = 0; i < fieldCount; i++) {
                final Object val = values[i];
                if (val == null || isBoolField[i]) {
                    continue;
                }
                final FieldDefinition fd = fields.get(i);
                if (!(fd.encryption() instanceof EncryptionSpec.None)) {
                    // Encrypted field — value should be byte[] ciphertext
                    assert val instanceof byte[]
                            : "pre-encrypted field '" + fd.name() + "' must hold byte[]";
                    final byte[] ciphertext = (byte[]) val;
                    CiphertextValidator.validate(fd, ciphertext);
                    ciphertextPayloads[i] = ciphertext;
                    hasEncrypted = true;
                }
            }

            // Measure payload
            final int payloadSize;
            if (!hasEncrypted) {
                payloadSize = measureFields(fields, values);
            } else {
                payloadSize = measureFieldsWithEncryption(fields, values, ciphertextPayloads);
            }
            final int totalSize = headerSize + payloadSize;

            final byte[] buf = new byte[totalSize];

            // Write header
            writeShortBE(buf, 0, (short) schema.version());
            writeShortBE(buf, 2, (short) fieldCount);
            buildNullMask(fields, values, buf, 4);
            if (boolCount > 0) {
                buildBoolMask(fields, values, buf, 4 + nullMaskBytes);
            }

            // Write values
            final Cursor cursor = new Cursor(buf, headerSize);
            if (!hasEncrypted) {
                encodeFields(fields, values, cursor);
            } else {
                encodeFieldsWithEncryption(fields, values, ciphertextPayloads, cursor);
            }

            assert cursor.pos == totalSize : "cursor.pos should equal totalSize";
            return MemorySegment.ofArray(buf);
        }

        @Override
        public JlsmDocument deserialize(MemorySegment segment) {
            Objects.requireNonNull(segment, "segment must not be null");

            final ByteArrayView view = extractBytes(segment);
            final byte[] buf = view.data();
            final Cursor cursor = new Cursor(buf, view.offset());

            // Read header
            final int _ = readShortBE(buf, cursor.advance(2)) & 0xFFFF;
            final int writeFieldCount = readShortBE(buf, cursor.advance(2)) & 0xFFFF;

            // We only read min(writeFieldCount, currentFieldCount) fields
            final int readCount = Math.min(writeFieldCount, fieldCount);

            // Use precomputed prefixBoolCount for O(1) lookup instead of O(n) iteration
            final int writeBoolCount = prefixBoolCount[readCount];
            final int writeNullMaskBytes = (writeFieldCount + 7) / 8;
            final int writeBoolMaskBytes = writeBoolCount > 0 ? (writeBoolCount + 7) / 8 : 0;

            final int nullMaskOffset = cursor.advance(writeNullMaskBytes);
            final int boolMaskOffset = writeBoolCount > 0 ? cursor.advance(writeBoolMaskBytes) : -1;

            // Decode values
            final Object[] values = new Object[fieldCount];
            // Fields beyond readCount remain null (new fields added in a later schema version)

            int boolIdx = 0;
            for (int i = 0; i < readCount; i++) {
                final boolean isNull = isNullBit(buf, nullMaskOffset, i);
                if (isNull) {
                    if (isBoolField[i]) {
                        boolIdx++;
                    }
                    continue;
                }
                if (isBoolField[i]) {
                    assert boolMaskOffset >= 0 : "bool bitmask offset should be set";
                    values[i] = isBoolBit(buf, boolMaskOffset, boolIdx);
                    boolIdx++;
                } else {
                    final FieldEncryptionDispatch.FieldDecryptor dec = encryptionDispatch
                            .decryptorFor(i);
                    if (dec != null) {
                        // Read length-prefixed encrypted blob, decrypt, then decode
                        final int encLen = readVarInt(buf, cursor);
                        final byte[] ciphertext = new byte[encLen];
                        System.arraycopy(buf, cursor.pos, ciphertext, 0, encLen);
                        cursor.pos += encLen;
                        final byte[] plainBytes = dec.decrypt(ciphertext);
                        final Cursor plainCursor = new Cursor(plainBytes, 0);
                        values[i] = decoders[i].decode(plainBytes, plainCursor);
                    } else {
                        values[i] = decoders[i].decode(buf, cursor);
                    }
                }
            }

            return new JlsmDocument(schema, values);
        }
    }

    // =========================================================================
    // Measure helpers (first pass)
    // =========================================================================

    private static int measureFields(List<FieldDefinition> fields, Object[] values) {
        int total = 0;
        for (int i = 0; i < fields.size(); i++) {
            final FieldDefinition fd = fields.get(i);
            final Object val = values[i];
            if (val == null) {
                continue; // null → no value bytes
            }
            total += measureField(fd.type(), val);
        }
        return total;
    }

    private static int measureField(FieldType type, Object value) {
        return switch (type) {
            case FieldType.Primitive p -> switch (p) {
                case STRING -> {
                    final byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                    yield varIntSize(utf8.length) + utf8.length;
                }
                case INT8 -> 1;
                case INT16 -> 2;
                case INT32 -> 4;
                case INT64 -> 8;
                case FLOAT16 -> 2;
                case FLOAT32 -> 4;
                case FLOAT64 -> 8;
                case BOOLEAN -> 0; // packed in bitmask
                case TIMESTAMP -> 8;
            };
            case FieldType.ArrayType at -> {
                final Object[] arr = (Object[]) value;
                int sz = varIntSize(arr.length);
                for (Object elem : arr) {
                    sz += (elem == null) ? 0 : measureField(at.elementType(), elem);
                }
                yield sz;
            }
            case FieldType.BoundedString _ -> {
                final byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                yield varIntSize(utf8.length) + utf8.length;
            }
            case FieldType.VectorType vt -> {
                final int elemBytes = vt.elementType() == FieldType.Primitive.FLOAT32 ? 4 : 2;
                yield vt.dimensions() * elemBytes;
            }
            case FieldType.ObjectType ot -> {
                final JlsmDocument sub = (JlsmDocument) value;
                final List<FieldDefinition> subFields = ot.fields();
                final int subBoolCount = countBoolFields(subFields);
                final int subNullBytes = (subFields.size() + 7) / 8;
                final int subBoolBytes = subBoolCount > 0 ? (subBoolCount + 7) / 8 : 0;
                int sz = subNullBytes + subBoolBytes;
                sz += measureFields(subFields, sub.values());
                yield sz;
            }
        };
    }

    // =========================================================================
    // Encode helpers (second pass)
    // =========================================================================

    private static void encodeFields(List<FieldDefinition> fields, Object[] values, Cursor c) {
        for (int i = 0; i < fields.size(); i++) {
            final Object val = values[i];
            if (val == null) {
                continue;
            }
            encodeField(c, fields.get(i).type(), val);
        }
    }

    /**
     * Measures payload size when some fields are pre-encrypted. Encrypted fields are stored as
     * varint-length-prefixed blobs; unencrypted fields use standard measurement.
     */
    private static int measureFieldsWithEncryption(List<FieldDefinition> fields, Object[] values,
            byte[][] encryptedPayloads) {
        int total = 0;
        for (int i = 0; i < fields.size(); i++) {
            final Object val = values[i];
            if (val == null) {
                continue; // null → no value bytes
            }
            if (encryptedPayloads[i] != null) {
                // Length-prefixed encrypted blob
                total += varIntSize(encryptedPayloads[i].length) + encryptedPayloads[i].length;
            } else {
                total += measureField(fields.get(i).type(), val);
            }
        }
        return total;
    }

    /**
     * Encodes fields with encryption support. Encrypted fields are written as
     * varint-length-prefixed blobs from the pre-encrypted payloads; unencrypted fields use the
     * standard encode path.
     */
    private static void encodeFieldsWithEncryption(List<FieldDefinition> fields, Object[] values,
            byte[][] encryptedPayloads, Cursor c) {
        for (int i = 0; i < fields.size(); i++) {
            final Object val = values[i];
            if (val == null) {
                continue;
            }
            if (encryptedPayloads[i] != null) {
                // Write length-prefixed encrypted blob
                final byte[] enc = encryptedPayloads[i];
                writeVarInt(c, enc.length);
                System.arraycopy(enc, 0, c.buf, c.pos, enc.length);
                c.pos += enc.length;
            } else {
                encodeField(c, fields.get(i).type(), val);
            }
        }
    }

    /**
     * Serializes a single field value into a fresh byte array. Used to produce the plaintext bytes
     * that will be fed to the field encryptor.
     */
    private static byte[] serializeFieldToBytes(FieldType type, Object value) {
        assert value != null : "value must not be null for serialization";
        final int size = measureField(type, value);
        final byte[] buf = new byte[size];
        final Cursor c = new Cursor(buf, 0);
        encodeField(c, type, value);
        assert c.pos == size : "encoded size mismatch: expected " + size + ", got " + c.pos;
        return buf;
    }

    private static void encodeField(Cursor c, FieldType type, Object value) {
        switch (type) {
            case FieldType.Primitive p -> {
                switch (p) {
                    case STRING -> {
                        final byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                        writeVarInt(c, utf8.length);
                        System.arraycopy(utf8, 0, c.buf, c.pos, utf8.length);
                        c.pos += utf8.length;
                    }
                    case INT8 -> c.buf[c.pos++] = (Byte) value;
                    case INT16 -> {
                        writeShortBE(c.buf, c.pos, (Short) value);
                        c.pos += 2;
                    }
                    case INT32 -> {
                        writeIntBE(c.buf, c.pos, (Integer) value);
                        c.pos += 4;
                    }
                    case INT64 -> {
                        writeLongBE(c.buf, c.pos, (Long) value);
                        c.pos += 8;
                    }
                    case FLOAT16 -> {
                        writeShortBE(c.buf, c.pos, (Short) value);
                        c.pos += 2;
                    }
                    case FLOAT32 -> {
                        writeIntBE(c.buf, c.pos, Float.floatToRawIntBits((Float) value));
                        c.pos += 4;
                    }
                    case FLOAT64 -> {
                        writeLongBE(c.buf, c.pos, Double.doubleToRawLongBits((Double) value));
                        c.pos += 8;
                    }
                    case BOOLEAN -> {
                        // Boolean values are packed in the bool bitmask; nothing here
                    }
                    case TIMESTAMP -> {
                        writeLongBE(c.buf, c.pos, (Long) value);
                        c.pos += 8;
                    }
                }
            }
            case FieldType.BoundedString _ -> {
                final byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                writeVarInt(c, utf8.length);
                System.arraycopy(utf8, 0, c.buf, c.pos, utf8.length);
                c.pos += utf8.length;
            }
            case FieldType.ArrayType at -> encodeArray(c, at.elementType(), (Object[]) value);
            case FieldType.VectorType vt -> encodeVector(c, vt, value);
            case FieldType.ObjectType ot -> encodeObject(c, ot, (JlsmDocument) value);
        }
    }

    private static void encodeArray(Cursor c, FieldType elemType, Object[] arr) {
        writeVarInt(c, arr.length);
        // SIMD-accelerated paths for contiguous numeric types
        if (elemType == FieldType.Primitive.INT32) {
            encodeInt32Array(c, arr);
        } else if (elemType == FieldType.Primitive.INT64) {
            encodeInt64Array(c, arr);
        } else if (elemType == FieldType.Primitive.FLOAT32) {
            encodeFloat32Array(c, arr);
        } else if (elemType == FieldType.Primitive.FLOAT64) {
            encodeFloat64Array(c, arr);
        } else {
            for (Object elem : arr) {
                if (elem != null) {
                    encodeField(c, elemType, elem);
                }
            }
        }
    }

    private static void encodeVector(Cursor c, FieldType.VectorType vt, Object value) {
        final int d = vt.dimensions();
        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = (float[]) value;
            assert vec.length == d : "vector length must match dimensions";
            for (int i = 0; i < d; i++) {
                writeIntBE(c.buf, c.pos, Float.floatToRawIntBits(vec[i]));
                c.pos += 4;
            }
        } else {
            // FLOAT16 — stored as short[]
            final short[] vec = (short[]) value;
            assert vec.length == d : "vector length must match dimensions";
            for (int i = 0; i < d; i++) {
                writeShortBE(c.buf, c.pos, vec[i]);
                c.pos += 2;
            }
        }
    }

    private static void encodeObject(Cursor c, FieldType.ObjectType ot, JlsmDocument sub) {
        final List<FieldDefinition> subFields = ot.fields();
        final Object[] subValues = sub.values();
        final int boolCount = countBoolFields(subFields);
        final int nullMaskBytes = (subFields.size() + 7) / 8;
        final int boolMaskBytes = boolCount > 0 ? (boolCount + 7) / 8 : 0;

        final int nullMaskStart = c.pos;
        c.pos += nullMaskBytes;
        final int boolMaskStart = boolCount > 0 ? c.pos : -1;
        if (boolCount > 0) {
            c.pos += boolMaskBytes;
        }

        buildNullMask(subFields, subValues, c.buf, nullMaskStart);
        if (boolCount > 0) {
            buildBoolMask(subFields, subValues, c.buf, boolMaskStart);
        }
        encodeFields(subFields, subValues, c);
    }

    // =========================================================================
    // SIMD encode paths
    // =========================================================================

    private static void encodeInt32Array(Cursor c, Object[] arr) {
        final int simdLen = BYTE_SPECIES.length(); // bytes per SIMD register
        // Only use SIMD if we have enough data (at least one full vector worth of 4-byte ints)
        final int simdElems = simdLen / 4; // number of int32 elements per SIMD operation
        int i = 0;
        if (arr.length >= simdElems && simdLen >= 4) {
            final int[] ints = new int[simdElems];
            while (i + simdElems <= arr.length) {
                for (int j = 0; j < simdElems; j++) {
                    ints[j] = (Integer) arr[i + j];
                }
                // Load as bytes (little-endian native), swap to big-endian
                final ByteVector bv = IntVector.fromArray(IntVector.SPECIES_PREFERRED, ints, 0)
                        .reinterpretAsBytes();
                bv.rearrange(BSWAP32).intoArray(c.buf, c.pos);
                c.pos += simdLen;
                i += simdElems;
            }
        }
        // Scalar remainder
        while (i < arr.length) {
            writeIntBE(c.buf, c.pos, (Integer) arr[i++]);
            c.pos += 4;
        }
    }

    private static void encodeInt64Array(Cursor c, Object[] arr) {
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 8;
        int i = 0;
        if (arr.length >= simdElems && simdLen >= 8) {
            final long[] longs = new long[simdElems];
            while (i + simdElems <= arr.length) {
                for (int j = 0; j < simdElems; j++) {
                    longs[j] = (Long) arr[i + j];
                }
                final ByteVector bv = LongVector.fromArray(LongVector.SPECIES_PREFERRED, longs, 0)
                        .reinterpretAsBytes();
                bv.rearrange(BSWAP64).intoArray(c.buf, c.pos);
                c.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < arr.length) {
            writeLongBE(c.buf, c.pos, (Long) arr[i++]);
            c.pos += 8;
        }
    }

    private static void encodeFloat32Array(Cursor c, Object[] arr) {
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 4;
        int i = 0;
        if (arr.length >= simdElems && simdLen >= 4) {
            final float[] floats = new float[simdElems];
            while (i + simdElems <= arr.length) {
                for (int j = 0; j < simdElems; j++) {
                    floats[j] = (Float) arr[i + j];
                }
                final ByteVector bv = FloatVector
                        .fromArray(FloatVector.SPECIES_PREFERRED, floats, 0).reinterpretAsBytes();
                bv.rearrange(BSWAP32).intoArray(c.buf, c.pos);
                c.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < arr.length) {
            writeIntBE(c.buf, c.pos, Float.floatToRawIntBits((Float) arr[i++]));
            c.pos += 4;
        }
    }

    private static void encodeFloat64Array(Cursor c, Object[] arr) {
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 8;
        int i = 0;
        if (arr.length >= simdElems && simdLen >= 8) {
            final double[] doubles = new double[simdElems];
            while (i + simdElems <= arr.length) {
                for (int j = 0; j < simdElems; j++) {
                    doubles[j] = (Double) arr[i + j];
                }
                final ByteVector bv = DoubleVector
                        .fromArray(DoubleVector.SPECIES_PREFERRED, doubles, 0).reinterpretAsBytes();
                bv.rearrange(BSWAP64).intoArray(c.buf, c.pos);
                c.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < arr.length) {
            writeLongBE(c.buf, c.pos, Double.doubleToRawLongBits((Double) arr[i++]));
            c.pos += 8;
        }
    }

    // =========================================================================
    // Decode helpers
    // =========================================================================

    private static Object decodeField(byte[] buf, Cursor cursor, FieldType type) {
        return switch (type) {
            case FieldType.Primitive p -> switch (p) {
                case STRING -> {
                    final int len = readVarInt(buf, cursor);
                    final String s = new String(buf, cursor.pos, len, StandardCharsets.UTF_8);
                    cursor.pos += len;
                    yield s;
                }
                case INT8 -> buf[cursor.pos++];
                case INT16 -> {
                    final short v = readShortBE(buf, cursor.pos);
                    cursor.pos += 2;
                    yield v;
                }
                case INT32 -> {
                    final int v = readIntBE(buf, cursor.pos);
                    cursor.pos += 4;
                    yield v;
                }
                case INT64 -> {
                    final long v = readLongBE(buf, cursor.pos);
                    cursor.pos += 8;
                    yield v;
                }
                case FLOAT16 -> {
                    final short v = readShortBE(buf, cursor.pos);
                    cursor.pos += 2;
                    yield v;
                }
                case FLOAT32 -> {
                    final float v = Float.intBitsToFloat(readIntBE(buf, cursor.pos));
                    cursor.pos += 4;
                    yield v;
                }
                case FLOAT64 -> {
                    final double v = Double.longBitsToDouble(readLongBE(buf, cursor.pos));
                    cursor.pos += 8;
                    yield v;
                }
                case BOOLEAN ->
                    throw new AssertionError("BOOLEAN should be handled via bool bitmask");
                case TIMESTAMP -> {
                    final long v = readLongBE(buf, cursor.pos);
                    cursor.pos += 8;
                    yield v;
                }
            };
            case FieldType.BoundedString _ -> {
                final int len = readVarInt(buf, cursor);
                final String s = new String(buf, cursor.pos, len, StandardCharsets.UTF_8);
                cursor.pos += len;
                yield s;
            }
            case FieldType.ArrayType at -> decodeArray(buf, cursor, at);
            case FieldType.VectorType vt -> decodeVector(buf, cursor, vt);
            case FieldType.ObjectType ot -> decodeObject(buf, cursor, ot);
        };
    }

    private static Object[] decodeArray(byte[] buf, Cursor cursor, FieldType.ArrayType at) {
        final int count = readVarInt(buf, cursor);
        // SIMD-accelerated decode for contiguous numeric types
        final FieldType elemType = at.elementType();
        if (elemType == FieldType.Primitive.INT32) {
            return decodeInt32Array(buf, cursor, count);
        } else if (elemType == FieldType.Primitive.INT64) {
            return decodeInt64Array(buf, cursor, count);
        } else if (elemType == FieldType.Primitive.FLOAT32) {
            return decodeFloat32Array(buf, cursor, count);
        } else if (elemType == FieldType.Primitive.FLOAT64) {
            return decodeFloat64Array(buf, cursor, count);
        }
        final Object[] arr = new Object[count];
        for (int i = 0; i < count; i++) {
            arr[i] = decodeField(buf, cursor, elemType);
        }
        return arr;
    }

    private static Object decodeVector(byte[] buf, Cursor cursor, FieldType.VectorType vt) {
        final int d = vt.dimensions();
        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = new float[d];
            for (int i = 0; i < d; i++) {
                vec[i] = Float.intBitsToFloat(readIntBE(buf, cursor.pos));
                cursor.pos += 4;
            }
            return vec;
        } else {
            // FLOAT16 — return as short[]
            final short[] vec = new short[d];
            for (int i = 0; i < d; i++) {
                vec[i] = readShortBE(buf, cursor.pos);
                cursor.pos += 2;
            }
            return vec;
        }
    }

    private static Object decodeObject(byte[] buf, Cursor cursor, FieldType.ObjectType ot) {
        final List<FieldDefinition> subFields = ot.fields();
        final int subFieldCount = subFields.size();
        final int subBoolCount = countBoolFields(subFields);
        final int nullMaskBytes = (subFieldCount + 7) / 8;
        final int boolMaskBytes = subBoolCount > 0 ? (subBoolCount + 7) / 8 : 0;

        final int nullMaskOffset = cursor.advance(nullMaskBytes);
        final int boolMaskOffset = subBoolCount > 0 ? cursor.advance(boolMaskBytes) : -1;

        final Object[] subValues = new Object[subFieldCount];
        int boolIdx = 0;
        for (int i = 0; i < subFieldCount; i++) {
            final FieldDefinition fd = subFields.get(i);
            final boolean isNull = isNullBit(buf, nullMaskOffset, i);
            if (isNull) {
                if (fd.type() == FieldType.Primitive.BOOLEAN) {
                    boolIdx++;
                }
                subValues[i] = null;
                continue;
            }
            if (fd.type() == FieldType.Primitive.BOOLEAN) {
                assert boolMaskOffset >= 0 : "bool bitmask offset should be set";
                subValues[i] = isBoolBit(buf, boolMaskOffset, boolIdx);
                boolIdx++;
            } else {
                subValues[i] = decodeField(buf, cursor, fd.type());
            }
        }

        // Synthesize a schema for the sub-document
        final JlsmSchema subSchema = ot.toSchema("nested", 0);
        return new JlsmDocument(subSchema, subValues);
    }

    // =========================================================================
    // SIMD decode paths
    // =========================================================================

    private static Object[] decodeInt32Array(byte[] buf, Cursor cursor, int count) {
        final Object[] result = new Object[count];
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 4;
        final int[] tmp = new int[simdElems];
        int i = 0;
        if (count >= simdElems && simdLen >= 4) {
            while (i + simdElems <= count) {
                ByteVector.fromArray(BYTE_SPECIES, buf, cursor.pos).rearrange(BSWAP32)
                        .reinterpretAsInts().intoArray(tmp, 0);
                for (int j = 0; j < simdElems; j++) {
                    result[i + j] = tmp[j];
                }
                cursor.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < count) {
            result[i++] = readIntBE(buf, cursor.pos);
            cursor.pos += 4;
        }
        return result;
    }

    private static Object[] decodeInt64Array(byte[] buf, Cursor cursor, int count) {
        final Object[] result = new Object[count];
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 8;
        final long[] tmp = new long[simdElems];
        int i = 0;
        if (count >= simdElems && simdLen >= 8) {
            while (i + simdElems <= count) {
                ByteVector.fromArray(BYTE_SPECIES, buf, cursor.pos).rearrange(BSWAP64)
                        .reinterpretAsLongs().intoArray(tmp, 0);
                for (int j = 0; j < simdElems; j++) {
                    result[i + j] = tmp[j];
                }
                cursor.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < count) {
            result[i++] = readLongBE(buf, cursor.pos);
            cursor.pos += 8;
        }
        return result;
    }

    private static Object[] decodeFloat32Array(byte[] buf, Cursor cursor, int count) {
        final Object[] result = new Object[count];
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 4;
        final float[] tmp = new float[simdElems];
        int i = 0;
        if (count >= simdElems && simdLen >= 4) {
            while (i + simdElems <= count) {
                ByteVector.fromArray(BYTE_SPECIES, buf, cursor.pos).rearrange(BSWAP32)
                        .reinterpretAsFloats().intoArray(tmp, 0);
                for (int j = 0; j < simdElems; j++) {
                    result[i + j] = tmp[j];
                }
                cursor.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < count) {
            result[i++] = Float.intBitsToFloat(readIntBE(buf, cursor.pos));
            cursor.pos += 4;
        }
        return result;
    }

    private static Object[] decodeFloat64Array(byte[] buf, Cursor cursor, int count) {
        final Object[] result = new Object[count];
        final int simdLen = BYTE_SPECIES.length();
        final int simdElems = simdLen / 8;
        final double[] tmp = new double[simdElems];
        int i = 0;
        if (count >= simdElems && simdLen >= 8) {
            while (i + simdElems <= count) {
                ByteVector.fromArray(BYTE_SPECIES, buf, cursor.pos).rearrange(BSWAP64)
                        .reinterpretAsDoubles().intoArray(tmp, 0);
                for (int j = 0; j < simdElems; j++) {
                    result[i + j] = tmp[j];
                }
                cursor.pos += simdLen;
                i += simdElems;
            }
        }
        while (i < count) {
            result[i++] = Double.longBitsToDouble(readLongBE(buf, cursor.pos));
            cursor.pos += 8;
        }
        return result;
    }

    // =========================================================================
    // Bitmask helpers
    // =========================================================================

    private static void buildNullMask(List<FieldDefinition> fields, Object[] values, byte[] buf,
            int offset) {
        for (int i = 0; i < fields.size(); i++) {
            if (values[i] == null) {
                buf[offset + i / 8] |= (byte) (1 << (i % 8));
            }
        }
    }

    private static void buildBoolMask(List<FieldDefinition> fields, Object[] values, byte[] buf,
            int offset) {
        int boolIdx = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).type() == FieldType.Primitive.BOOLEAN) {
                final Object val = values[i];
                if (val instanceof Boolean b && b) {
                    buf[offset + boolIdx / 8] |= (byte) (1 << (boolIdx % 8));
                }
                boolIdx++;
            }
        }
    }

    private static boolean isNullBit(byte[] buf, int maskOffset, int fieldIndex) {
        return (buf[maskOffset + fieldIndex / 8] & (1 << (fieldIndex % 8))) != 0;
    }

    private static boolean isBoolBit(byte[] buf, int maskOffset, int boolIndex) {
        return (buf[maskOffset + boolIndex / 8] & (1 << (boolIndex % 8))) != 0;
    }

    private static int countBoolFields(List<FieldDefinition> fields) {
        int count = 0;
        for (final FieldDefinition fd : fields) {
            if (fd.type() == FieldType.Primitive.BOOLEAN) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // VarInt (LEB128 unsigned)
    // =========================================================================

    private static int varIntSize(int value) {
        assert value >= 0 : "VarInt value must be non-negative";
        if (value < 128) {
            return 1;
        }
        if (value < 16384) {
            return 2;
        }
        if (value < 2097152) {
            return 3;
        }
        if (value < 268435456) {
            return 4;
        }
        return 5;
    }

    private static void writeVarInt(Cursor c, int value) {
        assert value >= 0 : "VarInt value must be non-negative";
        while (true) {
            if ((value & ~0x7F) == 0) {
                c.buf[c.pos++] = (byte) value;
                return;
            }
            c.buf[c.pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(byte[] buf, Cursor cursor) {
        int result = 0;
        int shift = 0;
        while (true) {
            final byte b = buf[cursor.pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            assert shift <= 35 : "VarInt overflow — corrupt data";
        }
        return result;
    }

    // =========================================================================
    // Primitive I/O
    // =========================================================================

    private static void writeShortBE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value >> 8);
        buf[offset + 1] = (byte) value;
    }

    private static short readShortBE(byte[] buf, int offset) {
        return (short) ((buf[offset] << 8) | (buf[offset + 1] & 0xFF));
    }

    private static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >> 24);
        buf[offset + 1] = (byte) (value >> 16);
        buf[offset + 2] = (byte) (value >> 8);
        buf[offset + 3] = (byte) value;
    }

    private static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24) | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }

    private static void writeLongBE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >> 56);
        buf[offset + 1] = (byte) (value >> 48);
        buf[offset + 2] = (byte) (value >> 40);
        buf[offset + 3] = (byte) (value >> 32);
        buf[offset + 4] = (byte) (value >> 24);
        buf[offset + 5] = (byte) (value >> 16);
        buf[offset + 6] = (byte) (value >> 8);
        buf[offset + 7] = (byte) value;
    }

    private static long readLongBE(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF) << 56) | ((long) (buf[offset + 1] & 0xFF) << 48)
                | ((long) (buf[offset + 2] & 0xFF) << 40) | ((long) (buf[offset + 3] & 0xFF) << 32)
                | ((long) (buf[offset + 4] & 0xFF) << 24) | ((long) (buf[offset + 5] & 0xFF) << 16)
                | ((long) (buf[offset + 6] & 0xFF) << 8) | ((long) (buf[offset + 7] & 0xFF));
    }

    // =========================================================================
    // Heap fast path
    // =========================================================================

    /** View of a byte array with an offset — enables zero-copy for heap-backed segments. */
    private record ByteArrayView(byte[] data, int offset) {
    }

    /**
     * Extracts a byte array view from a {@link MemorySegment}. For heap-backed segments (created
     * via {@code MemorySegment.ofArray(byte[])}), returns the backing array directly — zero-copy.
     * For off-heap or sliced segments, falls back to {@code toArray()}.
     */
    private static ByteArrayView extractBytes(MemorySegment segment) {
        Optional<Object> heapBase = segment.heapBase();
        if (heapBase.isPresent() && heapBase.get() instanceof byte[] data
                && segment.byteSize() == data.length) {
            // Full array — offset is 0 for MemorySegment.ofArray(byte[])
            return new ByteArrayView(data, 0);
        } else {
            byte[] data = segment.toArray(ValueLayout.JAVA_BYTE);
            return new ByteArrayView(data, 0);
        }
    }

    // =========================================================================
    // Cursor
    // =========================================================================

    /** Mutable position cursor for sequential byte-array I/O. */
    private static final class Cursor {

        final byte[] buf;
        int pos;

        Cursor(byte[] buf, int pos) {
            this.buf = buf;
            this.pos = pos;
        }

        /** Advances position by {@code n} bytes and returns the old position. */
        int advance(int n) {
            final int old = pos;
            pos += n;
            return old;
        }
    }
}

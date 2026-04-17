package jlsm.table;

import jlsm.encryption.EncryptionSpec;

import java.util.Arrays;
import java.util.Objects;

/**
 * A document stored in a {@link JlsmTable}, with values ordered according to a {@link JlsmSchema}.
 *
 * <p>
 * Values are stored in schema-field order. A {@code null} entry means the field is absent (PATCH
 * semantics). Type validation is performed eagerly at construction.
 *
 * @spec F15.R1 — no YAML methods (absent behavior)
 * @spec F15.R2 — YamlParser/YamlWriter removed (confirmed absent)
 */
public final class JlsmDocument {

    static {
        jlsm.table.internal.DocumentAccess
                .setAccessor(new jlsm.table.internal.DocumentAccess.Accessor() {
                    @Override
                    public Object[] values(JlsmDocument doc) {
                        return doc.values();
                    }

                    @Override
                    public JlsmDocument create(jlsm.table.JlsmSchema schema, Object[] values) {
                        return new JlsmDocument(schema, values);
                    }

                    @Override
                    public boolean isPreEncrypted(JlsmDocument doc) {
                        return doc.isPreEncrypted();
                    }
                });
    }

    private final JlsmSchema schema;
    private final Object[] values;
    private final boolean preEncrypted;

    /**
     * Package-private constructor used by the deserializer. Creates a non-pre-encrypted document.
     *
     * @param schema the schema describing the document structure; must not be null
     * @param values values in schema-field order; must not be null
     */
    JlsmDocument(JlsmSchema schema, Object[] values) {
        this(schema, values, false);
    }

    /**
     * Package-private constructor with explicit pre-encrypted flag.
     *
     * @param schema the schema describing the document structure; must not be null
     * @param values values in schema-field order; must not be null
     * @param preEncrypted whether the document's encrypted fields contain pre-encrypted ciphertext
     */
    JlsmDocument(JlsmSchema schema, Object[] values, boolean preEncrypted) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (values.length != schema.fields().size()) {
            throw new IllegalArgumentException("values length " + values.length
                    + " must match field count " + schema.fields().size());
        }
        this.schema = schema;
        this.values = values;
        this.preEncrypted = preEncrypted;
    }

    /**
     * Creates a {@link JlsmDocument} from alternating name/value pairs.
     *
     * <p>
     * Fields not mentioned in {@code nameValuePairs} default to {@code null}. Each value is
     * validated against the declared field type; {@code null} is always accepted (absent field).
     *
     * @param schema the schema describing valid fields; must not be null
     * @param nameValuePairs alternating {@code String name, Object value} pairs
     * @return a new JlsmDocument
     * @throws IllegalArgumentException if a field name is unknown, type is mismatched, or pairs
     *             length is odd
     */
    public static JlsmDocument of(JlsmSchema schema, Object... nameValuePairs) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(nameValuePairs, "nameValuePairs must not be null");
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "nameValuePairs must be an even number of alternating name/value entries");
        }

        final Object[] values = new Object[schema.fields().size()];
        final boolean[] assigned = new boolean[values.length];

        for (int i = 0; i < nameValuePairs.length; i += 2) {
            final Object rawName = nameValuePairs[i];
            if (!(rawName instanceof String fieldName)) {
                throw new IllegalArgumentException(
                        "nameValuePairs entry at index " + i + " must be a String field name");
            }
            final int idx = schema.fieldIndex(fieldName);
            if (idx < 0) {
                throw new IllegalArgumentException("Unknown field: '" + fieldName + "'");
            }
            if (assigned[idx]) {
                throw new IllegalArgumentException(
                        "Duplicate field name: '" + fieldName + "' appears more than once");
            }
            assigned[idx] = true;
            final Object value = nameValuePairs[i + 1];
            if (value != null) {
                validateType(fieldName, schema.fields().get(idx).type(), value);
            }
            values[idx] = defensiveCopyIfVector(schema.fields().get(idx).type(), value);
        }

        return new JlsmDocument(schema, values);
    }

    /**
     * Creates a pre-encrypted {@link JlsmDocument} from alternating name/value pairs.
     *
     * <p>
     * Fields with {@code EncryptionSpec != NONE} are expected to hold {@code byte[]} ciphertext
     * values (not the declared field type). Fields with {@code EncryptionSpec.NONE} are validated
     * normally. {@code null} is always accepted (absent field).
     *
     * @param schema the schema describing valid fields; must not be null
     * @param nameValuePairs alternating {@code String name, Object value} pairs
     * @return a new pre-encrypted JlsmDocument
     * @throws IllegalArgumentException if a field name is unknown, type is mismatched for an
     *             unencrypted field, or pairs length is odd
     */
    public static JlsmDocument preEncrypted(JlsmSchema schema, Object... nameValuePairs) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(nameValuePairs, "nameValuePairs must not be null");
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "nameValuePairs must be an even number of alternating name/value entries");
        }

        final Object[] values = new Object[schema.fields().size()];

        for (int i = 0; i < nameValuePairs.length; i += 2) {
            final Object rawName = nameValuePairs[i];
            if (!(rawName instanceof String fieldName)) {
                throw new IllegalArgumentException(
                        "nameValuePairs entry at index " + i + " must be a String field name");
            }
            final int idx = schema.fieldIndex(fieldName);
            if (idx < 0) {
                throw new IllegalArgumentException("Unknown field: '" + fieldName + "'");
            }
            final Object value = nameValuePairs[i + 1];
            if (value != null) {
                final FieldDefinition fd = schema.fields().get(idx);
                if (fd.encryption() instanceof EncryptionSpec.None) {
                    // Unencrypted field — validate type normally
                    validateType(fieldName, fd.type(), value);
                } else {
                    // Encrypted field — must be byte[] ciphertext
                    if (!(value instanceof byte[])) {
                        throw new IllegalArgumentException("Encrypted field '" + fieldName
                                + "' must be byte[], got " + value.getClass().getSimpleName());
                    }
                }
            }
            final FieldDefinition fd2 = schema.fields().get(idx);
            if (!(fd2.encryption() instanceof EncryptionSpec.None)
                    && value instanceof byte[] ciphertext) {
                // Encrypted field — defensive copy of ciphertext, not vector dispatch
                values[idx] = ciphertext.clone();
            } else {
                values[idx] = defensiveCopyIfVector(fd2.type(), value);
            }
        }

        return new JlsmDocument(schema, values, true);
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    /** Returns the schema for this document. */
    public JlsmSchema schema() {
        return schema;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof JlsmDocument other)) {
            return false;
        }
        return preEncrypted == other.preEncrypted && Objects.equals(schema, other.schema)
                && Arrays.deepEquals(values, other.values);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(schema, preEncrypted);
        result = 31 * result + Arrays.deepHashCode(values);
        return result;
    }

    /**
     * Returns {@code true} if this document was constructed via {@link #preEncrypted} and its
     * encrypted fields hold pre-encrypted ciphertext.
     */
    boolean isPreEncrypted() {
        return preEncrypted;
    }

    /**
     * Returns a defensive copy of the value array in schema-field order (package-private for
     * serializer use).
     */
    Object[] values() {
        return values.clone();
    }

    /** Returns {@code true} if the field with the given name is null/absent. */
    public boolean isNull(String field) {
        return values[requireIndex(field)] == null;
    }

    /** Returns the STRING or BoundedString value of the named field. */
    public String getString(String field) {
        final int idx = requireIndex(field);
        final FieldType actualType = schema.fields().get(idx).type();
        if (actualType != FieldType.Primitive.STRING
                && !(actualType instanceof FieldType.BoundedString)) {
            throw new IllegalArgumentException("Field '" + field + "' has type " + actualType
                    + ", not STRING or BoundedString");
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return (String) val;
    }

    /** Returns the INT8 value of the named field as a {@code byte}. */
    public byte getByte(String field) {
        return (Byte) requireValue(field, FieldType.Primitive.INT8);
    }

    /** Returns the INT16 value of the named field as a {@code short}. */
    public short getShort(String field) {
        return (Short) requireValue(field, FieldType.Primitive.INT16);
    }

    /** Returns the INT32 value of the named field as an {@code int}. */
    public int getInt(String field) {
        return (Integer) requireValue(field, FieldType.Primitive.INT32);
    }

    /**
     * Returns the INT64 or TIMESTAMP value of the named field as a {@code long}.
     *
     * <p>
     * Use {@link #getTimestamp(String)} for TIMESTAMP fields if you want self-documenting code.
     */
    public long getLong(String field) {
        final int idx = requireIndex(field);
        final FieldType type = schema.fields().get(idx).type();
        if (type != FieldType.Primitive.INT64 && type != FieldType.Primitive.TIMESTAMP) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' is not INT64 or TIMESTAMP, got " + type);
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return (Long) val;
    }

    /** Returns the raw IEEE 754 half-precision bits for the FLOAT16 named field. */
    public short getFloat16Bits(String field) {
        return (Short) requireValue(field, FieldType.Primitive.FLOAT16);
    }

    /** Returns the FLOAT32 value of the named field as a {@code float}. */
    public float getFloat(String field) {
        return (Float) requireValue(field, FieldType.Primitive.FLOAT32);
    }

    /** Returns the FLOAT64 value of the named field as a {@code double}. */
    public double getDouble(String field) {
        return (Double) requireValue(field, FieldType.Primitive.FLOAT64);
    }

    /** Returns the BOOLEAN value of the named field. */
    public boolean getBoolean(String field) {
        return (Boolean) requireValue(field, FieldType.Primitive.BOOLEAN);
    }

    /** Returns the TIMESTAMP value of the named field as a UTC epoch millisecond {@code long}. */
    public long getTimestamp(String field) {
        return (Long) requireValue(field, FieldType.Primitive.TIMESTAMP);
    }

    /** Returns the ARRAY value of the named field. */
    public Object[] getArray(String field) {
        final int idx = requireIndex(field);
        if (!(schema.fields().get(idx).type() instanceof FieldType.ArrayType)) {
            throw new IllegalArgumentException("Field '" + field + "' is not an array type");
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return ((Object[]) val).clone();
    }

    /** Returns the nested document (ObjectType) value of the named field. */
    public JlsmDocument getObject(String field) {
        final int idx = requireIndex(field);
        if (!(schema.fields().get(idx).type() instanceof FieldType.ObjectType)) {
            throw new IllegalArgumentException("Field '" + field + "' is not an object type");
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return (JlsmDocument) val;
    }

    /**
     * Returns a defensive copy of the VECTOR(FLOAT32) value of the named field as a
     * {@code float[]}.
     */
    public float[] getFloat32Vector(String field) {
        final int idx = requireIndex(field);
        final FieldType type = schema.fields().get(idx).type();
        if (!(type instanceof FieldType.VectorType vt)
                || vt.elementType() != FieldType.Primitive.FLOAT32) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' is not a VECTOR(FLOAT32), got " + type);
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return ((float[]) val).clone();
    }

    /**
     * Returns a defensive copy of the VECTOR(FLOAT16) value of the named field as a
     * {@code short[]}.
     */
    public short[] getFloat16Vector(String field) {
        final int idx = requireIndex(field);
        final FieldType type = schema.fields().get(idx).type();
        if (!(type instanceof FieldType.VectorType vt)
                || vt.elementType() != FieldType.Primitive.FLOAT16) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' is not a VECTOR(FLOAT16), got " + type);
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return ((short[]) val).clone();
    }

    // -------------------------------------------------------------------------
    // JSON serialization / deserialization
    // -------------------------------------------------------------------------

    /**
     * Serializes this document to a compact JSON string.
     *
     * @spec F15.R43 — uses jlsm-core JSON writer internally
     */
    public String toJson() {
        return jlsm.core.json.JsonWriter
                .write(jlsm.table.internal.JsonValueAdapter.toJsonValue(this));
    }

    /**
     * Serializes this document to a JSON string.
     *
     * @param pretty {@code true} for pretty-printed output (2-space indent)
     */
    public String toJson(boolean pretty) {
        return jlsm.core.json.JsonWriter
                .write(jlsm.table.internal.JsonValueAdapter.toJsonValue(this), pretty ? 2 : 0);
    }

    /**
     * Deserializes a JSON string into a {@link JlsmDocument} conforming to the given schema.
     *
     * @param json the JSON string; must not be null
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     * @throws IllegalArgumentException if the JSON is malformed or a field type does not match
     * @spec F15.R42 — uses jlsm-core JSON parser internally
     */
    public static JlsmDocument fromJson(String json, JlsmSchema schema) {
        Objects.requireNonNull(json, "json must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        try {
            final jlsm.core.json.JsonValue parsed = jlsm.core.json.JsonParser.parse(json);
            return jlsm.table.internal.JsonValueAdapter.fromJsonValue(parsed, schema);
        } catch (jlsm.core.json.JsonParseException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private int requireIndex(String field) {
        Objects.requireNonNull(field, "field must not be null");
        final int idx = schema.fieldIndex(field);
        if (idx < 0) {
            throw new IllegalArgumentException("Unknown field: '" + field + "'");
        }
        return idx;
    }

    private Object requireValue(String field, FieldType expectedType) {
        final int idx = requireIndex(field);
        final FieldType actualType = schema.fields().get(idx).type();
        if (actualType != expectedType) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' has type " + actualType + ", not " + expectedType);
        }
        final Object val = values[idx];
        if (val == null) {
            throw new NullPointerException("Field '" + field + "' is null");
        }
        return val;
    }

    /**
     * Validates that {@code value} is compatible with {@code type}.
     *
     * @param fieldName the field name (for error messages)
     * @param type the declared field type
     * @param value the non-null value to check
     * @throws IllegalArgumentException if the value type does not match
     */
    /**
     * Maximum nesting depth for ArrayType validation. Prevents StackOverflowError on pathologically
     * nested array schemas.
     */
    private static final int MAX_ARRAY_NESTING_DEPTH = 32;

    private static void validateType(String fieldName, FieldType type, Object value) {
        validateType(fieldName, type, value, 0);
    }

    private static void validateType(String fieldName, FieldType type, Object value, int depth) {
        assert fieldName != null : "fieldName must not be null";
        assert type != null : "type must not be null";
        assert value != null : "value must not be null (nulls are handled by callers)";

        switch (type) {
            case FieldType.Primitive p -> {
                switch (p) {
                    case STRING -> expect(fieldName, value, String.class, "STRING");
                    case INT8 -> expect(fieldName, value, Byte.class, "INT8");
                    case INT16 -> expect(fieldName, value, Short.class, "INT16");
                    case INT32 -> expect(fieldName, value, Integer.class, "INT32");
                    case INT64 -> expect(fieldName, value, Long.class, "INT64");
                    case FLOAT16 -> expect(fieldName, value, Short.class, "FLOAT16");
                    case FLOAT32 -> expect(fieldName, value, Float.class, "FLOAT32");
                    case FLOAT64 -> expect(fieldName, value, Double.class, "FLOAT64");
                    case BOOLEAN -> expect(fieldName, value, Boolean.class, "BOOLEAN");
                    case TIMESTAMP -> expect(fieldName, value, Long.class, "TIMESTAMP");
                }
            }
            case FieldType.ArrayType at -> {
                if (depth >= MAX_ARRAY_NESTING_DEPTH) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' exceeds maximum array nesting depth of "
                                    + MAX_ARRAY_NESTING_DEPTH);
                }
                expect(fieldName, value, Object[].class, "ARRAY");
                final Object[] arr = (Object[]) value;
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] != null) {
                        validateType(fieldName + "[" + i + "]", at.elementType(), arr[i],
                                depth + 1);
                    }
                }
            }
            case FieldType.VectorType vt -> {
                if (vt.elementType() == FieldType.Primitive.FLOAT32) {
                    expect(fieldName, value, float[].class, "VECTOR(FLOAT32)");
                    final float[] fArr = (float[]) value;
                    if (fArr.length != vt.dimensions()) {
                        throw new IllegalArgumentException("Field '" + fieldName
                                + "' expects VECTOR(FLOAT32, " + vt.dimensions() + ") but got "
                                + fArr.length + " elements");
                    }
                    for (int i = 0; i < fArr.length; i++) {
                        if (!Float.isFinite(fArr[i])) {
                            throw new IllegalArgumentException(
                                    "Field '" + fieldName + "' VECTOR(FLOAT32) element [" + i
                                            + "] is non-finite: " + fArr[i]);
                        }
                    }
                } else if (vt.elementType() == FieldType.Primitive.FLOAT16) {
                    expect(fieldName, value, short[].class, "VECTOR(FLOAT16)");
                    final short[] sArr = (short[]) value;
                    if (sArr.length != vt.dimensions()) {
                        throw new IllegalArgumentException("Field '" + fieldName
                                + "' expects VECTOR(FLOAT16, " + vt.dimensions() + ") but got "
                                + sArr.length + " elements");
                    }
                    for (int i = 0; i < sArr.length; i++) {
                        if ((sArr[i] & 0x7C00) == 0x7C00) {
                            throw new IllegalArgumentException(
                                    "Field '" + fieldName + "' VECTOR(FLOAT16) element [" + i
                                            + "] is non-finite (NaN or Infinity)");
                        }
                    }
                } else {
                    throw new AssertionError(
                            "Unsupported VectorType elementType: " + vt.elementType());
                }
            }
            case FieldType.ObjectType _ -> expect(fieldName, value, JlsmDocument.class, "OBJECT");
            case FieldType.BoundedString bs -> {
                expect(fieldName, value, String.class, "STRING");
                final int byteLen = ((String) value)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (byteLen > bs.maxLength()) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' value exceeds BoundedString maxLength "
                                    + bs.maxLength() + " (got " + byteLen + " bytes)");
                }
            }
        }
    }

    private static void expect(String fieldName, Object value, Class<?> expectedClass,
            String typeName) {
        if (!expectedClass.isInstance(value)) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects " + typeName
                    + " (" + expectedClass.getSimpleName() + "), got "
                    + value.getClass().getSimpleName());
        }
    }

    /**
     * Returns a defensive copy of the value if it is a mutable array type (vector float[]/short[],
     * or Object[] for ArrayType fields). Other types are returned as-is (immutable or not an
     * array).
     */
    private static Object defensiveCopyIfVector(FieldType type, Object value) {
        if (value == null) {
            return null;
        }
        if (type instanceof FieldType.VectorType vt) {
            if (vt.elementType() == FieldType.Primitive.FLOAT32) {
                return ((float[]) value).clone();
            } else {
                return ((short[]) value).clone();
            }
        }
        if (type instanceof FieldType.ArrayType at && value instanceof Object[] arr) {
            return deepCopyArray(at, arr);
        }
        return value;
    }

    /**
     * Deep-copies an {@code Object[]} value according to the nested {@link FieldType.ArrayType}
     * structure. Primitive leaf arrays (e.g., int[], float[]) are immutable-by-convention and not
     * cloned; only {@code Object[]} levels are cloned so that callers cannot mutate the document's
     * internal state.
     */
    private static Object[] deepCopyArray(FieldType.ArrayType arrayType, Object[] source) {
        final Object[] copy = source.clone();
        if (arrayType.elementType() instanceof FieldType.ArrayType innerType) {
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] instanceof Object[] inner) {
                    copy[i] = deepCopyArray(innerType, inner);
                }
            }
        }
        return copy;
    }
}

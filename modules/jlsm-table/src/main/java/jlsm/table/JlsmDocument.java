package jlsm.table;

import java.util.Objects;

/**
 * A document stored in a {@link JlsmTable}, with values ordered according to a {@link JlsmSchema}.
 *
 * <p>
 * Values are stored in schema-field order. A {@code null} entry means the field is absent (PATCH
 * semantics). Type validation is performed eagerly at construction.
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
                });
    }

    private final JlsmSchema schema;
    private final Object[] values;

    /**
     * Package-private constructor used by the deserializer.
     *
     * @param schema the schema describing the document structure; must not be null
     * @param values values in schema-field order; must not be null
     */
    JlsmDocument(JlsmSchema schema, Object[] values) {
        assert schema != null : "schema must not be null";
        assert values != null : "values must not be null";
        assert values.length == schema.fields().size() : "values length must match field count";
        this.schema = schema;
        this.values = values;
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
        assert nameValuePairs != null : "nameValuePairs must not be null";
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
                validateType(fieldName, schema.fields().get(idx).type(), value);
            }
            values[idx] = value;
        }

        return new JlsmDocument(schema, values);
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    /** Returns the schema for this document. */
    public JlsmSchema schema() {
        return schema;
    }

    /** Returns the raw value array in schema-field order (package-private for serializer use). */
    Object[] values() {
        return values;
    }

    /** Returns {@code true} if the field with the given name is null/absent. */
    public boolean isNull(String field) {
        return values[requireIndex(field)] == null;
    }

    /** Returns the STRING value of the named field. */
    public String getString(String field) {
        return (String) requireValue(field, FieldType.Primitive.STRING);
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
        return (Long) values[idx];
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
        return (Object[]) values[idx];
    }

    /** Returns the nested document (ObjectType) value of the named field. */
    public JlsmDocument getObject(String field) {
        final int idx = requireIndex(field);
        if (!(schema.fields().get(idx).type() instanceof FieldType.ObjectType)) {
            throw new IllegalArgumentException("Field '" + field + "' is not an object type");
        }
        return (JlsmDocument) values[idx];
    }

    // -------------------------------------------------------------------------
    // JSON / YAML stubs — filled in by later tasks
    // -------------------------------------------------------------------------

    /** Serializes this document to a compact JSON string. */
    public String toJson() {
        return new jlsm.table.internal.JsonWriter().write(this, 0);
    }

    /**
     * Serializes this document to a JSON string.
     *
     * @param pretty {@code true} for pretty-printed output
     */
    public String toJson(boolean pretty) {
        return new jlsm.table.internal.JsonWriter().write(this, pretty ? 2 : 0);
    }

    /**
     * Deserializes a JSON string into a {@link JlsmDocument} conforming to the given schema.
     *
     * @param json the JSON string; must not be null
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     */
    public static JlsmDocument fromJson(String json, JlsmSchema schema) {
        return new jlsm.table.internal.JsonParser().parse(json, schema);
    }

    /** Serializes this document to a YAML block-style string. */
    public String toYaml() {
        return new jlsm.table.internal.YamlWriter().write(this);
    }

    /**
     * Deserializes a YAML string into a {@link JlsmDocument} conforming to the given schema.
     *
     * @param yaml the YAML string; must not be null
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     */
    public static JlsmDocument fromYaml(String yaml, JlsmSchema schema) {
        return new jlsm.table.internal.YamlParser().parse(yaml, schema);
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
    private static void validateType(String fieldName, FieldType type, Object value) {
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
            case FieldType.ArrayType _ -> expect(fieldName, value, Object[].class, "ARRAY");
            case FieldType.VectorType vt -> {
                if (vt.elementType() == FieldType.Primitive.FLOAT32) {
                    expect(fieldName, value, float[].class, "VECTOR(FLOAT32)");
                    final float[] fArr = (float[]) value;
                    if (fArr.length != vt.dimensions()) {
                        throw new IllegalArgumentException("Field '" + fieldName
                                + "' expects VECTOR(FLOAT32, " + vt.dimensions() + ") but got "
                                + fArr.length + " elements");
                    }
                } else {
                    expect(fieldName, value, short[].class, "VECTOR(FLOAT16)");
                    final short[] sArr = (short[]) value;
                    if (sArr.length != vt.dimensions()) {
                        throw new IllegalArgumentException("Field '" + fieldName
                                + "' expects VECTOR(FLOAT16, " + vt.dimensions() + ") but got "
                                + sArr.length + " elements");
                    }
                }
            }
            case FieldType.ObjectType _ -> expect(fieldName, value, JlsmDocument.class, "OBJECT");
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
}

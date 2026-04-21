package jlsm.table.internal;

import jlsm.core.json.JsonArray;
import jlsm.core.json.JsonNull;
import jlsm.core.json.JsonObject;
import jlsm.core.json.JsonPrimitive;
import jlsm.core.json.JsonValue;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.Float16;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adaptation layer between {@link JsonValue} (core JSON types) and {@link JlsmDocument}
 * (schema-aware table documents).
 *
 * <p>
 * Provides bidirectional conversion:
 * <ul>
 * <li>{@link #toJsonValue(JlsmDocument)} — converts a document to a {@link JsonObject} with field
 * names as keys and typed values as JSON primitives/arrays/objects</li>
 * <li>{@link #fromJsonValue(JsonValue, JlsmSchema)} — converts a {@link JsonValue} (expected to be
 * a {@link JsonObject}) into a {@link JlsmDocument} conforming to the given schema, with the same
 * validation as {@link JlsmDocument#of}</li>
 * </ul>
 *
 * <p>
 * Conversion rules:
 * <ul>
 * <li>Schema-typed fields are narrowed to the declared type; out-of-range numeric values are
 * rejected with {@link IllegalArgumentException}</li>
 * <li>JSON null maps to absent field (null in the values array)</li>
 * <li>Type mismatches are rejected with descriptive error messages</li>
 * </ul>
 *
 * @spec serialization.simd-jsonl.R44 — type validation and range checking matching JlsmDocument.of()
 * @spec serialization.simd-jsonl.R57 — FLOAT64 rejects NaN/Infinity
 * @spec serialization.simd-jsonl.R58 — distinct overflow vs format error messages
 */
public final class JsonValueAdapter {

    /**
     * Converts a {@link JlsmDocument} to a {@link JsonObject}.
     *
     * <p>
     * Each schema field becomes a key in the resulting object. Null/absent fields are represented
     * as {@link JsonNull}. Field values are converted to the appropriate {@link JsonValue} subtype
     * based on the schema field type.
     *
     * @param document the document to convert; must not be null
     * @return a JsonObject representation of the document
     * @throws NullPointerException if document is null
     */
    public static JsonObject toJsonValue(JlsmDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        final JlsmSchema schema = document.schema();
        final Object[] values = DocumentAccess.get().values(document);

        final JsonObject.Builder builder = JsonObject.builder();
        for (int i = 0; i < schema.fields().size(); i++) {
            final FieldDefinition fd = schema.fields().get(i);
            final Object value = values[i];
            builder.put(fd.name(), convertToJsonValue(fd.type(), value));
        }
        return builder.build();
    }

    /**
     * Converts a {@link JsonValue} into a {@link JlsmDocument} conforming to the given schema.
     *
     * <p>
     * The value must be a {@link JsonObject}. Each key is matched to a schema field by name.
     * Missing keys and JSON null values both map to absent (null) fields in the document.
     *
     * @param value the JSON value; must not be null and must be a JsonObject
     * @param schema the target schema; must not be null
     * @return a new JlsmDocument
     * @throws NullPointerException if value or schema is null
     * @throws IllegalArgumentException if value is not a JsonObject, or has type mismatches
     */
    public static JlsmDocument fromJsonValue(JsonValue value, JlsmSchema schema) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(schema, "schema must not be null");

        if (!(value instanceof JsonObject obj)) {
            throw new IllegalArgumentException(
                    "Expected a JsonObject, got " + value.getClass().getSimpleName());
        }

        final Object[] values = new Object[schema.fields().size()];
        for (int i = 0; i < schema.fields().size(); i++) {
            final FieldDefinition fd = schema.fields().get(i);
            final JsonValue jv = obj.get(fd.name());
            if (jv == null || jv instanceof JsonNull) {
                values[i] = null;
            } else {
                values[i] = convertFromJsonValue(jv, fd.type(), fd.name(), schema);
            }
        }
        return DocumentAccess.get().create(schema, values);
    }

    // -------------------------------------------------------------------------
    // Document value → JsonValue
    // -------------------------------------------------------------------------

    private static JsonValue convertToJsonValue(FieldType type, Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }

        return switch (type) {
            case FieldType.Primitive p -> primitiveToJson(p, value);
            case FieldType.BoundedString _ -> JsonPrimitive.ofString((String) value);
            case FieldType.ArrayType at -> arrayToJson(at, (Object[]) value);
            case FieldType.VectorType vt -> vectorToJson(vt, value);
            case FieldType.ObjectType _ -> toJsonValue((JlsmDocument) value);
        };
    }

    private static JsonValue primitiveToJson(FieldType.Primitive p, Object value) {
        assert value != null : "value must not be null (caller handles nulls)";
        return switch (p) {
            case STRING -> JsonPrimitive.ofString((String) value);
            case INT8 -> JsonPrimitive.ofNumber(String.valueOf((Byte) value));
            case INT16 -> JsonPrimitive.ofNumber(String.valueOf((Short) value));
            case INT32 -> JsonPrimitive.ofNumber(String.valueOf((Integer) value));
            case INT64 -> JsonPrimitive.ofNumber(String.valueOf((Long) value));
            case TIMESTAMP -> JsonPrimitive.ofNumber(String.valueOf((Long) value));
            case FLOAT16 -> {
                final float f = Float16.toFloat((Short) value);
                yield Float.isFinite(f) ? JsonPrimitive.ofNumber(String.valueOf(f))
                        : JsonNull.INSTANCE;
            }
            case FLOAT32 -> {
                final float f = (Float) value;
                yield Float.isFinite(f) ? JsonPrimitive.ofNumber(String.valueOf(f))
                        : JsonNull.INSTANCE;
            }
            case FLOAT64 -> {
                final double d = (Double) value;
                yield Double.isFinite(d) ? JsonPrimitive.ofNumber(String.valueOf(d))
                        : JsonNull.INSTANCE;
            }
            case BOOLEAN -> JsonPrimitive.ofBoolean((Boolean) value);
        };
    }

    private static JsonArray arrayToJson(FieldType.ArrayType at, Object[] elements) {
        final List<JsonValue> list = new ArrayList<>(elements.length);
        for (final Object element : elements) {
            list.add(convertToJsonValue(at.elementType(), element));
        }
        return JsonArray.of(list);
    }

    // @spec vector.field-type.R49 — operates on pre-validated JlsmDocument; dimension check enforced upstream
    // by JlsmDocument.validateType at construction time
    private static JsonArray vectorToJson(FieldType.VectorType vt, Object value) {
        final List<JsonValue> list;
        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = (float[]) value;
            list = new ArrayList<>(vec.length);
            for (final float f : vec) {
                list.add(JsonPrimitive.ofNumber(String.valueOf(f)));
            }
        } else {
            assert vt.elementType() == FieldType.Primitive.FLOAT16
                    : "unsupported vector element type: " + vt.elementType();
            final short[] vec = (short[]) value;
            list = new ArrayList<>(vec.length);
            for (final short bits : vec) {
                list.add(JsonPrimitive.ofNumber(String.valueOf(Float16.toFloat(bits))));
            }
        }
        return JsonArray.of(list);
    }

    // -------------------------------------------------------------------------
    // JsonValue → Document value
    // -------------------------------------------------------------------------

    private static Object convertFromJsonValue(JsonValue jv, FieldType type, String fieldName,
            JlsmSchema parentSchema) {
        assert jv != null : "jv must not be null (caller handles nulls)";
        assert !(jv instanceof JsonNull) : "JsonNull must be handled by caller";

        return switch (type) {
            case FieldType.Primitive p -> primitiveFromJson(jv, p, fieldName);
            case FieldType.BoundedString bs -> boundedStringFromJson(jv, bs, fieldName);
            case FieldType.ArrayType at -> arrayFromJson(jv, at, fieldName, parentSchema);
            case FieldType.VectorType vt -> vectorFromJson(jv, vt, fieldName);
            case FieldType.ObjectType ot -> objectFromJson(jv, ot, fieldName, parentSchema);
        };
    }

    private static Object primitiveFromJson(JsonValue jv, FieldType.Primitive p, String fieldName) {
        return switch (p) {
            case STRING -> {
                requirePrimitiveString(jv, fieldName, "STRING");
                yield ((JsonPrimitive) jv).asString();
            }
            case BOOLEAN -> {
                requirePrimitiveBoolean(jv, fieldName, "BOOLEAN");
                yield ((JsonPrimitive) jv).asBoolean();
            }
            case INT8 -> {
                requirePrimitiveNumber(jv, fieldName, "INT8");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final int val = Integer.parseInt(text);
                    if (val < Byte.MIN_VALUE || val > Byte.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' INT8 value out of range: " + text);
                    }
                    yield (byte) val;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' INT8 value not a valid integer: " + text, e);
                }
            }
            case INT16 -> {
                requirePrimitiveNumber(jv, fieldName, "INT16");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final int val = Integer.parseInt(text);
                    if (val < Short.MIN_VALUE || val > Short.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' INT16 value out of range: " + text);
                    }
                    yield (short) val;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' INT16 value not a valid integer: " + text,
                            e);
                }
            }
            case INT32 -> {
                requirePrimitiveNumber(jv, fieldName, "INT32");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final long val = Long.parseLong(text);
                    if (val < Integer.MIN_VALUE || val > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' INT32 value out of range: " + text);
                    }
                    yield (int) val;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' INT32 value not a valid integer: " + text,
                            e);
                }
            }
            case INT64 -> {
                requirePrimitiveNumber(jv, fieldName, "INT64");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    yield Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' INT64 value out of range: " + text, e);
                }
            }
            case TIMESTAMP -> {
                requirePrimitiveNumber(jv, fieldName, "TIMESTAMP");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    yield Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' TIMESTAMP value not a valid long: " + text,
                            e);
                }
            }
            case FLOAT16 -> {
                requirePrimitiveNumber(jv, fieldName, "FLOAT16");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final float f = Float.parseFloat(text);
                    if (!Float.isFinite(f)) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' FLOAT16 value is non-finite");
                    }
                    yield Float16.fromFloat(f);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' FLOAT16 value not a valid number: " + text,
                            e);
                }
            }
            case FLOAT32 -> {
                requirePrimitiveNumber(jv, fieldName, "FLOAT32");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final float f = Float.parseFloat(text);
                    if (!Float.isFinite(f)) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' FLOAT32 value is non-finite");
                    }
                    yield f;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' FLOAT32 value not a valid number: " + text,
                            e);
                }
            }
            case FLOAT64 -> {
                requirePrimitiveNumber(jv, fieldName, "FLOAT64");
                final String text = ((JsonPrimitive) jv).asNumberText();
                try {
                    final double d = Double.parseDouble(text);
                    if (!Double.isFinite(d)) {
                        throw new IllegalArgumentException(
                                "Field '" + fieldName + "' FLOAT64 value is non-finite");
                    }
                    yield d;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' FLOAT64 value not a valid number: " + text,
                            e);
                }
            }
        };
    }

    private static String boundedStringFromJson(JsonValue jv, FieldType.BoundedString bs,
            String fieldName) {
        requirePrimitiveString(jv, fieldName, "BoundedString");
        final String s = ((JsonPrimitive) jv).asString();
        final int byteLen = s.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen > bs.maxLength()) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' value exceeds BoundedString maxLength "
                            + bs.maxLength() + " (got " + byteLen + " bytes)");
        }
        return s;
    }

    private static Object[] arrayFromJson(JsonValue jv, FieldType.ArrayType at, String fieldName,
            JlsmSchema parentSchema) {
        if (!(jv instanceof JsonArray arr)) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects ARRAY, got "
                    + jv.getClass().getSimpleName());
        }
        final Object[] result = new Object[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            final JsonValue element = arr.get(i);
            if (element instanceof JsonNull) {
                result[i] = null;
            } else {
                result[i] = convertFromJsonValue(element, at.elementType(),
                        fieldName + "[" + i + "]", parentSchema);
            }
        }
        return result;
    }

    private static Object vectorFromJson(JsonValue jv, FieldType.VectorType vt, String fieldName) {
        if (!(jv instanceof JsonArray arr)) {
            throw new IllegalArgumentException("Field '" + fieldName
                    + "' expects VECTOR array, got " + jv.getClass().getSimpleName());
        }

        if (vt.elementType() == FieldType.Primitive.FLOAT32) {
            final float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                final JsonValue element = arr.get(i);
                if (!(element instanceof JsonPrimitive ep) || !ep.isNumber()) {
                    throw new IllegalArgumentException("Field '" + fieldName + "' VECTOR element ["
                            + i + "] must be a number");
                }
                final float f = Float.parseFloat(ep.asNumberText());
                if (!Float.isFinite(f)) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' VECTOR(FLOAT32) element [" + i + "] is non-finite: " + f);
                }
                vec[i] = f;
            }
            return vec;
        } else {
            assert vt.elementType() == FieldType.Primitive.FLOAT16
                    : "unsupported vector element type: " + vt.elementType();
            final short[] vec = new short[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                final JsonValue element = arr.get(i);
                if (!(element instanceof JsonPrimitive ep) || !ep.isNumber()) {
                    throw new IllegalArgumentException("Field '" + fieldName + "' VECTOR element ["
                            + i + "] must be a number");
                }
                final float f = Float.parseFloat(ep.asNumberText());
                if (!Float.isFinite(f)) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' VECTOR(FLOAT16) element [" + i + "] is non-finite: " + f);
                }
                vec[i] = Float16.fromFloat(f);
            }
            return vec;
        }
    }

    private static JlsmDocument objectFromJson(JsonValue jv, FieldType.ObjectType ot,
            String fieldName, JlsmSchema parentSchema) {
        if (!(jv instanceof JsonObject)) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects OBJECT, got "
                    + jv.getClass().getSimpleName());
        }
        final JlsmSchema subSchema = ot.toSchema(fieldName, parentSchema.version());
        return fromJsonValue(jv, subSchema);
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private static void requirePrimitiveString(JsonValue jv, String fieldName, String typeName) {
        if (!(jv instanceof JsonPrimitive p) || !p.isString()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects " + typeName
                    + " (string), got " + describeJsonType(jv));
        }
    }

    private static void requirePrimitiveNumber(JsonValue jv, String fieldName, String typeName) {
        if (!(jv instanceof JsonPrimitive p) || !p.isNumber()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects " + typeName
                    + " (number), got " + describeJsonType(jv));
        }
    }

    private static void requirePrimitiveBoolean(JsonValue jv, String fieldName, String typeName) {
        if (!(jv instanceof JsonPrimitive p) || !p.isBoolean()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' expects " + typeName
                    + " (boolean), got " + describeJsonType(jv));
        }
    }

    private static String describeJsonType(JsonValue jv) {
        if (jv instanceof JsonPrimitive p) {
            if (p.isString())
                return "JSON string";
            if (p.isNumber())
                return "JSON number";
            if (p.isBoolean())
                return "JSON boolean";
        }
        if (jv instanceof JsonObject)
            return "JSON object";
        if (jv instanceof JsonArray)
            return "JSON array";
        if (jv instanceof JsonNull)
            return "JSON null";
        return jv.getClass().getSimpleName();
    }

    private JsonValueAdapter() {
        // Static utility class
    }
}

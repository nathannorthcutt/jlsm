package jlsm.table;

import jlsm.core.json.JsonArray;
import jlsm.core.json.JsonNull;
import jlsm.core.json.JsonObject;
import jlsm.core.json.JsonPrimitive;
import jlsm.table.internal.JsonValueAdapter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonValueAdapterTest {

    // -------------------------------------------------------------------------
    // toJsonValue tests
    // -------------------------------------------------------------------------

    @Nested
    class ToJsonValue {

        @Test
        void string_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("name", FieldType.Primitive.STRING)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("name");
            assertTrue(p.isString());
            assertEquals("Alice", p.asString());
        }

        @Test
        void boundedString_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("code", new FieldType.BoundedString(10)).build();
            JlsmDocument doc = JlsmDocument.of(schema, "code", "ABC");
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("code");
            assertTrue(p.isString());
            assertEquals("ABC", p.asString());
        }

        @Test
        void int8_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT8)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", (byte) 42);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(42, p.asInt());
        }

        @Test
        void int16_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT16)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", (short) 1234);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(1234, Integer.parseInt(p.asNumberText()));
        }

        @Test
        void int32_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT32)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", 999);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(999, p.asInt());
        }

        @Test
        void int64_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT64)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", Long.MAX_VALUE);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(Long.MAX_VALUE, p.asLong());
        }

        @Test
        void timestamp_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("ts", FieldType.Primitive.TIMESTAMP).build();
            JlsmDocument doc = JlsmDocument.of(schema, "ts", 1700000000000L);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("ts");
            assertTrue(p.isNumber());
            assertEquals(1700000000000L, p.asLong());
        }

        @Test
        void float16_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT16)
                    .build();
            short bits = Float16.fromFloat(1.5f);
            JlsmDocument doc = JlsmDocument.of(schema, "v", bits);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(1.5f, Float.parseFloat(p.asNumberText()), 0.01f);
        }

        @Test
        void float32_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT32)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", 3.14f);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(3.14f, Float.parseFloat(p.asNumberText()), 0.001f);
        }

        @Test
        void float64_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT64)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", 2.718281828);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isNumber());
            assertEquals(2.718281828, Double.parseDouble(p.asNumberText()), 0.000001);
        }

        @Test
        void float32_nonFinite_becomesNull() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("a", FieldType.Primitive.FLOAT32)
                    .field("b", FieldType.Primitive.FLOAT32).field("c", FieldType.Primitive.FLOAT32)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "a", Float.NaN, "b", Float.POSITIVE_INFINITY,
                    "c", Float.NEGATIVE_INFINITY);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            assertSame(JsonNull.INSTANCE, obj.get("a"));
            assertSame(JsonNull.INSTANCE, obj.get("b"));
            assertSame(JsonNull.INSTANCE, obj.get("c"));
        }

        @Test
        void float64_nonFinite_becomesNull() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("a", FieldType.Primitive.FLOAT64)
                    .field("b", FieldType.Primitive.FLOAT64).build();
            JlsmDocument doc = JlsmDocument.of(schema, "a", Double.NaN, "b",
                    Double.POSITIVE_INFINITY);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            assertSame(JsonNull.INSTANCE, obj.get("a"));
            assertSame(JsonNull.INSTANCE, obj.get("b"));
        }

        @Test
        void boolean_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.BOOLEAN)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema, "v", true);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonPrimitive p = (JsonPrimitive) obj.get("v");
            assertTrue(p.isBoolean());
            assertTrue(p.asBoolean());
        }

        @Test
        void null_field_becomesJsonNull() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("x", FieldType.Primitive.STRING)
                    .build();
            JlsmDocument doc = JlsmDocument.of(schema);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            assertSame(JsonNull.INSTANCE, obj.get("x"));
        }

        @Test
        void array_of_primitives() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
            JlsmDocument doc = JlsmDocument.of(schema, "nums", new Object[]{ 1, 2, 3 });
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonArray arr = (JsonArray) obj.get("nums");
            assertEquals(3, arr.size());
            assertEquals(1, ((JsonPrimitive) arr.get(0)).asInt());
            assertEquals(2, ((JsonPrimitive) arr.get(1)).asInt());
            assertEquals(3, ((JsonPrimitive) arr.get(2)).asInt());
        }

        @Test
        void nested_object() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .objectField("addr", inner -> inner.field("city", FieldType.Primitive.STRING)
                            .field("zip", FieldType.Primitive.INT32))
                    .build();
            FieldType.ObjectType objType = (FieldType.ObjectType) schema.fields().get(0).type();
            JlsmSchema addrSchema = objType.toSchema("addr", 1);
            JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "Boston", "zip", 2101);
            JlsmDocument doc = JlsmDocument.of(schema, "addr", addr);

            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonObject nested = (JsonObject) obj.get("addr");
            assertEquals("Boston", ((JsonPrimitive) nested.get("city")).asString());
            assertEquals(2101, ((JsonPrimitive) nested.get("zip")).asInt());
        }

        @Test
        void vector_float32() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();
            JlsmDocument doc = JlsmDocument.of(schema, "vec", new float[]{ 1.0f, 2.0f, 3.0f });
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonArray arr = (JsonArray) obj.get("vec");
            assertEquals(3, arr.size());
            assertEquals(1.0f, Float.parseFloat(((JsonPrimitive) arr.get(0)).asNumberText()),
                    0.001f);
        }

        @Test
        void vector_float16() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .vectorField("vec", FieldType.Primitive.FLOAT16, 2).build();
            short[] bits = { Float16.fromFloat(1.0f), Float16.fromFloat(2.0f) };
            JlsmDocument doc = JlsmDocument.of(schema, "vec", bits);
            JsonObject obj = JsonValueAdapter.toJsonValue(doc);
            JsonArray arr = (JsonArray) obj.get("vec");
            assertEquals(2, arr.size());
        }
    }

    // -------------------------------------------------------------------------
    // fromJsonValue tests
    // -------------------------------------------------------------------------

    @Nested
    class FromJsonValue {

        @Test
        void string_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("name", FieldType.Primitive.STRING)
                    .build();
            JsonObject obj = JsonObject.builder().put("name", JsonPrimitive.ofString("Alice"))
                    .build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals("Alice", doc.getString("name"));
        }

        @Test
        void boundedString_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("code", new FieldType.BoundedString(10)).build();
            JsonObject obj = JsonObject.builder().put("code", JsonPrimitive.ofString("ABC"))
                    .build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals("ABC", doc.getString("code"));
        }

        @Test
        void int8_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT8)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("42")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals((byte) 42, doc.getByte("v"));
        }

        @Test
        void int8_outOfRange_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT8)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("999")).build();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
            assertTrue(ex.getMessage().contains("v"), "error should mention field name");
        }

        @Test
        void int16_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT16)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("1234")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals((short) 1234, doc.getShort("v"));
        }

        @Test
        void int16_outOfRange_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT16)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("99999")).build();
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
        }

        @Test
        void int32_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT32)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("999")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(999, doc.getInt("v"));
        }

        @Test
        void int64_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT64)
                    .build();
            JsonObject obj = JsonObject.builder()
                    .put("v", JsonPrimitive.ofNumber(String.valueOf(Long.MAX_VALUE))).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(Long.MAX_VALUE, doc.getLong("v"));
        }

        @Test
        void timestamp_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("ts", FieldType.Primitive.TIMESTAMP).build();
            JsonObject obj = JsonObject.builder().put("ts", JsonPrimitive.ofNumber("1700000000000"))
                    .build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(1700000000000L, doc.getTimestamp("ts"));
        }

        @Test
        void float16_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT16)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("1.5")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(1.5f, Float16.toFloat(doc.getFloat16Bits("v")), 0.01f);
        }

        @Test
        void float32_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT32)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("3.14")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(3.14f, doc.getFloat("v"), 0.001f);
        }

        @Test
        void float64_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.FLOAT64)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("2.718281828"))
                    .build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals(2.718281828, doc.getDouble("v"), 0.000001);
        }

        @Test
        void boolean_field() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.BOOLEAN)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofBoolean(true)).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertTrue(doc.getBoolean("v"));
        }

        @Test
        void null_value_becomesAbsentField() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("x", FieldType.Primitive.STRING)
                    .build();
            JsonObject obj = JsonObject.builder().put("x", JsonNull.INSTANCE).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertTrue(doc.isNull("x"));
        }

        @Test
        void missing_field_becomesAbsentField() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("x", FieldType.Primitive.STRING)
                    .field("y", FieldType.Primitive.INT32).build();
            JsonObject obj = JsonObject.builder().put("y", JsonPrimitive.ofNumber("5")).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertTrue(doc.isNull("x"));
            assertEquals(5, doc.getInt("y"));
        }

        @Test
        void array_of_primitives() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
            JsonArray arr = JsonArray.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofNumber("2"),
                    JsonPrimitive.ofNumber("3"));
            JsonObject obj = JsonObject.builder().put("nums", arr).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertArrayEquals(new Object[]{ 1, 2, 3 }, doc.getArray("nums"));
        }

        @Test
        void nested_object() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .objectField("addr", inner -> inner.field("city", FieldType.Primitive.STRING)
                            .field("zip", FieldType.Primitive.INT32))
                    .build();
            JsonObject addrObj = JsonObject.builder().put("city", JsonPrimitive.ofString("Boston"))
                    .put("zip", JsonPrimitive.ofNumber("2101")).build();
            JsonObject obj = JsonObject.builder().put("addr", addrObj).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertEquals("Boston", doc.getObject("addr").getString("city"));
            assertEquals(2101, doc.getObject("addr").getInt("zip"));
        }

        @Test
        void vector_float32() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();
            JsonArray arr = JsonArray.of(JsonPrimitive.ofNumber("1.0"),
                    JsonPrimitive.ofNumber("2.0"), JsonPrimitive.ofNumber("3.0"));
            JsonObject obj = JsonObject.builder().put("vec", arr).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            assertArrayEquals(new float[]{ 1.0f, 2.0f, 3.0f }, doc.getFloat32Vector("vec"), 0.001f);
        }

        @Test
        void vector_float16() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .vectorField("vec", FieldType.Primitive.FLOAT16, 2).build();
            JsonArray arr = JsonArray.of(JsonPrimitive.ofNumber("1.0"),
                    JsonPrimitive.ofNumber("2.0"));
            JsonObject obj = JsonObject.builder().put("vec", arr).build();
            JlsmDocument doc = JsonValueAdapter.fromJsonValue(obj, schema);
            short[] vec = doc.getFloat16Vector("vec");
            assertEquals(1.0f, Float16.toFloat(vec[0]), 0.01f);
            assertEquals(2.0f, Float16.toFloat(vec[1]), 0.01f);
        }

        @Test
        void nonJsonObject_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("x", FieldType.Primitive.STRING)
                    .build();
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(JsonPrimitive.ofString("oops"), schema));
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(JsonArray.empty(), schema));
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(JsonNull.INSTANCE, schema));
        }

        @Test
        void typeMismatch_stringWhenExpectingNumber_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT32)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofString("not-a-number"))
                    .build();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
            assertTrue(ex.getMessage().contains("v"), "error should mention field name");
        }

        @Test
        void typeMismatch_numberWhenExpectingBoolean_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.BOOLEAN)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofNumber("42")).build();
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
        }

        @Test
        void typeMismatch_stringWhenExpectingBoolean_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.BOOLEAN)
                    .build();
            JsonObject obj = JsonObject.builder().put("v", JsonPrimitive.ofString("true")).build();
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
        }

        @Test
        void boundedString_exceeds_maxLength_throws() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("code", new FieldType.BoundedString(3)).build();
            JsonObject obj = JsonObject.builder().put("code", JsonPrimitive.ofString("ABCDEF"))
                    .build();
            assertThrows(IllegalArgumentException.class,
                    () -> JsonValueAdapter.fromJsonValue(obj, schema));
        }
    }

    // -------------------------------------------------------------------------
    // Round-trip: doc -> JsonValue -> doc
    // -------------------------------------------------------------------------

    @Nested
    class RoundTrip {

        @Test
        void allPrimitiveTypes() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("s", FieldType.Primitive.STRING)
                    .field("i8", FieldType.Primitive.INT8).field("i16", FieldType.Primitive.INT16)
                    .field("i32", FieldType.Primitive.INT32).field("i64", FieldType.Primitive.INT64)
                    .field("f32", FieldType.Primitive.FLOAT32)
                    .field("f64", FieldType.Primitive.FLOAT64)
                    .field("bool", FieldType.Primitive.BOOLEAN)
                    .field("ts", FieldType.Primitive.TIMESTAMP).build();
            JlsmDocument doc = JlsmDocument.of(schema, "s", "hello", "i8", (byte) 7, "i16",
                    (short) 300, "i32", 42, "i64", 100L, "f32", 1.5f, "f64", 2.718281828, "bool",
                    true, "ts", 1700000000000L);

            JsonObject jsonObj = JsonValueAdapter.toJsonValue(doc);
            JlsmDocument restored = JsonValueAdapter.fromJsonValue(jsonObj, schema);

            assertEquals("hello", restored.getString("s"));
            assertEquals((byte) 7, restored.getByte("i8"));
            assertEquals((short) 300, restored.getShort("i16"));
            assertEquals(42, restored.getInt("i32"));
            assertEquals(100L, restored.getLong("i64"));
            assertEquals(1.5f, restored.getFloat("f32"), 0.001f);
            assertEquals(2.718281828, restored.getDouble("f64"), 0.000001);
            assertTrue(restored.getBoolean("bool"));
            assertEquals(1700000000000L, restored.getTimestamp("ts"));
        }

        @Test
        void withNullFields() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("a", FieldType.Primitive.STRING)
                    .field("b", FieldType.Primitive.INT32).build();
            JlsmDocument doc = JlsmDocument.of(schema, "b", 5);

            JsonObject jsonObj = JsonValueAdapter.toJsonValue(doc);
            JlsmDocument restored = JsonValueAdapter.fromJsonValue(jsonObj, schema);

            assertTrue(restored.isNull("a"));
            assertEquals(5, restored.getInt("b"));
        }

        @Test
        void withNestedObjectAndArray() {
            JlsmSchema schema = JlsmSchema.builder("t", 1)
                    .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING))
                    .objectField("meta", inner -> inner.field("key", FieldType.Primitive.STRING))
                    .build();
            FieldType.ObjectType ot = (FieldType.ObjectType) schema.fields().get(1).type();
            JlsmSchema metaSchema = ot.toSchema("meta", 1);
            JlsmDocument meta = JlsmDocument.of(metaSchema, "key", "value");
            JlsmDocument doc = JlsmDocument.of(schema, "tags", new Object[]{ "x", "y" }, "meta",
                    meta);

            JsonObject jsonObj = JsonValueAdapter.toJsonValue(doc);
            JlsmDocument restored = JsonValueAdapter.fromJsonValue(jsonObj, schema);

            assertArrayEquals(new Object[]{ "x", "y" }, restored.getArray("tags"));
            assertEquals("value", restored.getObject("meta").getString("key"));
        }

        @Test
        void float16_roundTrip() {
            JlsmSchema schema = JlsmSchema.builder("t", 1).field("h", FieldType.Primitive.FLOAT16)
                    .build();
            short bits = Float16.fromFloat(1.5f);
            JlsmDocument doc = JlsmDocument.of(schema, "h", bits);

            JsonObject jsonObj = JsonValueAdapter.toJsonValue(doc);
            JlsmDocument restored = JsonValueAdapter.fromJsonValue(jsonObj, schema);

            assertEquals(1.5f, Float16.toFloat(restored.getFloat16Bits("h")), 0.01f);
        }
    }
}

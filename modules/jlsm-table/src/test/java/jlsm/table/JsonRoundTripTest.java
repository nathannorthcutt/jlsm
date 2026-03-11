package jlsm.table;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonRoundTripTest {

    @Test
    void roundTrip_allPrimitiveTypes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i32", FieldType.Primitive.INT32).field("i64", FieldType.Primitive.INT64)
                .field("f32", FieldType.Primitive.FLOAT32).field("f64", FieldType.Primitive.FLOAT64)
                .field("bool", FieldType.Primitive.BOOLEAN)
                .field("ts", FieldType.Primitive.TIMESTAMP).build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "hello", "i32", 42, "i64", 100L, "f32",
                1.5f, "f64", 2.718281828, "bool", true, "ts", 1700000000000L);
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertEquals("hello", out.getString("s"));
        assertEquals(42, out.getInt("i32"));
        assertEquals(100L, out.getLong("i64"));
        assertEquals(1.5f, out.getFloat("f32"), 0.001f);
        assertEquals(2.718281828, out.getDouble("f64"), 0.000001);
        assertTrue(out.getBoolean("bool"));
        assertEquals(1700000000000L, out.getTimestamp("ts"));
    }

    @Test
    void roundTrip_nullField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", null, "age", 25);
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertTrue(out.isNull("name"));
        assertEquals(25, out.getInt("age"));
    }

    @Test
    void roundTrip_stringWithEscapes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("msg", FieldType.Primitive.STRING)
                .build();
        String original = "line1\nline2\ttabbed\"quoted\"\\backslash";
        JlsmDocument doc = JlsmDocument.of(schema, "msg", original);
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertEquals(original, out.getString("msg"));
    }

    @Test
    void roundTrip_nestedObject() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).objectField("addr", inner -> inner
                .field("city", FieldType.Primitive.STRING).field("zip", FieldType.Primitive.INT32))
                .build();
        FieldType.ObjectType objType = (FieldType.ObjectType) schema.fields().get(0).type();
        JlsmSchema addrSchema = objType.toSchema("addr", schema.version());
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "Boston", "zip", 2101);
        JlsmDocument doc = JlsmDocument.of(schema, "addr", addr);
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertEquals("Boston", out.getObject("addr").getString("city"));
        assertEquals(2101, out.getObject("addr").getInt("zip"));
    }

    @Test
    void roundTrip_primitiveArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "nums", new Object[]{ 1, 2, 3, 4, 5 });
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertArrayEquals(new Object[]{ 1, 2, 3, 4, 5 }, out.getArray("nums"));
    }

    @Test
    void roundTrip_objectArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "tags", new Object[]{ "a", "b", "c" });
        JlsmDocument out = JlsmDocument.fromJson(doc.toJson(), schema);
        assertArrayEquals(new Object[]{ "a", "b", "c" }, out.getArray("tags"));
    }

    @Test
    void roundTrip_prettyVsCompact_equivalent() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("x", FieldType.Primitive.INT32)
                .field("y", FieldType.Primitive.STRING).build();
        JlsmDocument doc = JlsmDocument.of(schema, "x", 7, "y", "hello");
        JlsmDocument compact = JlsmDocument.fromJson(doc.toJson(false), schema);
        JlsmDocument pretty = JlsmDocument.fromJson(doc.toJson(true), schema);
        assertEquals(compact.getInt("x"), pretty.getInt("x"));
        assertEquals(compact.getString("y"), pretty.getString("y"));
    }

    @Test
    void fromJson_malformed_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("x", FieldType.Primitive.INT32)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromJson("not valid json", schema));
    }

    @Test
    void fromJson_wrongType_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("num", FieldType.Primitive.INT32)
                .build();
        // Passing a string where an int is expected
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromJson("{\"num\": \"not-a-number\"}", schema));
    }
}

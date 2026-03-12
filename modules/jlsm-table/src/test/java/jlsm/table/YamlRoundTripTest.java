package jlsm.table;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YamlRoundTripTest {

    @Test
    void roundTrip_allPrimitiveTypes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("s", FieldType.Primitive.STRING)
                .field("i8", FieldType.Primitive.INT8)
                .field("i16", FieldType.Primitive.INT16)
                .field("i32", FieldType.Primitive.INT32)
                .field("i64", FieldType.Primitive.INT64)
                .field("f16", FieldType.Primitive.FLOAT16)
                .field("f32", FieldType.Primitive.FLOAT32)
                .field("f64", FieldType.Primitive.FLOAT64)
                .field("bool", FieldType.Primitive.BOOLEAN)
                .field("ts", FieldType.Primitive.TIMESTAMP)
                .build();

        final short f16bits = Float16.fromFloat(1.5f);

        JlsmDocument doc = JlsmDocument.of(schema,
                "s", "hello",
                "i8", (byte) 7,
                "i16", (short) 300,
                "i32", 42,
                "i64", 100L,
                "f16", f16bits,
                "f32", 1.5f,
                "f64", 2.718281828,
                "bool", true,
                "ts", 1700000000000L);

        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertEquals("hello", out.getString("s"));
        assertEquals((byte) 7, out.getByte("i8"));
        assertEquals((short) 300, out.getShort("i16"));
        assertEquals(42, out.getInt("i32"));
        assertEquals(100L, out.getLong("i64"));
        assertEquals(f16bits, out.getFloat16Bits("f16"));
        assertEquals(1.5f, out.getFloat("f32"), 0.001f);
        assertEquals(2.718281828, out.getDouble("f64"), 0.000001);
        assertTrue(out.getBoolean("bool"));
        assertEquals(1700000000000L, out.getTimestamp("ts"));
    }

    @Test
    void roundTrip_nestedObject() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING)
                .objectField("addr", inner -> inner
                        .field("city", FieldType.Primitive.STRING)
                        .field("zip", FieldType.Primitive.INT32))
                .build();

        FieldType.ObjectType objType = (FieldType.ObjectType) schema.fields().get(1).type();
        JlsmSchema addrSchema = objType.toSchema("addr", schema.version());
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "Boston", "zip", 2101);
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "addr", addr);

        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertEquals("Alice", out.getString("name"));
        assertEquals("Boston", out.getObject("addr").getString("city"));
        assertEquals(2101, out.getObject("addr").getInt("zip"));
    }

    @Test
    void roundTrip_array() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32))
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "nums", new Object[]{ 1, 2, 3, 4, 5 });

        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertArrayEquals(new Object[]{ 1, 2, 3, 4, 5 }, out.getArray("nums"));
    }

    @Test
    void roundTrip_nullField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32)
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", null, "age", 25);

        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertTrue(out.isNull("name"));
        assertEquals(25, out.getInt("age"));
    }

    @Test
    void fromYaml_badIndent_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING)
                .build();

        // Malformed: unexpected extra indent on a top-level field
        String badYaml = "  name: Alice";
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.fromYaml(badYaml, schema));
    }

    @Test
    void roundTrip_stringWithSpecialChars() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("msg", FieldType.Primitive.STRING)
                .build();

        String original = "contains: colon and #hash";
        JlsmDocument doc = JlsmDocument.of(schema, "msg", original);
        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertEquals(original, out.getString("msg"));
    }

    @Test
    void roundTrip_emptyString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("s", FieldType.Primitive.STRING)
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "s", "");
        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertEquals("", out.getString("s"));
    }

    @Test
    void roundTrip_stringArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING))
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "tags", new Object[]{ "a", "b", "c" });
        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertArrayEquals(new Object[]{ "a", "b", "c" }, out.getArray("tags"));
    }

    @Test
    void roundTrip_nonFiniteFloat_becomesNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("f", FieldType.Primitive.FLOAT32)
                .build();

        JlsmDocument doc = JlsmDocument.of(schema, "f", Float.NaN);
        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        // NaN emitted as null, so field should be null after round-trip
        assertTrue(out.isNull("f"));
    }
}

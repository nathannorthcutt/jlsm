package jlsm.table;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JlsmDocumentTest {

    private static JlsmSchema primitiveSchema() {
        return JlsmSchema.builder("test", 1)
                .field("strField", FieldType.Primitive.STRING)
                .field("intField", FieldType.Primitive.INT32)
                .field("longField", FieldType.Primitive.INT64)
                .field("floatField", FieldType.Primitive.FLOAT32)
                .field("doubleField", FieldType.Primitive.FLOAT64)
                .field("boolField", FieldType.Primitive.BOOLEAN)
                .field("tsField", FieldType.Primitive.TIMESTAMP)
                .build();
    }

    @Test
    void of_allPrimitiveTypes_getRoundTrip() {
        JlsmSchema schema = primitiveSchema();
        JlsmDocument doc = JlsmDocument.of(schema,
                "strField", "hello",
                "intField", 42,
                "longField", 100L,
                "floatField", 3.14f,
                "doubleField", 2.718281828,
                "boolField", true,
                "tsField", 1700000000000L);
        assertEquals("hello", doc.getString("strField"));
        assertEquals(42, doc.getInt("intField"));
        assertEquals(100L, doc.getLong("longField"));
        assertEquals(3.14f, doc.getFloat("floatField"), 0.0001f);
        assertEquals(2.718281828, doc.getDouble("doubleField"), 0.0000001);
        assertTrue(doc.getBoolean("boolField"));
        assertEquals(1700000000000L, doc.getTimestamp("tsField"));
    }

    @Test
    void of_float16_storesRawBits() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("halfFloat", FieldType.Primitive.FLOAT16)
                .build();
        short bits = Float16.fromFloat(1.5f);
        JlsmDocument doc = JlsmDocument.of(schema, "halfFloat", bits);
        assertEquals(bits, doc.getFloat16Bits("halfFloat"));
    }

    @Test
    void of_nullField_isNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", null);
        assertTrue(doc.isNull("name"));
    }

    @Test
    void of_arrayField_getsArray() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING))
                .build();
        Object[] tags = {"a", "b", "c"};
        JlsmDocument doc = JlsmDocument.of(schema, "tags", tags);
        assertArrayEquals(tags, doc.getArray("tags"));
    }

    @Test
    void of_nestedObject_getsSubDocument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .objectField("address", inner -> inner
                        .field("city", FieldType.Primitive.STRING))
                .build();
        JlsmSchema addrSchema = ((FieldType.ObjectType) schema.fields().get(0).type())
                .toSchema("address", schema.version());
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "NYC");
        JlsmDocument doc = JlsmDocument.of(schema, "address", addr);
        assertEquals("NYC", doc.getObject("address").getString("city"));
    }

    @Test
    void of_typeMismatch_throwsIllegalArgument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("num", FieldType.Primitive.INT32)
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                JlsmDocument.of(schema, "num", "not an int"));
    }

    @Test
    void of_unknownField_throwsIllegalArgument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("known", FieldType.Primitive.STRING)
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                JlsmDocument.of(schema, "unknown", "value"));
    }

    @Test
    void of_timestamp_getLong() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("created", FieldType.Primitive.TIMESTAMP)
                .build();
        long ts = System.currentTimeMillis();
        JlsmDocument doc = JlsmDocument.of(schema, "created", ts);
        assertEquals(ts, doc.getTimestamp("created"));
    }
}

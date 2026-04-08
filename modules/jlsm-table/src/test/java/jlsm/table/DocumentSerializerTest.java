package jlsm.table;

import jlsm.core.io.MemorySerializer;
import org.junit.jupiter.api.Test;
import java.lang.foreign.MemorySegment;
import static org.junit.jupiter.api.Assertions.*;

class DocumentSerializerTest {

    private static JlsmDocument roundTrip(JlsmSchema schema, JlsmDocument doc) {
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        return ser.deserialize(bytes);
    }

    @Test
    void serialize_deserialize_allPrimitiveTypes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i8", FieldType.Primitive.INT8).field("i16", FieldType.Primitive.INT16)
                .field("i32", FieldType.Primitive.INT32).field("i64", FieldType.Primitive.INT64)
                .field("f32", FieldType.Primitive.FLOAT32).field("f64", FieldType.Primitive.FLOAT64)
                .field("bool", FieldType.Primitive.BOOLEAN)
                .field("ts", FieldType.Primitive.TIMESTAMP).build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "hello", "i8", (byte) 42, "i16",
                (short) 1000, "i32", 123456, "i64", 999999999999L, "f32", 3.14f, "f64", 2.718281828,
                "bool", true, "ts", 1700000000000L);
        JlsmDocument out = roundTrip(schema, doc);
        assertEquals("hello", out.getString("s"));
        assertEquals((byte) 42, out.getByte("i8"));
        assertEquals((short) 1000, out.getShort("i16"));
        assertEquals(123456, out.getInt("i32"));
        assertEquals(999999999999L, out.getLong("i64"));
        assertEquals(3.14f, out.getFloat("f32"), 0.0001f);
        assertEquals(2.718281828, out.getDouble("f64"), 0.000001);
        assertTrue(out.getBoolean("bool"));
        assertEquals(1700000000000L, out.getTimestamp("ts"));
    }

    @Test
    void serialize_deserialize_nullField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.STRING)
                .field("b", FieldType.Primitive.INT32).build();
        JlsmDocument doc = JlsmDocument.of(schema, "a", null, "b", 99);
        JlsmDocument out = roundTrip(schema, doc);
        assertTrue(out.isNull("a"));
        assertEquals(99, out.getInt("b"));
    }

    @Test
    void serialize_deserialize_allNullFields() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("x", FieldType.Primitive.STRING)
                .field("y", FieldType.Primitive.INT32).build();
        JlsmDocument doc = JlsmDocument.of(schema, "x", null, "y", null);
        JlsmDocument out = roundTrip(schema, doc);
        assertTrue(out.isNull("x"));
        assertTrue(out.isNull("y"));
    }

    @Test
    void serialize_deserialize_mixedNullAndNonNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.INT32)
                .field("b", FieldType.Primitive.STRING).field("c", FieldType.Primitive.INT32)
                .field("d", FieldType.Primitive.STRING).build();
        JlsmDocument doc = JlsmDocument.of(schema, "a", 1, "b", null, "c", null, "d", "end");
        JlsmDocument out = roundTrip(schema, doc);
        assertEquals(1, out.getInt("a"));
        assertTrue(out.isNull("b"));
        assertTrue(out.isNull("c"));
        assertEquals("end", out.getString("d"));
    }

    @Test
    void serialize_deserialize_booleanPacking() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("b1", FieldType.Primitive.BOOLEAN)
                .field("b2", FieldType.Primitive.BOOLEAN).field("b3", FieldType.Primitive.BOOLEAN)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "b1", true, "b2", false, "b3", true);
        JlsmDocument out = roundTrip(schema, doc);
        assertTrue(out.getBoolean("b1"));
        assertFalse(out.getBoolean("b2"));
        assertTrue(out.getBoolean("b3"));
    }

    @Test
    void serialize_deserialize_nullBoolean() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("flag", FieldType.Primitive.BOOLEAN)
                .field("name", FieldType.Primitive.STRING).build();
        JlsmDocument doc = JlsmDocument.of(schema, "flag", null, "name", "test");
        JlsmDocument out = roundTrip(schema, doc);
        assertTrue(out.isNull("flag"));
        assertEquals("test", out.getString("name"));
    }

    @Test
    void serialize_varint_shortString() {
        // String < 128 bytes should use 1-byte VarInt length
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "hi");
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        // Header: 2 (version) + 2 (fieldCount) + 2 (boolCount) + 1 (null bitmask) = 7 bytes
        // Value: 1 (varint len=2) + 2 (UTF-8 "hi") = 3 bytes
        // Total: 10 bytes
        assertEquals(10L, bytes.byteSize());
    }

    @Test
    void serialize_varint_longString() {
        // String with 128-byte UTF-8 encoding should use 2-byte VarInt length
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .build();
        String s128 = "a".repeat(128);
        JlsmDocument doc = JlsmDocument.of(schema, "s", s128);
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        // Header: 7 bytes; Value: 2 (varint 128) + 128 bytes = 130; Total: 137
        assertEquals(137L, bytes.byteSize());
    }

    @Test
    void serialize_varint_arrayCount() {
        // Array with 200 elements should use 2-byte VarInt count
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("arr", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
        Object[] arr = new Object[200];
        for (int i = 0; i < 200; i++)
            arr[i] = i;
        JlsmDocument doc = JlsmDocument.of(schema, "arr", arr);
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        // Header: 7; Array: 2 (varint 200) + 200*4 bytes = 802; Total: 809
        assertEquals(809L, bytes.byteSize());
    }

    @Test
    void serialize_deserialize_int32Array() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
        Object[] nums = { 1, 2, 3, 4, 5, 100, -1, Integer.MAX_VALUE };
        JlsmDocument doc = JlsmDocument.of(schema, "nums", nums);
        JlsmDocument out = roundTrip(schema, doc);
        assertArrayEquals(nums, out.getArray("nums"));
    }

    @Test
    void serialize_deserialize_float32Array() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("vals", FieldType.arrayOf(FieldType.Primitive.FLOAT32)).build();
        Object[] vals = { 1.0f, 2.5f, -3.14f, Float.MAX_VALUE };
        JlsmDocument doc = JlsmDocument.of(schema, "vals", vals);
        JlsmDocument out = roundTrip(schema, doc);
        Object[] result = out.getArray("vals");
        assertEquals(vals.length, result.length);
        for (int i = 0; i < vals.length; i++) {
            assertEquals((Float) vals[i], (Float) result[i], 0.0001f);
        }
    }

    @Test
    void serialize_deserialize_float64Array() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("vals", FieldType.arrayOf(FieldType.Primitive.FLOAT64)).build();
        Object[] vals = { 1.0, 2.718281828, -3.14159265 };
        JlsmDocument doc = JlsmDocument.of(schema, "vals", vals);
        JlsmDocument out = roundTrip(schema, doc);
        Object[] result = out.getArray("vals");
        assertEquals(vals.length, result.length);
        for (int i = 0; i < vals.length; i++) {
            assertEquals((Double) vals[i], (Double) result[i], 0.000001);
        }
    }

    @Test
    void serialize_deserialize_nestedObject() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).objectField("addr", inner -> inner
                .field("city", FieldType.Primitive.STRING).field("zip", FieldType.Primitive.INT32))
                .build();
        FieldType.ObjectType objType = (FieldType.ObjectType) schema.fields().get(0).type();
        JlsmSchema addrSchema = objType.toSchema("addr", schema.version());
        JlsmDocument addr = JlsmDocument.of(addrSchema, "city", "Boston", "zip", 2101);
        JlsmDocument doc = JlsmDocument.of(schema, "addr", addr);
        JlsmDocument out = roundTrip(schema, doc);
        JlsmDocument outAddr = out.getObject("addr");
        assertEquals("Boston", outAddr.getString("city"));
        assertEquals(2101, outAddr.getInt("zip"));
    }

    @Test
    void serialize_deserialize_nestedObjectWithNullFields() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).objectField("inner",
                b -> b.field("x", FieldType.Primitive.INT32).field("y", FieldType.Primitive.STRING))
                .build();
        FieldType.ObjectType objType = (FieldType.ObjectType) schema.fields().get(0).type();
        JlsmSchema innerSchema = objType.toSchema("inner", schema.version());
        JlsmDocument inner = JlsmDocument.of(innerSchema, "x", 7, "y", null);
        JlsmDocument doc = JlsmDocument.of(schema, "inner", inner);
        JlsmDocument out = roundTrip(schema, doc);
        assertEquals(7, out.getObject("inner").getInt("x"));
        assertTrue(out.getObject("inner").isNull("y"));
    }

    @Test
    void serialize_deserialize_arrayOfObjects() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("items",
                        FieldType.arrayOf(FieldType.objectOf(java.util.List.of(
                                new FieldDefinition("name", FieldType.Primitive.STRING),
                                new FieldDefinition("val", FieldType.Primitive.INT32)))))
                .build();
        FieldType.ObjectType elemType = (FieldType.ObjectType) ((FieldType.ArrayType) schema
                .fields().get(0).type()).elementType();
        JlsmSchema elemSchema = elemType.toSchema("item", schema.version());
        JlsmDocument item1 = JlsmDocument.of(elemSchema, "name", "foo", "val", 1);
        JlsmDocument item2 = JlsmDocument.of(elemSchema, "name", "bar", "val", 2);
        JlsmDocument doc = JlsmDocument.of(schema, "items", new Object[]{ item1, item2 });
        JlsmDocument out = roundTrip(schema, doc);
        Object[] items = out.getArray("items");
        assertEquals(2, items.length);
        assertEquals("foo", ((JlsmDocument) items[0]).getString("name"));
        assertEquals(2, ((JlsmDocument) items[1]).getInt("val"));
    }

    @Test
    void serialize_olderVersion_deserializedByNewer_newFieldsAreNull() {
        // Simulate: schema v1 has 2 fields, v2 adds a 3rd
        JlsmSchema schemaV1 = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.STRING)
                .field("b", FieldType.Primitive.INT32).build();
        JlsmSchema schemaV2 = JlsmSchema.builder("test", 2).field("a", FieldType.Primitive.STRING)
                .field("b", FieldType.Primitive.INT32).field("c", FieldType.Primitive.BOOLEAN)
                .build();
        JlsmDocument docV1 = JlsmDocument.of(schemaV1, "a", "hello", "b", 42);
        // Serialize with V1 serializer
        MemorySerializer<JlsmDocument> serV1 = DocumentSerializer.forSchema(schemaV1);
        MemorySegment bytes = serV1.serialize(docV1);
        // Deserialize with V2 serializer
        MemorySerializer<JlsmDocument> serV2 = DocumentSerializer.forSchema(schemaV2);
        JlsmDocument out = serV2.deserialize(bytes);
        assertEquals("hello", out.getString("a"));
        assertEquals(42, out.getInt("b"));
        assertTrue(out.isNull("c")); // new field → null
    }

    @Test
    void serialize_emptyDocument() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).build();
        JlsmDocument doc = JlsmDocument.of(schema);
        JlsmDocument out = roundTrip(schema, doc);
        assertNotNull(out);
        assertEquals(0, out.schema().fields().size());
    }
}

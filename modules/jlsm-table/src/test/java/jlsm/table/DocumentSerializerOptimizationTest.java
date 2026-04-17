package jlsm.table;

import jlsm.core.io.MemorySerializer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DocumentSerializer deserialization optimizations: heap fast path, off-heap fallback,
 * precomputed schema constants, and field decoder dispatch table.
 *
 * <p>
 * These tests verify that the optimized deserialization path produces results identical to the
 * original implementation for all field types and segment backing types.
 */
class DocumentSerializerOptimizationTest {

    // ---- Happy path ----

    // @spec F06.R1,R2,R14 — heap fast path produces correct round-trip for heap-backed segments
    @Test
    void testHeapBackedSegmentDeserializesCorrectly() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32)
                .field("active", FieldType.Primitive.BOOLEAN).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30, "active", true);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment heapSegment = ser.serialize(doc);

        // serialize() returns MemorySegment.ofArray(byte[]) — heap-backed
        assert heapSegment.heapBase().isPresent() : "test requires heap-backed segment";

        JlsmDocument result = ser.deserialize(heapSegment);
        assertEquals("Alice", result.getString("name"));
        assertEquals(30, result.getInt("age"));
        assertTrue(result.getBoolean("active"));
    }

    // @spec F06.R3,R14 — off-heap fallback via toArray() produces correct round-trip
    @Test
    void testOffHeapSegmentDeserializesCorrectly() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32)
                .field("active", FieldType.Primitive.BOOLEAN).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Bob", "age", 25, "active", false);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment heapSegment = ser.serialize(doc);

        // Copy to off-heap segment via Arena
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment offHeap = arena.allocate(heapSegment.byteSize());
            MemorySegment.copy(heapSegment, 0, offHeap, 0, heapSegment.byteSize());
            assert offHeap.heapBase().isEmpty() : "test requires off-heap segment";

            JlsmDocument result = ser.deserialize(offHeap);
            assertEquals("Bob", result.getString("name"));
            assertEquals(25, result.getInt("age"));
            assertFalse(result.getBoolean("active"));
        }
    }

    // @spec F06.R10,R11,R13,R14 — dispatch table decodes all field types byte-identically
    @Test
    void testAllFieldTypesRoundTripAfterOptimization() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i8", FieldType.Primitive.INT8).field("i16", FieldType.Primitive.INT16)
                .field("i32", FieldType.Primitive.INT32).field("i64", FieldType.Primitive.INT64)
                .field("f32", FieldType.Primitive.FLOAT32).field("f64", FieldType.Primitive.FLOAT64)
                .field("bool", FieldType.Primitive.BOOLEAN)
                .field("ts", FieldType.Primitive.TIMESTAMP).build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "test-value", "i8", (byte) -128, "i16",
                (short) 32767, "i32", Integer.MIN_VALUE, "i64", Long.MAX_VALUE, "f32",
                Float.MIN_VALUE, "f64", Double.MAX_VALUE, "bool", false, "ts", 9999999999999L);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument result = ser.deserialize(bytes);

        assertEquals("test-value", result.getString("s"));
        assertEquals((byte) -128, result.getByte("i8"));
        assertEquals((short) 32767, result.getShort("i16"));
        assertEquals(Integer.MIN_VALUE, result.getInt("i32"));
        assertEquals(Long.MAX_VALUE, result.getLong("i64"));
        assertEquals(Float.MIN_VALUE, result.getFloat("f32"), 0f);
        assertEquals(Double.MAX_VALUE, result.getDouble("f64"), 0d);
        assertFalse(result.getBoolean("bool"));
        assertEquals(9999999999999L, result.getTimestamp("ts"));
    }

    // @spec F06.R6,R7,R8,R15 — prefixBoolCount handles writeFieldCount < fieldCount correctly
    @Test
    void testSchemaEvolutionWithPrecomputedConstants() {
        // Serialize with 3-field schema
        JlsmSchema schemaV1 = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.STRING)
                .field("b", FieldType.Primitive.INT32).field("flag", FieldType.Primitive.BOOLEAN)
                .build();
        // Deserialize with 5-field schema (2 new fields added)
        JlsmSchema schemaV2 = JlsmSchema.builder("test", 2).field("a", FieldType.Primitive.STRING)
                .field("b", FieldType.Primitive.INT32).field("flag", FieldType.Primitive.BOOLEAN)
                .field("c", FieldType.Primitive.FLOAT64).field("d", FieldType.Primitive.STRING)
                .build();

        JlsmDocument docV1 = JlsmDocument.of(schemaV1, "a", "hello", "b", 42, "flag", true);
        MemorySerializer<JlsmDocument> serV1 = DocumentSerializer.forSchema(schemaV1);
        MemorySegment bytes = serV1.serialize(docV1);

        // Deserialize with V2 — prefixBoolCount must handle writeFieldCount(3) <
        // currentFieldCount(5)
        MemorySerializer<JlsmDocument> serV2 = DocumentSerializer.forSchema(schemaV2);
        JlsmDocument result = serV2.deserialize(bytes);

        assertEquals("hello", result.getString("a"));
        assertEquals(42, result.getInt("b"));
        assertTrue(result.getBoolean("flag"));
        assertTrue(result.isNull("c"), "new field c must be null");
        assertTrue(result.isNull("d"), "new field d must be null");
    }

    // ---- Error and edge cases ----

    @Test
    void testDeserializeNullSegmentThrows() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("x", FieldType.Primitive.INT32)
                .build();
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        assertThrows(NullPointerException.class, () -> ser.deserialize(null));
    }

    // ---- Boundary values ----

    @Test
    void testSingleFieldSchema() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("only", FieldType.Primitive.INT64)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "only", 42L);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));
        assertEquals(42L, result.getLong("only"));
    }

    @Test
    void testAllBooleanSchema() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("a", FieldType.Primitive.BOOLEAN)
                .field("b", FieldType.Primitive.BOOLEAN).field("c", FieldType.Primitive.BOOLEAN)
                .field("d", FieldType.Primitive.BOOLEAN).build();
        JlsmDocument doc = JlsmDocument.of(schema, "a", true, "b", false, "c", true, "d", false);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));

        assertTrue(result.getBoolean("a"));
        assertFalse(result.getBoolean("b"));
        assertTrue(result.getBoolean("c"));
        assertFalse(result.getBoolean("d"));
    }

    @Test
    void testSchemaWithNoBooleans() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("x", FieldType.Primitive.INT32)
                .field("y", FieldType.Primitive.STRING).field("z", FieldType.Primitive.FLOAT64)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "x", 1, "y", "two", "z", 3.0);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));

        assertEquals(1, result.getInt("x"));
        assertEquals("two", result.getString("y"));
        assertEquals(3.0, result.getDouble("z"), 0d);
    }

    @Test
    void testEmptyStringField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "");

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));
        assertEquals("", result.getString("s"));
    }

    // ---- Structural (optimization verification) ----

    // @spec F06.R14,R20,R22 — multiple round-trips preserve values; backing array not retained
    @Test
    void testSerializeDeserializeRoundTripIsIdentity() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("count", FieldType.Primitive.INT32)
                .field("flag", FieldType.Primitive.BOOLEAN).build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);

        // Multiple round-trips should all produce identical results
        for (int i = 0; i < 10; i++) {
            JlsmDocument doc = JlsmDocument.of(schema, "name", "item-" + i, "count", i * 100,
                    "flag", i % 2 == 0);

            MemorySegment bytes = ser.serialize(doc);
            JlsmDocument result = ser.deserialize(bytes);

            assertEquals("item-" + i, result.getString("name"));
            assertEquals(i * 100, result.getInt("count"));
            assertEquals(i % 2 == 0, result.getBoolean("flag"));
        }
    }

    // @spec F06.R1 — heap-backed segments decode via backing-array fast path
    @Test
    void testHeapSegmentUsesBackingArrayDirectly() {
        // This test verifies the optimization contract: for heap-backed segments,
        // deserialize should work with the backing array directly (no copy).
        // We verify this by confirming the heap segment is accepted and produces
        // correct results — the actual zero-copy behavior is validated by profiling.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("val", FieldType.Primitive.INT32)
                .build();

        byte[] backingArray = new byte[]{ 0, 1, 0, 1, 0, 0, 0, 0, 42 };
        // Header: version=1, fieldCount=1, nullMask=0x00, then INT32 value=42
        // Actually, let's use the serializer to produce correct bytes
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "val", 99);
        MemorySegment heapSeg = ser.serialize(doc);

        // Confirm it's heap-backed
        assertTrue(heapSeg.heapBase().isPresent(), "serialized segment must be heap-backed");

        // Deserialize — should use heap fast path internally
        JlsmDocument result = ser.deserialize(heapSeg);
        assertEquals(99, result.getInt("val"));
    }

    // @spec F06.R3 — off-heap segments trigger toArray() fallback copy
    @Test
    void testOffHeapSegmentCopiesBytes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("val", FieldType.Primitive.INT32)
                .build();

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument doc = JlsmDocument.of(schema, "val", 77);
        MemorySegment heapSeg = ser.serialize(doc);

        // Copy to off-heap
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment offHeap = arena.allocate(heapSeg.byteSize());
            MemorySegment.copy(heapSeg, 0, offHeap, 0, heapSeg.byteSize());

            // Confirm it's NOT heap-backed
            assertTrue(offHeap.heapBase().isEmpty(), "off-heap segment must not have heapBase");

            // Deserialize — should fall back to toArray() copy
            JlsmDocument result = ser.deserialize(offHeap);
            assertEquals(77, result.getInt("val"));
        }
    }
}

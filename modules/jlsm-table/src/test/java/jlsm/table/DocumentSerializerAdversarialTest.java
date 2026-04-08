package jlsm.table;

import jlsm.core.io.MemorySerializer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for DocumentSerializer and JlsmDocument, targeting findings from the
 * optimize-document-serializer audit spec analysis.
 */
class DocumentSerializerAdversarialTest {

    // =====================================================================
    // JD-02: getArray() returns mutable internal reference — caller can
    // corrupt document state by mutating the returned array.
    // =====================================================================

    @Test
    void getArray_mutatingReturnedArray_corruptsDocumentState() {
        // JD-02: getArray() returns the internal Object[] reference directly.
        // Mutating it should NOT affect the document if properly defensive.
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "tags", new Object[]{ "a", "b", "c" });

        Object[] returned = doc.getArray("tags");
        returned[0] = "MUTATED";

        // If getArray() returned a defensive copy, original should be unaffected
        Object[] again = doc.getArray("tags");
        assertEquals("a", again[0],
                "getArray() should return a defensive copy — mutation must not corrupt document");
    }

    @Test
    void getArray_afterDeserialize_mutatingReturnedArray_corruptsDocumentState() {
        // JD-02: Same vector but after a round-trip through serializer.
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("nums", FieldType.arrayOf(FieldType.Primitive.INT32)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "nums", new Object[]{ 1, 2, 3 });

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument deserialized = ser.deserialize(ser.serialize(doc));

        Object[] arr = deserialized.getArray("nums");
        arr[0] = 999;

        Object[] again = deserialized.getArray("nums");
        assertEquals(1, again[0], "getArray() on deserialized doc should return defensive copy");
    }

    // =====================================================================
    // JD-03: getArray() returns null for null field without NPE,
    // inconsistent with getString()/getInt()/etc. which throw NPE.
    // =====================================================================

    @Test
    void getArray_nullField_shouldThrowNullPointerException() {
        // JD-03: Other typed getters throw NPE for null fields, but getArray()
        // silently returns null. This tests the expected consistent behavior.
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.Primitive.STRING)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "tags", null);

        assertThrows(NullPointerException.class, () -> doc.getArray("tags"),
                "getArray() should throw NPE for null field, consistent with other getters");
    }

    // =====================================================================
    // JD-04: getObject() returns null for null field without NPE,
    // inconsistent with getString()/getInt()/etc.
    // =====================================================================

    @Test
    void getObject_nullField_shouldThrowNullPointerException() {
        // JD-04: Same inconsistency as JD-03 but for ObjectType fields.
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .objectField("addr", inner -> inner.field("city", FieldType.Primitive.STRING))
                .build();
        JlsmDocument doc = JlsmDocument.of(schema, "addr", null);

        assertThrows(NullPointerException.class, () -> doc.getObject("addr"),
                "getObject() should throw NPE for null field, consistent with other getters");
    }

    // =====================================================================
    // DS-01: extractBytes heap path defensive improvement — verify
    // deserialization works correctly with sliced heap segments.
    // =====================================================================

    @Test
    void deserialize_slicedHeapSegment_producesCorrectResult() {
        // DS-01: A sliced heap segment has heapBase().isPresent() but
        // byteSize() != data.length. extractBytes should fall back to toArray().
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("val", FieldType.Primitive.INT32)
                .build();
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);

        JlsmDocument doc = JlsmDocument.of(schema, "val", 42);
        MemorySegment heapSeg = ser.serialize(doc);
        byte[] backing = heapSeg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

        // Embed serialized bytes in a larger array and slice into it
        byte[] larger = new byte[backing.length + 20];
        System.arraycopy(backing, 0, larger, 10, backing.length);
        MemorySegment wrapping = MemorySegment.ofArray(larger);
        MemorySegment sliced = wrapping.asSlice(10, backing.length);

        // sliced is heap-backed but is a slice — should still deserialize correctly
        JlsmDocument result = ser.deserialize(sliced);
        assertEquals(42, result.getInt("val"));
    }

    @Test
    void deserialize_offHeapSegment_allFieldTypes_producesCorrectResult() {
        // DS-01: Comprehensive off-heap test with all primitive types to verify
        // the toArray() fallback path handles every field decoder correctly.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("s", FieldType.Primitive.STRING)
                .field("i8", FieldType.Primitive.INT8).field("i16", FieldType.Primitive.INT16)
                .field("i32", FieldType.Primitive.INT32).field("i64", FieldType.Primitive.INT64)
                .field("f16", FieldType.Primitive.FLOAT16).field("f32", FieldType.Primitive.FLOAT32)
                .field("f64", FieldType.Primitive.FLOAT64)
                .field("bool", FieldType.Primitive.BOOLEAN)
                .field("ts", FieldType.Primitive.TIMESTAMP).build();
        JlsmDocument doc = JlsmDocument.of(schema, "s", "hello", "i8", (byte) 127, "i16",
                (short) -1, "i32", Integer.MAX_VALUE, "i64", Long.MIN_VALUE, "f16",
                Float16.fromFloat(1.0f), "f32", -0.0f, "f64", Double.MIN_VALUE, "bool", true, "ts",
                0L);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment heapSeg = ser.serialize(doc);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment offHeap = arena.allocate(heapSeg.byteSize());
            MemorySegment.copy(heapSeg, 0, offHeap, 0, heapSeg.byteSize());

            JlsmDocument result = ser.deserialize(offHeap);
            assertEquals("hello", result.getString("s"));
            assertEquals((byte) 127, result.getByte("i8"));
            assertEquals((short) -1, result.getShort("i16"));
            assertEquals(Integer.MAX_VALUE, result.getInt("i32"));
            assertEquals(Long.MIN_VALUE, result.getLong("i64"));
            assertEquals(Float16.fromFloat(1.0f), result.getFloat16Bits("f16"));
            assertEquals(-0.0f, result.getFloat("f32"), 0f);
            assertEquals(Double.MIN_VALUE, result.getDouble("f64"), 0d);
            assertTrue(result.getBoolean("bool"));
            assertEquals(0L, result.getTimestamp("ts"));
        }
    }

    // =====================================================================
    // Schema evolution with booleans at various positions — verifies that
    // prefixBoolCount precomputation handles edge cases correctly.
    // =====================================================================

    @Test
    void schemaEvolution_writerHasBooleansReaderHasMore_prefixBoolCountCorrect() {
        // Writer: [STRING, BOOLEAN, INT32] — 1 boolean at position 1
        // Reader: [STRING, BOOLEAN, INT32, BOOLEAN, STRING] — 2 booleans
        // Ensures prefixBoolCount[readCount=3] gives the correct bool count.
        JlsmSchema writerSchema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.Primitive.STRING)
                .field("flag1", FieldType.Primitive.BOOLEAN)
                .field("count", FieldType.Primitive.INT32).build();
        JlsmSchema readerSchema = JlsmSchema.builder("test", 2)
                .field("name", FieldType.Primitive.STRING)
                .field("flag1", FieldType.Primitive.BOOLEAN)
                .field("count", FieldType.Primitive.INT32)
                .field("flag2", FieldType.Primitive.BOOLEAN)
                .field("extra", FieldType.Primitive.STRING).build();

        JlsmDocument writerDoc = JlsmDocument.of(writerSchema, "name", "test", "flag1", true,
                "count", 7);

        MemorySerializer<JlsmDocument> writerSer = DocumentSerializer.forSchema(writerSchema);
        MemorySegment bytes = writerSer.serialize(writerDoc);

        MemorySerializer<JlsmDocument> readerSer = DocumentSerializer.forSchema(readerSchema);
        JlsmDocument result = readerSer.deserialize(bytes);

        assertEquals("test", result.getString("name"));
        assertTrue(result.getBoolean("flag1"));
        assertEquals(7, result.getInt("count"));
        assertTrue(result.isNull("flag2"));
        assertTrue(result.isNull("extra"));
    }

    @Test
    void schemaEvolution_writerHasMultipleBooleans_readerAddsMoreBooleans() {
        // Writer: [BOOLEAN, STRING, BOOLEAN, INT32] — 2 booleans
        // Reader: [BOOLEAN, STRING, BOOLEAN, INT32, BOOLEAN] — 3 booleans
        JlsmSchema writerSchema = JlsmSchema.builder("test", 1)
                .field("b1", FieldType.Primitive.BOOLEAN).field("s", FieldType.Primitive.STRING)
                .field("b2", FieldType.Primitive.BOOLEAN).field("n", FieldType.Primitive.INT32)
                .build();
        JlsmSchema readerSchema = JlsmSchema.builder("test", 2)
                .field("b1", FieldType.Primitive.BOOLEAN).field("s", FieldType.Primitive.STRING)
                .field("b2", FieldType.Primitive.BOOLEAN).field("n", FieldType.Primitive.INT32)
                .field("b3", FieldType.Primitive.BOOLEAN).build();

        JlsmDocument writerDoc = JlsmDocument.of(writerSchema, "b1", false, "s", "data", "b2", true,
                "n", 42);

        MemorySerializer<JlsmDocument> writerSer = DocumentSerializer.forSchema(writerSchema);
        MemorySegment bytes = writerSer.serialize(writerDoc);

        MemorySerializer<JlsmDocument> readerSer = DocumentSerializer.forSchema(readerSchema);
        JlsmDocument result = readerSer.deserialize(bytes);

        assertFalse(result.getBoolean("b1"));
        assertEquals("data", result.getString("s"));
        assertTrue(result.getBoolean("b2"));
        assertEquals(42, result.getInt("n"));
        assertTrue(result.isNull("b3"));
    }

    @Test
    void schemaEvolution_allBooleanWriter_readerAddsNonBooleans() {
        // Writer: [BOOLEAN, BOOLEAN, BOOLEAN] — 3 booleans
        // Reader: [BOOLEAN, BOOLEAN, BOOLEAN, INT32, STRING] — still 3 booleans
        JlsmSchema writerSchema = JlsmSchema.builder("test", 1)
                .field("a", FieldType.Primitive.BOOLEAN).field("b", FieldType.Primitive.BOOLEAN)
                .field("c", FieldType.Primitive.BOOLEAN).build();
        JlsmSchema readerSchema = JlsmSchema.builder("test", 2)
                .field("a", FieldType.Primitive.BOOLEAN).field("b", FieldType.Primitive.BOOLEAN)
                .field("c", FieldType.Primitive.BOOLEAN).field("count", FieldType.Primitive.INT32)
                .field("label", FieldType.Primitive.STRING).build();

        JlsmDocument writerDoc = JlsmDocument.of(writerSchema, "a", true, "b", false, "c", true);

        MemorySerializer<JlsmDocument> writerSer = DocumentSerializer.forSchema(writerSchema);
        MemorySegment bytes = writerSer.serialize(writerDoc);

        MemorySerializer<JlsmDocument> readerSer = DocumentSerializer.forSchema(readerSchema);
        JlsmDocument result = readerSer.deserialize(bytes);

        assertTrue(result.getBoolean("a"));
        assertFalse(result.getBoolean("b"));
        assertTrue(result.getBoolean("c"));
        assertTrue(result.isNull("count"));
        assertTrue(result.isNull("label"));
    }

    // =====================================================================
    // Null booleans with schema evolution — ensures null boolean fields
    // still advance boolIdx correctly during deserialization.
    // =====================================================================

    @Test
    void schemaEvolution_nullBooleansInterspersed_correctDeserialization() {
        // Writer: [BOOLEAN(null), STRING, BOOLEAN(true), INT32]
        // Reader: same + extra BOOLEAN
        JlsmSchema writerSchema = JlsmSchema.builder("test", 1)
                .field("b1", FieldType.Primitive.BOOLEAN).field("s", FieldType.Primitive.STRING)
                .field("b2", FieldType.Primitive.BOOLEAN).field("n", FieldType.Primitive.INT32)
                .build();
        JlsmSchema readerSchema = JlsmSchema.builder("test", 2)
                .field("b1", FieldType.Primitive.BOOLEAN).field("s", FieldType.Primitive.STRING)
                .field("b2", FieldType.Primitive.BOOLEAN).field("n", FieldType.Primitive.INT32)
                .field("b3", FieldType.Primitive.BOOLEAN).build();

        JlsmDocument writerDoc = JlsmDocument.of(writerSchema, "b1", null, "s", "hello", "b2", true,
                "n", 5);

        MemorySerializer<JlsmDocument> writerSer = DocumentSerializer.forSchema(writerSchema);
        MemorySegment bytes = writerSer.serialize(writerDoc);

        MemorySerializer<JlsmDocument> readerSer = DocumentSerializer.forSchema(readerSchema);
        JlsmDocument result = readerSer.deserialize(bytes);

        assertTrue(result.isNull("b1"), "null boolean must remain null");
        assertEquals("hello", result.getString("s"));
        assertTrue(result.getBoolean("b2"));
        assertEquals(5, result.getInt("n"));
        assertTrue(result.isNull("b3"));
    }

    // =====================================================================
    // Many boolean fields (>8) to test multi-byte bool bitmask handling
    // =====================================================================

    @Test
    void manyBooleanFields_multiByteBlaskMask_correctRoundTrip() {
        // 10 boolean fields — requires 2 bytes in bool bitmask
        var builder = JlsmSchema.builder("test", 1);
        for (int i = 0; i < 10; i++) {
            builder = builder.field("b" + i, FieldType.Primitive.BOOLEAN);
        }
        JlsmSchema schema = builder.build();

        Object[] nameValues = new Object[20];
        for (int i = 0; i < 10; i++) {
            nameValues[i * 2] = "b" + i;
            nameValues[i * 2 + 1] = i % 3 == 0; // true for 0,3,6,9
        }
        JlsmDocument doc = JlsmDocument.of(schema, nameValues);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));

        for (int i = 0; i < 10; i++) {
            assertEquals(i % 3 == 0, result.getBoolean("b" + i),
                    "boolean field b" + i + " mismatch");
        }
    }

    // =====================================================================
    // BoundedString round-trip through optimized deserializer
    // =====================================================================

    @Test
    void boundedString_roundTrip_correctDeserialization() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", new FieldType.BoundedString(10)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "code", "ABC");

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));

        assertEquals("ABC", result.getString("code"));
    }

    @Test
    void boundedString_emptyString_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("code", new FieldType.BoundedString(10)).build();
        JlsmDocument doc = JlsmDocument.of(schema, "code", "");

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        JlsmDocument result = ser.deserialize(ser.serialize(doc));

        assertEquals("", result.getString("code"));
    }
}

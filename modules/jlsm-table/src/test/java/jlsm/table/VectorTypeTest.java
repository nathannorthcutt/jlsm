package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.io.MemorySerializer;
import jlsm.table.internal.IndexRegistry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Tests for the VectorType field type, vector index definition simplification, IndexRegistry
 * validation changes, and DocumentSerializer round-trip.
 */
class VectorTypeTest {

    // ── FieldType.VectorType construction ────────────────────────────────

    @Test
    void vectorType_createsWithFloat32() {
        var vt = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        assertEquals(FieldType.Primitive.FLOAT32, vt.elementType());
        assertEquals(128, vt.dimensions());
    }

    @Test
    void vectorType_createsWithFloat16() {
        var vt = new FieldType.VectorType(FieldType.Primitive.FLOAT16, 64);
        assertEquals(FieldType.Primitive.FLOAT16, vt.elementType());
        assertEquals(64, vt.dimensions());
    }

    @Test
    void vectorType_rejectsStringElementType() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.STRING, 128));
    }

    @Test
    void vectorType_rejectsInt32ElementType() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.INT32, 128));
    }

    @Test
    void vectorType_rejectsZeroDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.FLOAT32, 0));
    }

    @Test
    void vectorType_rejectsNegativeDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.FLOAT32, -1));
    }

    // ── FieldType.vector() factory ───────────────────────────────────────

    @Test
    void vectorFactory_returnsVectorType() {
        FieldType result = FieldType.vector(FieldType.Primitive.FLOAT32, 128);
        assertInstanceOf(FieldType.VectorType.class, result);
        var vt = (FieldType.VectorType) result;
        assertEquals(FieldType.Primitive.FLOAT32, vt.elementType());
        assertEquals(128, vt.dimensions());
    }

    // ── JlsmSchema.Builder.vectorField() ─────────────────────────────────

    @Test
    void schemaBuilder_vectorField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .vectorField("embedding", FieldType.Primitive.FLOAT32, 128).build();

        assertEquals(2, schema.fields().size());
        FieldDefinition embField = schema.fields().get(1);
        assertEquals("embedding", embField.name());
        assertInstanceOf(FieldType.VectorType.class, embField.type());
        var vt = (FieldType.VectorType) embField.type();
        assertEquals(FieldType.Primitive.FLOAT32, vt.elementType());
        assertEquals(128, vt.dimensions());
    }

    // ── IndexDefinition simplification ───────────────────────────────────

    @Test
    void indexDefinition_vectorWithSimilarity() {
        var def = new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE);
        assertEquals("embedding", def.fieldName());
        assertEquals(IndexType.VECTOR, def.indexType());
        assertEquals(SimilarityFunction.COSINE, def.similarityFunction());
    }

    @Test
    void indexDefinition_nonVector_twoArg() {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        assertEquals("name", def.fieldName());
        assertEquals(IndexType.EQUALITY, def.indexType());
        assertNull(def.similarityFunction());
    }

    @Test
    void indexDefinition_vectorMissingSimilarity() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition("embedding", IndexType.VECTOR, null));
    }

    // ── IndexRegistry validation ─────────────────────────────────────────

    @Test
    void indexRegistry_acceptsVectorOnVectorType() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("emb", FieldType.Primitive.FLOAT32, 128).build();

        var defs = List.of(new IndexDefinition("emb", IndexType.VECTOR, SimilarityFunction.COSINE));

        try (var registry = new IndexRegistry(schema, defs)) {
            assertFalse(registry.isEmpty());
        }
    }

    @Test
    void indexRegistry_rejectsVectorOnArrayType() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("arr", FieldType.arrayOf(FieldType.Primitive.FLOAT32)).build();

        var defs = List.of(new IndexDefinition("arr", IndexType.VECTOR, SimilarityFunction.COSINE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    @Test
    void indexRegistry_rejectsVectorOnPrimitive() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("score", FieldType.float32())
                .build();

        var defs = List
                .of(new IndexDefinition("score", IndexType.VECTOR, SimilarityFunction.COSINE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    // ── DocumentSerializer round-trip ────────────────────────────────────

    private static JlsmDocument roundTrip(JlsmSchema schema, JlsmDocument doc) {
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        return ser.deserialize(bytes);
    }

    @Test
    void documentSerializer_roundTripsFloat32Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        float[] vector = { 1.0f, -2.5f, 3.14f, 0.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vector);
        JlsmDocument out = roundTrip(schema, doc);

        assertFalse(out.isNull("vec"));
        float[] result = (float[]) out.values()[0];
        assertArrayEquals(vector, result);
    }

    @Test
    void documentSerializer_roundTripsFloat16Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();

        // Store as raw IEEE 754 half-precision bits
        short[] vector = { Float16.fromFloat(1.0f), Float16.fromFloat(-0.5f),
                Float16.fromFloat(3.0f) };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vector);
        JlsmDocument out = roundTrip(schema, doc);

        assertFalse(out.isNull("vec"));
        short[] result = (short[]) out.values()[0];
        assertArrayEquals(vector, result);
    }

    // ── JSON round-trip ─────────────────────────────────────────────────

    @Test
    void jsonRoundTrip_float32Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        float[] vector = { 1.0f, -2.5f, 3.14f, 0.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vector);

        String json = doc.toJson();
        JlsmDocument out = JlsmDocument.fromJson(json, schema);

        assertFalse(out.isNull("vec"));
        float[] result = (float[]) out.values()[0];
        assertArrayEquals(vector, result);
    }

    @Test
    void jsonRoundTrip_float16Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();

        short[] vector = { Float16.fromFloat(1.0f), Float16.fromFloat(-0.5f),
                Float16.fromFloat(3.0f) };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vector);

        String json = doc.toJson();
        JlsmDocument out = JlsmDocument.fromJson(json, schema);

        assertFalse(out.isNull("vec"));
        short[] result = (short[]) out.values()[0];
        assertArrayEquals(vector, result);
    }

    // ── YAML round-trip ─────────────────────────────────────────────────

    @Test
    void yamlRoundTrip_float32Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        float[] vector = { 1.0f, -2.5f, 3.14f, 0.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vector);

        String yaml = doc.toYaml();
        JlsmDocument out = JlsmDocument.fromYaml(yaml, schema);

        assertFalse(out.isNull("vec"));
        float[] result = (float[]) out.values()[0];
        assertArrayEquals(vector, result);
    }

    // ── Dimension validation ────────────────────────────────────────────

    @Test
    void documentOf_dimensionMismatch() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        // dimension 4 in schema but only 3 elements provided
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "vec", new float[]{ 1.0f, 2.0f, 3.0f }));
    }

    @Test
    void documentSerializer_roundTripsNullVector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "test", "vec", null);
        JlsmDocument out = roundTrip(schema, doc);

        assertEquals("test", out.getString("name"));
        assertTrue(out.isNull("vec"));
    }
}

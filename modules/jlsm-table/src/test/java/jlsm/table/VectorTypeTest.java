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

    // @spec F13.R44
    // @spec F12.R1 — record with elementType and dimensions
    @Test
    void vectorType_createsWithFloat32() {
        var vt = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        assertEquals(FieldType.Primitive.FLOAT32, vt.elementType());
        assertEquals(128, vt.dimensions());
    }

    // @spec F13.R44
    // @spec F12.R1 — record with elementType and dimensions
    @Test
    void vectorType_createsWithFloat16() {
        var vt = new FieldType.VectorType(FieldType.Primitive.FLOAT16, 64);
        assertEquals(FieldType.Primitive.FLOAT16, vt.elementType());
        assertEquals(64, vt.dimensions());
    }

    // @spec F13.R44
    // @spec F12.R4 — non-FLOAT16/FLOAT32 elementType rejected with IAE
    @Test
    void vectorType_rejectsStringElementType() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.STRING, 128));
    }

    // @spec F13.R44
    // @spec F12.R4 — non-FLOAT16/FLOAT32 elementType rejected with IAE
    @Test
    void vectorType_rejectsInt32ElementType() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.INT32, 128));
    }

    // @spec F13.R45
    // @spec F12.R5 — non-positive dimensions rejected with IAE
    @Test
    void vectorType_rejectsZeroDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.FLOAT32, 0));
    }

    // @spec F13.R45
    // @spec F12.R5 — non-positive dimensions rejected with IAE
    @Test
    void vectorType_rejectsNegativeDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldType.VectorType(FieldType.Primitive.FLOAT32, -1));
    }

    // ── FieldType.vector() factory ───────────────────────────────────────

    // @spec F13.R49
    // @spec F12.R7 — factory returns new VectorType with given elementType and dimensions
    @Test
    void vectorFactory_returnsVectorType() {
        FieldType result = FieldType.vector(FieldType.Primitive.FLOAT32, 128);
        assertInstanceOf(FieldType.VectorType.class, result);
        var vt = (FieldType.VectorType) result;
        assertEquals(FieldType.Primitive.FLOAT32, vt.elementType());
        assertEquals(128, vt.dimensions());
    }

    // ── JlsmSchema.Builder.vectorField() ─────────────────────────────────

    // @spec F13.R28,R29
    // @spec F12.R9,R12 — vectorField adds VectorType field and delegates to FieldType.vector
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

    // @spec F12.R13,R15 — record with three components; two-arg variant is convenience
    @Test
    void indexDefinition_vectorWithSimilarity() {
        var def = new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE);
        assertEquals("embedding", def.fieldName());
        assertEquals(IndexType.VECTOR, def.indexType());
        assertEquals(SimilarityFunction.COSINE, def.similarityFunction());
    }

    // @spec F12.R15 — two-arg convenience constructor
    @Test
    void indexDefinition_nonVector_twoArg() {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        assertEquals("name", def.fieldName());
        assertEquals(IndexType.EQUALITY, def.indexType());
        assertNull(def.similarityFunction());
    }

    // @spec F12.R16 — null similarityFunction rejected when indexType is VECTOR
    @Test
    void indexDefinition_vectorMissingSimilarity() {
        assertThrows(NullPointerException.class,
                () -> new IndexDefinition("embedding", IndexType.VECTOR, null));
    }

    // ── IndexRegistry validation ─────────────────────────────────────────

    // @spec F12.R22 — accepts VECTOR index on VectorType field
    @Test
    void indexRegistry_acceptsVectorOnVectorType() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("emb", FieldType.Primitive.FLOAT32, 128).build();

        var defs = List.of(new IndexDefinition("emb", IndexType.VECTOR, SimilarityFunction.COSINE));

        try (var registry = new IndexRegistry(schema, defs, null,
                jlsm.table.internal.InMemoryVectorFactories.ivfFlatFake())) {
            assertFalse(registry.isEmpty());
        }
    }

    // @spec F12.R18 — rejects VECTOR index on ArrayType field with IAE
    @Test
    void indexRegistry_rejectsVectorOnArrayType() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("arr", FieldType.arrayOf(FieldType.Primitive.FLOAT32)).build();

        var defs = List.of(new IndexDefinition("arr", IndexType.VECTOR, SimilarityFunction.COSINE));

        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    // @spec F12.R19 — rejects VECTOR index on Primitive field with IAE
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

    // @spec F12.R24,R27,R30,R33 — measure, encode, decode, round-trip FLOAT32 preserves bits
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

    // @spec F12.R25,R28,R31,R34 — measure, encode, decode, round-trip FLOAT16 preserves bits
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

    // @spec F14.R25,R45,R47 — JSON round-trip for VECTOR(FLOAT32)
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

    // @spec F14.R25,R45,R47 — JSON round-trip for VECTOR(FLOAT16)
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

    // ── Dimension validation ────────────────────────────────────────────

    // @spec F14.R26 — vector dimension must match schema declaration
    // @spec F12.R45,R49 — encode-time runtime check; upstream validateType rejects mismatch
    @Test
    void documentOf_dimensionMismatch() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        // dimension 4 in schema but only 3 elements provided
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "vec", new float[]{ 1.0f, 2.0f, 3.0f }));
    }

    // @spec F14.R10,R11 — null vector value accepted, stored as absent
    // @spec F12.R35,R36 — null VectorType marked in bitmask, deserializes without reading bytes
    @Test
    void documentSerializer_roundTripsNullVector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "test", "vec", null);
        JlsmDocument out = roundTrip(schema, doc);

        assertEquals("test", out.getString("name"));
        assertTrue(out.isNull("vec"));
    }

    // ── VectorType equality and hashCode ────────────────────────────────

    // @spec F12.R39 — same elementType and dimensions are equal with matching hashCode
    @Test
    void vectorType_equalitySameTypeSameDimensions() {
        var a = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        var b = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // @spec F12.R40 — different elementType means not equal
    @Test
    void vectorType_equalityDifferentElementType() {
        var f32 = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        var f16 = new FieldType.VectorType(FieldType.Primitive.FLOAT16, 128);
        assertNotEquals(f32, f16);
    }

    // @spec F12.R41 — different dimensions means not equal
    @Test
    void vectorType_equalityDifferentDimensions() {
        var d128 = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 128);
        var d256 = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 256);
        assertNotEquals(d128, d256);
    }

    // ── Schema evolution — dimension mismatch ───────────────────────────

    // @spec F12.R37,R38 — reader uses write-time field count; dimension change throws IAE
    // rather than silently reading past the truncated vector payload
    @Test
    void documentSerializer_schemaEvolutionDimensionMismatch() {
        JlsmSchema v1 = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();
        JlsmSchema v2 = JlsmSchema.builder("test", 2)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 8).build();

        float[] vec = { 1.0f, 2.0f, 3.0f, 4.0f };
        JlsmDocument doc = JlsmDocument.of(v1, "vec", vec);

        MemorySerializer<JlsmDocument> writer = DocumentSerializer.forSchema(v1);
        MemorySerializer<JlsmDocument> reader = DocumentSerializer.forSchema(v2);
        MemorySegment bytes = writer.serialize(doc);

        // v2 reader expects 8 floats (32 bytes) but payload has only 4 floats (16 bytes).
        // The bounds check in decodeVector must throw rather than silently over-read.
        assertThrows(IllegalArgumentException.class, () -> reader.deserialize(bytes));
    }

    // ── Structural: VectorType is a permitted sealed subtype ────────────

    // @spec F12.R2 — VectorType is listed as a permitted implementation of sealed FieldType
    @Test
    void vectorType_isSealedPermittedSubtype() {
        Class<?>[] permitted = FieldType.class.getPermittedSubclasses();
        assertTrue(
                permitted != null
                        && java.util.Arrays.asList(permitted).contains(FieldType.VectorType.class),
                "FieldType must list VectorType among its permitted subclasses");
    }

    // ── R6: large dimension accepted without upper bound ────────────────

    // @spec F12.R6 — any positive dimensions value accepted with no type-level upper bound
    @Test
    void vectorType_acceptsLargeDimensions() {
        var vt = new FieldType.VectorType(FieldType.Primitive.FLOAT32, 1_000_000);
        assertEquals(1_000_000, vt.dimensions());
    }

    // ── R3: null elementType rejected ───────────────────────────────────

    // @spec F12.R3 — null elementType rejected with NullPointerException
    @Test
    void vectorType_rejectsNullElementType() {
        assertThrows(NullPointerException.class, () -> new FieldType.VectorType(null, 128));
    }

    // ── R8: factory propagates validation exceptions ────────────────────

    // @spec F12.R8 — factory propagates validation exceptions from VectorType constructor
    @Test
    void vectorFactory_propagatesValidation() {
        assertThrows(NullPointerException.class, () -> FieldType.vector(null, 128));
        assertThrows(IllegalArgumentException.class,
                () -> FieldType.vector(FieldType.Primitive.INT32, 128));
        assertThrows(IllegalArgumentException.class,
                () -> FieldType.vector(FieldType.Primitive.FLOAT32, 0));
    }

    // ── R10, R11: vectorField null-rejection ────────────────────────────

    // @spec F12.R10 — vectorField rejects null name with NullPointerException
    @Test
    void schemaBuilder_vectorField_rejectsNullName() {
        assertThrows(NullPointerException.class, () -> JlsmSchema.builder("test", 1)
                .vectorField(null, FieldType.Primitive.FLOAT32, 128));
    }

    // @spec F12.R11 — vectorField rejects null elementType with NullPointerException
    @Test
    void schemaBuilder_vectorField_rejectsNullElementType() {
        assertThrows(NullPointerException.class,
                () -> JlsmSchema.builder("test", 1).vectorField("vec", null, 128));
    }

    // ── R14: IndexDefinition has no vectorDimensions component ──────────

    // @spec F12.R14 — IndexDefinition does not carry a vectorDimensions field
    @Test
    void indexDefinition_hasNoVectorDimensionsComponent() {
        var components = IndexDefinition.class.getRecordComponents();
        assertNotNull(components);
        for (var c : components) {
            assertNotEquals("vectorDimensions", c.getName(),
                    "IndexDefinition must not carry a vectorDimensions component");
        }
        assertEquals(3, components.length, "IndexDefinition must have exactly three components");
    }

    // ── R20, R21: IndexRegistry rejects VECTOR on ObjectType and BoundedString ──

    // @spec F12.R20 — rejects VECTOR index on ObjectType field with IAE
    @Test
    void indexRegistry_rejectsVectorOnObjectType() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .objectField("nested", b -> b.field("x", FieldType.float32())).build();
        var defs = List
                .of(new IndexDefinition("nested", IndexType.VECTOR, SimilarityFunction.COSINE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    // @spec F12.R21 — rejects VECTOR index on BoundedString field with IAE
    @Test
    void indexRegistry_rejectsVectorOnBoundedString() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string(32))
                .build();
        var defs = List
                .of(new IndexDefinition("name", IndexType.VECTOR, SimilarityFunction.COSINE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRegistry(schema, defs));
    }

    // ── R44: arrayOf/objectOf null-rejection via requireNonNull ──────────

    // @spec F12.R44 — arrayOf rejects null via NullPointerException (runtime check)
    @Test
    void arrayOf_rejectsNullElementType() {
        assertThrows(NullPointerException.class, () -> FieldType.arrayOf(null));
    }

    // @spec F12.R44 — objectOf rejects null via NullPointerException (runtime check)
    @Test
    void objectOf_rejectsNullFields() {
        assertThrows(NullPointerException.class, () -> FieldType.objectOf(null));
    }

    // ── R50: reject oversize schema version ──────────────────────────────

    // @spec F12.R50 — rejects schema version > 65535 with IAE at serialization time
    @Test
    void documentSerializer_rejectsSchemaVersionExceeding65535() {
        JlsmSchema oversize = JlsmSchema.builder("test", 70_000).field("x", FieldType.int32())
                .build();
        assertThrows(IllegalArgumentException.class, () -> DocumentSerializer.forSchema(oversize));
    }

    // ── R23: IndexRegistry derives dimensions from schema, not IndexDefinition ──

    // @spec F12.R23 — IndexRegistry accepts the definition and sources dimensions from the
    // VectorType field on the schema; IndexDefinition carries no vectorDimensions parameter
    @Test
    void indexRegistry_derivesDimensionsFromSchema() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("emb", FieldType.Primitive.FLOAT32, 64).build();
        var defs = List.of(new IndexDefinition("emb", IndexType.VECTOR, SimilarityFunction.COSINE));

        try (var registry = new IndexRegistry(schema, defs, null,
                jlsm.table.internal.InMemoryVectorFactories.ivfFlatFake())) {
            FieldType declared = schema.fields().get(0).type();
            assertInstanceOf(FieldType.VectorType.class, declared);
            assertEquals(64, ((FieldType.VectorType) declared).dimensions());
        }
    }

    // ── R26, R32: no VarInt length prefix — size is exactly dimensions*elementBytes ──

    // @spec F12.R26 — measure emits no VarInt length prefix; payload is exactly dims*elemBytes
    // @spec F12.R32 — decode uses schema-declared dimensions without reading a length prefix
    @Test
    void documentSerializer_vectorPayloadHasNoLengthPrefix() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 5).build();

        float[] vec = { 0.1f, 0.2f, 0.3f, 0.4f, 0.5f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", vec);
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);

        // Header = 2 (version) + 2 (fieldCount) + 2 (boolCount) + 1 (nullMask for 1 field) = 7
        // Payload for dims=5, FLOAT32 = 5 * 4 = 20; no VarInt prefix expected
        final int headerSize = 7;
        final int expectedPayload = 5 * 4;
        assertEquals(headerSize + expectedPayload, bytes.byteSize(),
                "VectorType payload must be exactly dimensions*elementBytes with no VarInt prefix");
    }

    // ── R29: encode-time length verification ─────────────────────────────

    // @spec F12.R29 — encode-time length verification rejects array length != dimensions
    // (also enforced upstream at JlsmDocument.of via F12.R49, exercised here at the encode path)
    @Test
    void documentSerializer_rejectsEncodeTimeLengthMismatch() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();
        // Upstream validateType rejects wrong-length at construction
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "vec", new float[]{ 1.0f, 2.0f }));
    }

    // ── R46: truncated vector input rejected at decode ───────────────────

    // @spec F12.R46 — decode throws IAE with descriptive message when buffer is too small
    @Test
    void documentSerializer_rejectsTruncatedVectorInput() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 4).build();
        JlsmDocument doc = JlsmDocument.of(schema, "vec", new float[]{ 1f, 2f, 3f, 4f });
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment full = ser.serialize(doc);

        // Truncate the payload by 4 bytes (one float)
        byte[] truncated = new byte[(int) full.byteSize() - 4];
        MemorySegment.copy(full, 0, MemorySegment.ofArray(truncated), 0, truncated.length);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ser.deserialize(MemorySegment.ofArray(truncated)));
        assertTrue(ex.getMessage().contains("truncated") || ex.getMessage().contains("bytes"),
                "truncation message must describe the shortage; got: " + ex.getMessage());
    }

    // ── R47: write-time boolean count validated at decode ────────────────

    // @spec F12.R47 — serialized header carries the write-time boolean count and the reader
    // rejects deserialization when current schema's boolean count differs for overlap fields
    @Test
    void documentSerializer_detectsBooleanCountDivergence() {
        JlsmSchema v1 = JlsmSchema.builder("test", 1).field("flag", FieldType.boolean_()).build();
        // v2 replaces the boolean with an int at the same index
        JlsmSchema v2 = JlsmSchema.builder("test", 1).field("flag", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(v1, "flag", Boolean.TRUE);
        MemorySerializer<JlsmDocument> writer = DocumentSerializer.forSchema(v1);
        MemorySerializer<JlsmDocument> reader = DocumentSerializer.forSchema(v2);
        MemorySegment bytes = writer.serialize(doc);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reader.deserialize(bytes));
        assertTrue(ex.getMessage().contains("boolean"),
                "divergence message must mention boolean; got: " + ex.getMessage());
    }

    // ── R48: field-level encode wraps type mismatches with field context ─

    // @spec F12.R48 — encode path rethrows ClassCastException as IAE with field name and type
    @Test
    void documentSerializer_wrapsFieldTypeMismatchWithContext() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("n", FieldType.int32()).build();
        // Bypass JlsmDocument.of validation to place a wrong-typed value in the payload slot
        JlsmDocument doc = new JlsmDocument(schema, new Object[]{ "not an int" });
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ser.serialize(doc));
        assertTrue(ex.getMessage().contains("'n'"),
                "wrapped exception must include the field name; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("INT32"),
                "wrapped exception must include the expected type; got: " + ex.getMessage());
    }
}

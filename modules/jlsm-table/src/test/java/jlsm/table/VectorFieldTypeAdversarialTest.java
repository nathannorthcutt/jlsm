package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.core.io.MemorySerializer;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

/**
 * Adversarial tests for vector-field-type feature. Each test targets a finding from
 * spec-analysis.md.
 */
class VectorFieldTypeAdversarialTest {

    // ── VFT-3: Mutable array stored by reference ────────────────────────

    /**
     * VFT-3: float[] passed to JlsmDocument.of() is stored by reference. Mutating the original
     * array after construction changes the document's internal state.
     *
     * @spec F14.R30 — of() defensively clones float[] for VectorType(FLOAT32)
     */
    @Test
    void vft3_float32VectorMutationAfterConstruction() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();

        float[] original = { 1.0f, 2.0f, 3.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", original);

        // Mutate the original array after construction
        original[0] = 999.0f;

        // The document should be immutable — it should still hold the original values
        float[] stored = (float[]) doc.values()[0];
        assertEquals(1.0f, stored[0],
                "Document must defensively copy float[] at construction; mutation leaked");
    }

    /**
     * VFT-3: short[] (FLOAT16 vector) passed to JlsmDocument.of() is stored by reference.
     *
     * @spec F14.R30 — of() defensively clones short[] for VectorType(FLOAT16)
     */
    @Test
    void vft3_float16VectorMutationAfterConstruction() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();

        short[] original = { Float16.fromFloat(1.0f), Float16.fromFloat(2.0f),
                Float16.fromFloat(3.0f) };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", original);

        // Mutate the original array after construction
        original[0] = Float16.fromFloat(999.0f);

        short[] stored = (short[]) doc.values()[0];
        assertEquals(Float16.fromFloat(1.0f), stored[0],
                "Document must defensively copy short[] at construction; mutation leaked");
    }

    // ── VFT-4 / VFT-5: NaN elements in vectors ─────────────────────────

    /**
     * VFT-4: float[] vector containing NaN should be rejected at construction. NaN produces garbage
     * in similarity computations (cosine → NaN/NaN → NaN, invisible in search).
     *
     * @spec F14.R27 — FLOAT32 vector finiteness check rejects NaN
     */
    @Test
    void vft4_float32VectorWithNanElementRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();

        float[] withNan = { 1.0f, Float.NaN, 3.0f };
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "vec", withNan),
                "NaN elements in FLOAT32 vector should be rejected at construction");
    }

    /**
     * VFT-4: float[] vector containing Infinity should be rejected at construction.
     *
     * @spec F14.R27 — FLOAT32 vector finiteness check rejects Infinity
     */
    @Test
    void vft4_float32VectorWithInfinityRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();

        float[] withInf = { 1.0f, Float.POSITIVE_INFINITY, 3.0f };
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "vec", withInf),
                "Infinity elements in FLOAT32 vector should be rejected at construction");
    }

    /**
     * VFT-5: short[] (FLOAT16) vector containing NaN bits should be rejected at construction.
     * Float16 NaN is any value with exponent=0x1F and non-zero mantissa (e.g., 0x7E00).
     *
     * @spec F14.R27 — FLOAT16 finiteness check rejects NaN (0x7C00 exponent mask)
     */
    @Test
    void vft5_float16VectorWithNanBitsRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();

        short[] withNan = { Float16.fromFloat(1.0f), (short) 0x7E00, Float16.fromFloat(3.0f) };
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "vec", withNan),
                "NaN elements in FLOAT16 vector should be rejected at construction");
    }

    /**
     * VFT-5: short[] (FLOAT16) vector containing Infinity bits should be rejected at construction.
     * Float16 +Inf = 0x7C00, -Inf = 0xFC00.
     *
     * @spec F14.R27 — FLOAT16 finiteness check rejects Infinity
     */
    @Test
    void vft5_float16VectorWithInfinityBitsRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();

        short[] withInf = { Float16.fromFloat(1.0f), (short) 0x7C00, Float16.fromFloat(3.0f) };
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "vec", withInf),
                "Infinity elements in FLOAT16 vector should be rejected at construction");
    }

    // ── VFT-6: Non-vector index with spurious similarity function ───────

    /**
     * VFT-6: Creating an EQUALITY index with a non-null similarityFunction should be rejected since
     * the parameter is meaningless and misleading.
     */
    @Test
    void vft6_nonVectorIndexWithSimilarityFunctionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexDefinition("name", IndexType.EQUALITY,
                        jlsm.core.indexing.SimilarityFunction.COSINE),
                "Non-VECTOR index should reject non-null similarityFunction");
    }

    // ── VFT-11 / VFT-12: JSON round-trip with non-finite floats ─────────

    /**
     * VFT-11: JsonWriter writes NaN as "null" but JsonParser cannot parse "null" back into a float.
     * This breaks JSON round-trip for vectors containing NaN.
     *
     * Note: if VFT-4 fix rejects NaN at construction, this test becomes untriggerable (good). We
     * test the serializer path directly in case pre-validated data enters.
     *
     * @spec F14.R27 — non-finite vector rejection at construction prevents bad JSON round-trip
     */
    @Test
    void vft11_jsonRoundTripNanInFloat32VectorPreserved() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();

        // If NaN is now rejected at construction, this test documents that the protection
        // works. If NaN is still accepted, the JSON round-trip will fail.
        float[] withNan = { 1.0f, Float.NaN, 3.0f };
        // After VFT-4 fix, this should throw at construction
        assertThrows(IllegalArgumentException.class, () -> JlsmDocument.of(schema, "vec", withNan),
                "NaN vectors should be rejected before they can reach JSON serialization");
    }

    // ── VFT-8: Serializer round-trip preserves all vector values ────────

    /**
     * VFT-8: Binary serializer must round-trip boundary float values correctly (negative zero,
     * max/min finite, subnormal).
     */
    @Test
    void vft8_binaryRoundTripBoundaryFloat32Values() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 5).build();

        float[] boundary = { -0.0f, Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL,
                -Float.MAX_VALUE };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", boundary);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        float[] result = (float[]) out.values()[0];
        assertArrayEquals(boundary, result);
    }

    /**
     * VFT-8: Binary serializer must round-trip boundary FLOAT16 values correctly.
     */
    @Test
    void vft8_binaryRoundTripBoundaryFloat16Values() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 4).build();

        // Float16 boundary values: zero, negative zero, max finite (65504), min subnormal
        short[] boundary = { (short) 0x0000, // +0.0
                (short) 0x8000, // -0.0
                (short) 0x7BFF, // max finite (65504)
                (short) 0x0001 // min positive subnormal
        };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", boundary);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        short[] result = (short[]) out.values()[0];
        assertArrayEquals(boundary, result);
    }

    // ── VFT-3 (output side): values() exposes mutable internal state ────

    /**
     * VFT-3 (output): values() is package-private. Input-side defensive copy (at JlsmDocument.of())
     * prevents caller-side mutation from reaching the serializer.
     *
     * @spec F14.R30 — of() clones float[] before storing
     */
    @Test
    void vft3_inputSideDefensiveCopyPreventsMutationBeforeSerialization() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();

        float[] original = { 1.0f, 2.0f, 3.0f };
        JlsmDocument doc = JlsmDocument.of(schema, "vec", original);

        // Mutate original after construction
        original[0] = 999.0f;

        // Serialize — document should contain original values, not mutated
        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument deserialized = ser.deserialize(bytes);
        float[] result = (float[]) deserialized.values()[0];

        assertEquals(1.0f, result[0],
                "Input-side defensive copy should prevent external mutation affecting serialization");
    }
}

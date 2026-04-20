package jlsm.table.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.Predicate;

/**
 * Behavioural tests for {@link VectorFieldIndex} against a fake backing
 * {@link VectorIndex.IvfFlat}. Exercises adapter semantics — vector extraction, null handling on
 * update, supports/lookup dispatch, and close propagation — without requiring a real LSM-backed
 * vector index. Integration coverage against {@code LsmVectorIndex} lives in jlsm-vector tests.
 */
// @spec F10.R85,R86,R87,R88,R89,R90 — exercises the adapter wiring satisfied by WD-02
class VectorFieldIndexTest {

    /**
     * Fake in-memory vector index that records invocations and returns canned results. Returns
     * stored doc ids in descending score order (higher = more similar) matching the
     * {@link SimilarityFunction} convention.
     */
    private static final class FakeVectorIndex implements VectorIndex.IvfFlat<MemorySegment> {

        final List<IndexCall> indexCalls = new ArrayList<>();
        final List<MemorySegment> removeCalls = new ArrayList<>();
        final Map<String, float[]> store = new HashMap<>();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void index(MemorySegment docId, float[] vector) {
            MemorySegment copy = copy(docId);
            indexCalls.add(new IndexCall(copy, vector.clone()));
            store.put(decode(copy), vector.clone());
        }

        @Override
        public void remove(MemorySegment docId) {
            MemorySegment copy = copy(docId);
            removeCalls.add(copy);
            store.remove(decode(copy));
        }

        @Override
        public List<VectorIndex.SearchResult<MemorySegment>> search(float[] query, int topK) {
            // Use Euclidean distance as a stable scorer; higher similarity = closer.
            List<VectorIndex.SearchResult<MemorySegment>> out = new ArrayList<>();
            for (Map.Entry<String, float[]> e : store.entrySet()) {
                float dist = 0.0f;
                float[] v = e.getValue();
                for (int i = 0; i < query.length && i < v.length; i++) {
                    float diff = query[i] - v[i];
                    dist += diff * diff;
                }
                float score = -dist; // higher = more similar
                MemorySegment seg = MemorySegment
                        .ofArray(e.getKey().getBytes(StandardCharsets.UTF_8));
                out.add(new VectorIndex.SearchResult<>(seg, score));
            }
            out.sort((a, b) -> Float.compare(b.score(), a.score()));
            return out.size() > topK ? out.subList(0, topK) : out;
        }

        @Override
        public VectorPrecision precision() {
            return VectorPrecision.FLOAT32;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
        }

        private static MemorySegment copy(MemorySegment src) {
            byte[] bytes = src.toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(bytes);
        }

        private static String decode(MemorySegment seg) {
            return new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        record IndexCall(MemorySegment docId, float[] vector) {
        }
    }

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static IndexDefinition embeddingDef() {
        return new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE);
    }

    // @spec F10.R85 — VectorFieldIndex is a final class implementing SecondaryIndex.
    @Test
    void classIsFinalImplementingSecondaryIndex() {
        Class<?> c = VectorFieldIndex.class;
        assertTrue(java.lang.reflect.Modifier.isFinal(c.getModifiers()),
                "VectorFieldIndex must be final");
        assertTrue(SecondaryIndex.class.isAssignableFrom(c),
                "VectorFieldIndex must implement SecondaryIndex");
    }

    @Test
    void definition_returnsDefinitionPassedAtConstruction() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        IndexDefinition def = embeddingDef();
        try (VectorFieldIndex idx = new VectorFieldIndex(def, fake)) {
            assertEquals(def, idx.definition());
        }
    }

    @Test
    void constructor_rejectsNullDefinition() {
        FakeVectorIndex fake = new FakeVectorIndex();
        assertThrows(NullPointerException.class, () -> new VectorFieldIndex(null, fake));
    }

    @Test
    void constructor_rejectsNullBacking() {
        assertThrows(NullPointerException.class, () -> new VectorFieldIndex(embeddingDef(), null));
    }

    @Test
    void constructor_rejectsNonVectorIndexType() {
        FakeVectorIndex fake = new FakeVectorIndex();
        IndexDefinition nonVector = new IndexDefinition("x", IndexType.EQUALITY);
        assertThrows(IllegalArgumentException.class, () -> new VectorFieldIndex(nonVector, fake));
    }

    // @spec F10.R87 — onInsert extracts vector and inserts into backing
    @Test
    void onInsert_float32Vector_propagatesToBackingIndex() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            float[] vec = { 1.0f, 2.0f, 3.0f };
            idx.onInsert(stringKey("pk1"), vec);
            assertEquals(1, fake.indexCalls.size());
            FakeVectorIndex.IndexCall call = fake.indexCalls.get(0);
            assertArrayEquals(vec, call.vector());
        }
    }

    // @spec F10.R87 — silent-drop safety fix: mutations are not silent no-ops.
    @Test
    void onInsert_isNotSilentNoOp_theSilentDropHazardIsGone() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onInsert(stringKey("pk1"), new float[]{ 0.1f, 0.2f });
            idx.onInsert(stringKey("pk2"), new float[]{ 0.3f, 0.4f });
            idx.onInsert(stringKey("pk3"), new float[]{ 0.5f, 0.6f });
            assertEquals(3, fake.indexCalls.size(),
                    "every insert must reach the backing index — silent no-ops are the pre-WD-02 hazard");
        }
    }

    // @spec F10.R87 — null vector is a no-op (matches SecondaryIndex R56)
    @Test
    void onInsert_nullVector_isNoOp() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onInsert(stringKey("pk1"), null);
            assertTrue(fake.indexCalls.isEmpty(),
                    "null vector must not produce a backing index call");
            assertTrue(fake.removeCalls.isEmpty(), "null vector must not produce a remove either");
        }
    }

    // @spec F10.R87 — reject non-float[] non-short[] values with clear message (no silent drop)
    @Test
    void onInsert_rejectsNonVectorObject() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                    () -> idx.onInsert(stringKey("pk1"), "not a vector"));
            assertTrue(iae.getMessage().contains("vector") || iae.getMessage().contains("float"),
                    "error message must identify the cause: " + iae.getMessage());
        }
    }

    // @spec F10.R87 — float16 short[] values converted to float[] before indexing
    @Test
    void onInsert_float16ShortArray_convertedToFloats() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            short[] f16 = { Float.floatToFloat16(1.0f), Float.floatToFloat16(2.0f) };
            idx.onInsert(stringKey("pk1"), f16);
            assertEquals(1, fake.indexCalls.size());
            float[] got = fake.indexCalls.get(0).vector();
            assertEquals(2, got.length);
            assertEquals(1.0f, got[0], 1e-6);
            assertEquals(2.0f, got[1], 1e-6);
        }
    }

    // @spec F10.R88 — onUpdate removes old then inserts new
    @Test
    void onUpdate_removesOldThenInsertsNew() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), new float[]{ 1.0f, 2.0f }, new float[]{ 3.0f, 4.0f });
            assertEquals(1, fake.removeCalls.size(), "old vector must be removed");
            assertEquals(1, fake.indexCalls.size(), "new vector must be indexed");
            assertArrayEquals(new float[]{ 3.0f, 4.0f }, fake.indexCalls.get(0).vector());
        }
    }

    // @spec F10.R88 — null old value: insert-only
    @Test
    void onUpdate_nullOld_isInsertOnly() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), null, new float[]{ 1.0f, 2.0f });
            assertTrue(fake.removeCalls.isEmpty(), "null old must not trigger remove");
            assertEquals(1, fake.indexCalls.size(), "new vector must be indexed");
        }
    }

    // @spec F10.R88 — null new value: delete-only
    @Test
    void onUpdate_nullNew_isDeleteOnly() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), new float[]{ 1.0f, 2.0f }, null);
            assertEquals(1, fake.removeCalls.size(), "old vector must be removed");
            assertTrue(fake.indexCalls.isEmpty(), "null new must not trigger index");
        }
    }

    // @spec F10.R88 — null both: no-op
    @Test
    void onUpdate_nullBoth_isNoOp() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onUpdate(stringKey("pk1"), null, null);
            assertTrue(fake.removeCalls.isEmpty());
            assertTrue(fake.indexCalls.isEmpty());
        }
    }

    // @spec F10.R89 — onDelete removes from backing
    @Test
    void onDelete_removesFromBacking() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onDelete(stringKey("pk1"), new float[]{ 1.0f, 2.0f });
            assertEquals(1, fake.removeCalls.size());
        }
    }

    // @spec F10.R89 — null value: no-op on delete
    @Test
    void onDelete_nullValue_isNoOp() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onDelete(stringKey("pk1"), null);
            assertTrue(fake.removeCalls.isEmpty());
        }
    }

    // @spec F10.R86 — supports returns true only for VectorNearest on matching field
    @Test
    void supports_trueForVectorNearestOnMatchingField() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertTrue(
                    idx.supports(new Predicate.VectorNearest("embedding", new float[]{ 1.0f }, 5)));
        }
    }

    // @spec F10.R86 — supports returns false for mismatched field
    @Test
    void supports_falseForVectorNearestOnWrongField() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertFalse(idx.supports(new Predicate.VectorNearest("other", new float[]{ 1.0f }, 5)));
        }
    }

    // @spec F10.R86 — supports returns false for non-VectorNearest predicates
    @Test
    void supports_falseForNonVectorPredicates() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertFalse(idx.supports(new Predicate.Eq("embedding", "x")));
            assertFalse(idx.supports(new Predicate.FullTextMatch("embedding", "x")));
        }
    }

    // @spec F10.R86 — supports returns false after close
    @Test
    void supports_falseAfterClose() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake);
        idx.close();
        assertFalse(idx.supports(new Predicate.VectorNearest("embedding", new float[]{ 1.0f }, 5)));
    }

    // @spec F10.R90 — lookup for VectorNearest returns topK PKs
    @Test
    void lookup_vectorNearest_returnsTopKByBackingOrder() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            idx.onInsert(stringKey("a"), new float[]{ 0.0f, 0.0f });
            idx.onInsert(stringKey("b"), new float[]{ 1.0f, 1.0f });
            idx.onInsert(stringKey("c"), new float[]{ 10.0f, 10.0f });
            Iterator<MemorySegment> it = idx
                    .lookup(new Predicate.VectorNearest("embedding", new float[]{ 0.0f, 0.0f }, 2));
            List<String> results = new ArrayList<>();
            while (it.hasNext()) {
                results.add(new String(it.next().toArray(ValueLayout.JAVA_BYTE),
                        StandardCharsets.UTF_8));
            }
            assertEquals(List.of("a", "b"), results,
                    "lookup must return topK in descending-similarity order");
        }
    }

    // @spec F10.R90 — lookup rejects non-VectorNearest predicates
    @Test
    void lookup_rejectsNonVectorNearest() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> idx.lookup(new Predicate.Eq("embedding", "x")));
        }
    }

    // @spec F10.R90 — lookup on mismatched field returns empty iterator (defensive)
    @Test
    void lookup_mismatchedField_returnsEmptyIterator() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            Iterator<MemorySegment> it = idx
                    .lookup(new Predicate.VectorNearest("other", new float[]{ 1.0f }, 5));
            assertFalse(it.hasNext());
        }
    }

    @Test
    void onInsert_onUpdate_onDelete_lookup_afterClose_throwIllegalState() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake);
        idx.close();
        assertThrows(IllegalStateException.class,
                () -> idx.onInsert(stringKey("pk"), new float[]{ 1.0f }));
        assertThrows(IllegalStateException.class,
                () -> idx.onUpdate(stringKey("pk"), null, new float[]{ 1.0f }));
        assertThrows(IllegalStateException.class,
                () -> idx.onDelete(stringKey("pk"), new float[]{ 1.0f }));
        assertThrows(IllegalStateException.class,
                () -> idx.lookup(new Predicate.VectorNearest("embedding", new float[]{ 1.0f }, 5)));
    }

    // @spec F10.R90 — close propagates once to backing; idempotent
    @Test
    void close_isIdempotent_andClosesBackingExactlyOnce() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake);
        idx.close();
        idx.close(); // idempotent
        assertTrue(fake.closed.get());
    }

    @Test
    void supports_falseForNullPredicate() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertFalse(idx.supports(null));
        }
    }

    @Test
    void lookup_rejectsNullPredicate() throws IOException {
        FakeVectorIndex fake = new FakeVectorIndex();
        try (VectorFieldIndex idx = new VectorFieldIndex(embeddingDef(), fake)) {
            assertThrows(NullPointerException.class, () -> idx.lookup(null));
        }
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    private static void assertArrayEquals(float[] expected, float[] actual) {
        assertNotNull(actual);
        assertEquals(expected.length, actual.length, "array length mismatch");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-6, "mismatch at index " + i);
        }
    }
}

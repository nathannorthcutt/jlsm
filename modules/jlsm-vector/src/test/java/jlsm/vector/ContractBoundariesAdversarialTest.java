package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorPrecision;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ContractBoundariesAdversarialTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private static final MemorySerializer<Long> LONG_DOC_ID_SERIALIZER = new MemorySerializer<>() {
        @Override
        public MemorySegment serialize(Long value) {
            byte[] bytes = new byte[8];
            long v = value;
            for (int i = 7; i >= 0; i--) {
                bytes[i] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public Long deserialize(MemorySegment segment) {
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            long v = 0L;
            for (byte b : bytes)
                v = (v << 8) | (b & 0xFFL);
            return v;
        }
    };

    private LsmTree createLsmTree() throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory(
                        (SSTableWriterFactory) (id, level, path) -> new TrieSSTableWriter(id, level,
                                path, PassthroughBloomFilter.factory()))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(Long.MAX_VALUE).build();
    }

    // Finding: F-R5.cb.1.1
    // Bug: AbstractBuilder.validateBase() does not check precision for null
    // Correct behavior: validateBase() should throw NullPointerException with message when
    // precision is null
    // Fix location: LsmVectorIndex.java, AbstractBuilder.validateBase() method (lines 337-344)
    // Regression watch: ensure precision null-check uses Objects.requireNonNull with clear message
    @Test
    void test_AbstractBuilder_validateBase_precision_null_check() throws Exception {
        LsmTree lsmTree = createLsmTree();

        LsmVectorIndex.IvfFlat.Builder<Long> builder = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(lsmTree).docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.COSINE);

        // Use reflection to null out the precision field, simulating a code path
        // where precision is not set and the default is missing or cleared.
        Field precisionField = findField(builder.getClass(), "precision");
        precisionField.setAccessible(true);
        precisionField.set(builder, null);

        // validateBase() (called by build()) should catch null precision with
        // a NullPointerException, not let it pass through to the constructor's assert.
        NullPointerException ex = assertThrows(NullPointerException.class, builder::build);
        assertTrue(ex.getMessage().contains("precision"),
                "Expected message to mention 'precision', got: " + ex.getMessage());

        lsmTree.close();
    }

    // Finding: F-R5.cb.1.2
    // Bug: IvfFlat and Hnsw constructors guard all parameters with assert only — no runtime
    // validation.
    // With assertions disabled, null/invalid parameters are silently accepted.
    // Correct behavior: Constructors should throw NullPointerException/IllegalArgumentException
    // at construction time, not AssertionError.
    // Fix location: LsmVectorIndex.java, IvfFlat constructor (lines 393-409), Hnsw constructor
    // (lines 742-761)
    // Regression watch: Ensure runtime checks use Objects.requireNonNull and explicit IAE, not
    // assert
    @Test
    void test_IvfFlat_constructor_rejects_null_precision_with_runtime_check() throws Exception {
        LsmTree lsmTree = createLsmTree();

        // Build a valid IvfFlat via the builder, then use reflection to inspect
        // that the constructor performs runtime validation (not just assert).
        // We call the private constructor directly via reflection with null precision.
        var ivfFlatClass = Class.forName("jlsm.vector.LsmVectorIndex$IvfFlat");
        var constructor = ivfFlatClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        // Invoke with null precision — should throw NullPointerException (runtime check),
        // not AssertionError (assert-only check).
        try {
            constructor.newInstance(lsmTree, LONG_DOC_ID_SERIALIZER, 4, SimilarityFunction.COSINE,
                    8, 2, null);
            fail("Constructor should reject null precision");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(NullPointerException.class, cause,
                    "Expected NullPointerException for null precision, got "
                            + cause.getClass().getName());
            assertTrue(cause.getMessage().contains("precision"),
                    "Expected message to mention 'precision', got: " + cause.getMessage());
        }

        lsmTree.close();
    }

    @Test
    void test_Hnsw_constructor_rejects_null_precision_with_runtime_check() throws Exception {
        LsmTree lsmTree = createLsmTree();

        var hnswClass = Class.forName("jlsm.vector.LsmVectorIndex$Hnsw");
        var constructor = hnswClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        // Invoke with null precision — should throw NullPointerException (runtime check),
        // not AssertionError (assert-only check).
        try {
            constructor.newInstance(lsmTree, LONG_DOC_ID_SERIALIZER, 4, SimilarityFunction.COSINE,
                    16, 200, 50, null);
            fail("Constructor should reject null precision");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(NullPointerException.class, cause,
                    "Expected NullPointerException for null precision, got "
                            + cause.getClass().getName());
            assertTrue(cause.getMessage().contains("precision"),
                    "Expected message to mention 'precision', got: " + cause.getMessage());
        }

        lsmTree.close();
    }

    // Finding: F-R5.contract_boundaries.2.1
    // Bug: IvfFlat.search does not filter Infinity scores — the NaN filter at line 534
    // only catches Float.isNaN(s), allowing Float.POSITIVE_INFINITY and
    // Float.NEGATIVE_INFINITY to propagate through ScoredCandidate into results.
    // Correct behavior: Infinity scores should be filtered (skipped) during accumulation,
    // just like NaN scores are, so that only finite scores reach search results.
    // Fix location: LsmVectorIndex.java, IvfFlat.search, around line 534 (the NaN filter)
    // Regression watch: ensure both positive and negative infinity are filtered, not just one
    @Test
    void test_IvfFlat_search_filters_infinity_scores() throws Exception {
        LsmTree lsmTree = createLsmTree();

        // Use DOT_PRODUCT with FLOAT32 precision so that large component values
        // cause score overflow to Float.POSITIVE_INFINITY.
        var index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(lsmTree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT).numClusters(1).nprobe(1)
                .precision(VectorPrecision.FLOAT32).build();

        // Insert a vector with large components that will overflow dot product to Infinity.
        // (Float.MAX_VALUE/2)^2 * 4 dimensions overflows float range.
        float big = Float.MAX_VALUE / 2;
        float[] bigVec = { big, big, big, big };
        index.index(1L, bigVec);

        // Also insert a normal vector that will produce a finite score.
        float[] normalVec = { 1.0f, 0.0f, 0.0f, 0.0f };
        index.index(2L, normalVec);

        // Search with a query that triggers Infinity score against bigVec.
        float[] query = { big, big, big, big };
        var results = index.search(query, 10);

        // All returned scores must be finite — Infinity scores must be filtered out.
        for (var result : results) {
            assertFalse(Float.isInfinite(result.score()),
                    "Search result should not contain Infinity score, got: " + result.score()
                            + " for docId=" + result.docId());
        }

        index.close();
    }

    // Finding: F-R5.contract_boundaries.2.2
    // Bug: Hnsw.search passes Infinity scores to SearchResult, violating its no-Infinity contract.
    // searchLayer filters NaN but not Infinity; search output loop has no Infinity filter.
    // Correct behavior: Infinity scores should be filtered during accumulation in searchLayer
    // and in the search output loop, so only finite scores reach SearchResult.
    // Fix location: LsmVectorIndex.java, Hnsw.searchLayer (lines 1003, 1024) and Hnsw.search (line
    // 919)
    // Regression watch: ensure both entry score and neighbor scores filter Infinity, not just NaN
    @Test
    void test_Hnsw_search_filters_infinity_scores() throws Exception {
        LsmTree lsmTree = createLsmTree();

        // Use DOT_PRODUCT with FLOAT32 precision so that large component values
        // cause score overflow to Float.POSITIVE_INFINITY.
        var index = LsmVectorIndex.<Long>hnswBuilder().lsmTree(lsmTree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT).maxConnections(4)
                .efConstruction(10).efSearch(10).precision(VectorPrecision.FLOAT32).build();

        // Insert a vector with large components that will overflow dot product to Infinity.
        // (Float.MAX_VALUE/2)^2 * 4 dimensions overflows float range.
        float big = Float.MAX_VALUE / 2;
        float[] bigVec = { big, big, big, big };
        index.index(1L, bigVec);

        // Also insert a normal vector that will produce a finite score.
        float[] normalVec = { 1.0f, 0.0f, 0.0f, 0.0f };
        index.index(2L, normalVec);

        // Search with a query that triggers Infinity score against bigVec.
        float[] query = { big, big, big, big };
        var results = index.search(query, 10);

        // All returned scores must be finite — Infinity scores must be filtered out.
        for (var result : results) {
            assertFalse(Float.isInfinite(result.score()),
                    "Search result should not contain Infinity score, got: " + result.score()
                            + " for docId=" + result.docId());
        }

        index.close();
    }

    // Finding: F-R5.contract_boundaries.2.3
    // Bug: Infinity scores in Hnsw.searchLayer corrupt heap ordering, displacing valid
    // finite-scored candidates from the bounded results heap. When ef equals topK,
    // an Infinity candidate permanently occupies a slot, causing a valid result to be lost.
    // Correct behavior: Infinity scores must be filtered before insertion into the results
    // heap so that all ef slots are available for finite-scored candidates.
    // Fix location: LsmVectorIndex.java, Hnsw.searchLayer — entry score guard and neighbor
    // score guard (lines ~1006 and ~1028)
    // Regression watch: if the isFinite guard is weakened to isNaN-only, valid candidates
    // will be silently displaced by Infinity entries in bounded heaps
    @Test
    void test_Hnsw_searchLayer_infinity_does_not_displace_valid_candidates() throws Exception {
        LsmTree lsmTree = createLsmTree();

        // Use DOT_PRODUCT with FLOAT32 so large components overflow to Infinity.
        // Set efSearch = topK = 2 so the results heap is tightly bounded — any
        // Infinity entry that occupies a slot would displace a valid candidate.
        var index = LsmVectorIndex.<Long>hnswBuilder().lsmTree(lsmTree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT).maxConnections(4)
                .efConstruction(10).efSearch(2).precision(VectorPrecision.FLOAT32).build();

        // Insert two normal vectors that produce finite dot-product scores.
        float[] normalVec1 = { 1.0f, 0.0f, 0.0f, 0.0f };
        float[] normalVec2 = { 0.0f, 1.0f, 0.0f, 0.0f };
        index.index(1L, normalVec1);
        index.index(2L, normalVec2);

        // Insert a vector with extreme components: dot product with query overflows to Infinity.
        float big = Float.MAX_VALUE / 2;
        float[] bigVec = { big, big, big, big };
        index.index(3L, bigVec);

        // Query designed so that bigVec dot query = 4 * (MAX_VALUE/2)^2 → +Infinity,
        // while normalVec1 and normalVec2 produce finite scores.
        float[] query = { big, big, big, big };
        List<jlsm.core.indexing.VectorIndex.SearchResult<Long>> results = index.search(query, 2);

        // Both finite-scored candidates must be returned — neither should be
        // displaced by the Infinity-scored candidate.
        assertEquals(2, results.size(), "Expected 2 valid results but got " + results.size()
                + "; Infinity candidate likely displaced a valid candidate from the heap");

        // All returned scores must be finite.
        for (var result : results) {
            assertTrue(Float.isFinite(result.score()),
                    "Result score must be finite, got: " + result.score());
        }

        index.close();
    }

    // Finding: F-R5.contract_boundaries.2.4
    // Bug: ScoredCandidate record accepts any float score (NaN, Infinity) without validation,
    // creating a contract gap between score computation and SearchResult
    // Correct behavior: ScoredCandidate should reject non-finite scores with
    // IllegalArgumentException
    // Fix location: LsmVectorIndex.java, ScoredCandidate record (line ~287)
    // Regression watch: ensure frontier entry in searchLayer handles non-finite entry scores
    // without creating a ScoredCandidate with non-finite score
    @Test
    void test_ScoredCandidate_rejects_infinity_score() throws Exception {
        // ScoredCandidate is a private record inside LsmVectorIndex.
        // Use reflection to verify it enforces the score-finiteness contract.
        var scoredCandidateClass = Class.forName("jlsm.vector.LsmVectorIndex$ScoredCandidate");
        var constructor = scoredCandidateClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        byte[] dummyDocId = new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 };

        // Positive Infinity should be rejected
        try {
            constructor.newInstance(dummyDocId, Float.POSITIVE_INFINITY);
            fail("ScoredCandidate should reject POSITIVE_INFINITY score");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(IllegalArgumentException.class, cause,
                    "Expected IllegalArgumentException for Infinity score, got "
                            + cause.getClass().getName());
        }

        // Negative Infinity should be rejected
        try {
            constructor.newInstance(dummyDocId, Float.NEGATIVE_INFINITY);
            fail("ScoredCandidate should reject NEGATIVE_INFINITY score");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(IllegalArgumentException.class, cause,
                    "Expected IllegalArgumentException for -Infinity score, got "
                            + cause.getClass().getName());
        }

        // NaN should be rejected
        try {
            constructor.newInstance(dummyDocId, Float.NaN);
            fail("ScoredCandidate should reject NaN score");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(IllegalArgumentException.class, cause,
                    "Expected IllegalArgumentException for NaN score, got "
                            + cause.getClass().getName());
        }

        // Finite scores should be accepted
        assertDoesNotThrow(() -> constructor.newInstance(dummyDocId, 0.5f));
        assertDoesNotThrow(() -> constructor.newInstance(dummyDocId, -1.0f));
        assertDoesNotThrow(() -> constructor.newInstance(dummyDocId, 0.0f));
    }

    /**
     * Walk the class hierarchy to find a field by name (handles inherited fields).
     */
    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}

package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests — Round 2. Targets interaction-level bugs missed by Round 1.
 */
class LsmVectorIndexFloat16AdversarialRound2Test {

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

    private LsmTree buildTree(long flushThreshold) throws IOException {
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
                .memTableFlushThresholdBytes(flushThreshold).build();
    }

    // =======================================================================
    // F13: Hnsw index() after remove() — soft-delete tombstone not cleared
    // =======================================================================

    @Test
    void hnsw_float16_removeAndReindex_docVisibleInSearch() throws IOException {
        // F13: After remove(docId) + index(docId, newVec), doc should be searchable.
        // Bug: index() does not delete the [0xFF][docId] soft-delete key, so
        // search() still filters it out.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f });

            // Remove doc 1
            index.remove(1L);

            // Re-index doc 1 with a new vector
            index.index(1L, new float[]{ 0.7f, 0.7f });

            // Search — doc 1 should appear in results
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 0.7f, 0.7f }, 2);

            boolean doc1Found = results.stream().anyMatch(r -> r.docId().equals(1L));
            assertTrue(doc1Found,
                    "doc 1 should be visible after remove + re-index, but soft-delete tombstone persists");
        }
    }

    @Test
    void hnsw_float32_removeAndReindex_docVisibleInSearch() throws IOException {
        // F13 (fix-forward): Same bug exists in float32 path — verify fix applies to both precisions.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT32).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f });

            index.remove(1L);
            index.index(1L, new float[]{ 0.7f, 0.7f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 0.7f, 0.7f }, 2);

            boolean doc1Found = results.stream().anyMatch(r -> r.docId().equals(1L));
            assertTrue(doc1Found,
                    "doc 1 should be visible after remove + re-index (float32 fix-forward)");
        }
    }

    // =======================================================================
    // F14: NaN vector components corrupt search result ordering
    // =======================================================================

    @Test
    void ivfFlat_float16_nanVectorDoesNotCorruptOtherResults() throws IOException {
        // F14: A stored vector with NaN component produces NaN score via cosine.
        // NaN should not displace legitimate results at the top of the heap.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            // Index a normal vector
            index.index(1L, new float[]{ 1.0f, 0.0f });

            // Index a vector with NaN — produces NaN score against any query
            index.index(2L, new float[]{ Float.NaN, 0.0f });

            // Search with a normal query
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "search should return results");

            // The first result should be doc 1 (exact match), NOT doc 2 (NaN score).
            // If NaN corrupts ordering, doc 2 appears first.
            VectorIndex.SearchResult<Long> top = results.get(0);
            assertFalse(Float.isNaN(top.score()),
                    "top result should not have NaN score — NaN entries should be ranked last or filtered");
            assertEquals(1L, top.docId(),
                    "exact match (doc 1) should be top result, not NaN-scored doc 2");
        }
    }

    @Test
    void hnsw_float16_nanVectorDoesNotCorruptOtherResults() throws IOException {
        // F14: Same NaN ordering corruption check for Hnsw.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ Float.NaN, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "search should return results");
            VectorIndex.SearchResult<Long> top = results.get(0);
            assertFalse(Float.isNaN(top.score()),
                    "top result should not have NaN score in Hnsw search");
            assertEquals(1L, top.docId(),
                    "exact match (doc 1) should be top result, not NaN-scored doc 2");
        }
    }

    // =======================================================================
    // F15: Float16 overflow → infinity → NaN cosine → corrupted search
    // =======================================================================

    @Test
    void ivfFlat_float16_overflowVectorDoesNotCorruptSearchResults() throws IOException {
        // F15: A vector with components > 65504 overflows to infinity in float16.
        // cosine([inf, 0], [inf, 0]) = inf/inf = NaN. This triggers F14.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            // Normal vector
            index.index(1L, new float[]{ 1.0f, 0.0f });

            // Overflow vector: 100000 → infinity in float16
            index.index(2L, new float[]{ 100000.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "search should return results");
            VectorIndex.SearchResult<Long> top = results.get(0);
            assertFalse(Float.isNaN(top.score()),
                    "top result should not have NaN score from overflow → infinity → NaN cosine");
            assertEquals(1L, top.docId(),
                    "normal vector (doc 1) should be top result, not overflow-corrupted doc 2");
        }
    }

    @Test
    void hnsw_float16_overflowVectorDoesNotCorruptSearchResults() throws IOException {
        // F15: Same overflow → NaN cosine check for Hnsw.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 100000.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "search should return results");
            VectorIndex.SearchResult<Long> top = results.get(0);
            assertFalse(Float.isNaN(top.score()),
                    "top result should not have NaN score from Hnsw overflow path");
            assertEquals(1L, top.docId(),
                    "normal vector (doc 1) should be top result");
        }
    }

    // =======================================================================
    // F14 (NaN query vector): Query-side NaN should not produce NaN results
    // =======================================================================

    @Test
    void ivfFlat_float16_nanQueryDoesNotProduceNanTopResult() throws IOException {
        // F14: NaN in query vector — all scores become NaN. Results should be empty
        // or all NaN, but not silently corrupted.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f });

            // Query with NaN — all scores will be NaN
            List<VectorIndex.SearchResult<Long>> results =
                    index.search(new float[]{ Float.NaN, 0.0f }, 2);

            // With NaN query, all scores are NaN. We accept results being returned,
            // but verify they are not ranked above a hypothetical finite score.
            // The key assertion: scores should all be NaN (consistent), not a mix
            // of NaN and finite.
            for (VectorIndex.SearchResult<Long> r : results) {
                assertTrue(Float.isNaN(r.score()) || !Float.isFinite(r.score()),
                        "with NaN query, all result scores should be NaN or non-finite, got: "
                                + r.score());
            }
        }
    }
}

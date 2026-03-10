package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Level;
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

class LsmVectorIndexTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Doc-ID serializer: big-endian 8-byte Long
    // -----------------------------------------------------------------------

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
            for (byte b : bytes) v = (v << 8) | (b & 0xFFL);
            return v;
        }
    };

    // -----------------------------------------------------------------------
    // Tree factory helper
    // -----------------------------------------------------------------------

    private LsmTree buildTree(long flushThreshold) throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level, path) ->
                        new TrieSSTableWriter(id, level, path, PassthroughBloomFilter.factory()))
                .sstableReaderFactory((SSTableReaderFactory) path ->
                        TrieSSTableReader.open(path, PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThreshold)
                .build();
    }

    // -----------------------------------------------------------------------
    // IvfFlat tests
    // -----------------------------------------------------------------------

    @Test
    void ivfFlat_indexThenSearchReturnsExactMatch() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 1);

            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).docId());
            assertEquals(1.0f, results.get(0).score(), 0.001f);
        }
    }

    @Test
    void ivfFlat_searchReturnsTopKNearest() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .numClusters(10)
                .build()) {

            for (long i = 0; i < 5; i++) {
                index.index(i, new float[]{(float) i, 1.0f});
            }
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 1.0f}, 3);

            assertEquals(3, results.size());
        }
    }

    @Test
    void ivfFlat_resultsOrderedByDescendingScore() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .numClusters(10)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.0f, 1.0f});
            index.index(3L, new float[]{-1.0f, 0.0f});

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 3);

            assertEquals(3, results.size());
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).score() >= results.get(i + 1).score(),
                        "scores must be in descending order");
            }
        }
    }

    @Test
    void ivfFlat_removeDocExcludedFromSearch() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.9f, 0.1f});

            index.remove(2L);

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 10);
            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).docId());
        }
    }

    @Test
    void ivfFlat_euclideanSimilarity() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.EUCLIDEAN)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 1);

            assertEquals(1, results.size());
            assertEquals(0.0f, results.get(0).score(), 0.001f);
        }
    }

    @Test
    void ivfFlat_dotProductSimilarity() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .build()) {

            index.index(1L, new float[]{2.0f, 3.0f});
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 1.0f}, 1);

            assertEquals(1, results.size());
            assertEquals(5.0f, results.get(0).score(), 0.001f);
        }
    }

    @Test
    void ivfFlat_searchAfterFlush() throws IOException {
        // tiny flush threshold forces flush during indexing
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(1L))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.0f, 1.0f});

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 10);
            assertEquals(2, results.size());
        }
    }

    @Test
    void ivfFlat_builderRequiresLsmTree() {
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>ivfFlatBuilder()
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .dimensions(2)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());
    }

    @Test
    void ivfFlat_builderRequiresDocIdSerializer() throws IOException {
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>ivfFlatBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .dimensions(2)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());
    }

    @Test
    void ivfFlat_builderRequiresDimensions() throws IOException {
        assertThrows(IllegalArgumentException.class, () ->
                LsmVectorIndex.<Long>ivfFlatBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());
    }

    @Test
    void ivfFlat_builderRequiresSimilarityFunction() throws IOException {
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>ivfFlatBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .dimensions(2)
                        .build());
    }

    @Test
    void ivfFlat_builderNullsThrowAtSetTime() {
        var builder = LsmVectorIndex.<Long>ivfFlatBuilder();
        assertThrows(NullPointerException.class, () -> builder.lsmTree(null));
        assertThrows(NullPointerException.class, () -> builder.docIdSerializer(null));
        assertThrows(NullPointerException.class, () -> builder.similarityFunction(null));
        assertThrows(IllegalArgumentException.class, () -> builder.dimensions(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.dimensions(0));
    }

    // -----------------------------------------------------------------------
    // Hnsw tests
    // -----------------------------------------------------------------------

    @Test
    void hnsw_indexThenSearchReturnsExactMatch() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 1);

            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).docId());
            assertEquals(1.0f, results.get(0).score(), 0.001f);
        }
    }

    @Test
    void hnsw_searchTopK() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .M(4)
                .efConstruction(10)
                .efSearch(10)
                .build()) {

            for (long i = 1; i <= 5; i++) {
                index.index(i, new float[]{(float) i, 1.0f});
            }
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 1.0f}, 3);

            assertEquals(3, results.size());
            // Scores should be in descending order
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).score() >= results.get(i + 1).score());
            }
        }
    }

    @Test
    void hnsw_removeDocExcludedFromSearch() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.9f, 0.1f});
            index.index(3L, new float[]{0.5f, 0.5f});

            index.remove(2L);

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 10);
            boolean containsRemoved = results.stream().anyMatch(r -> r.docId().equals(2L));
            assertFalse(containsRemoved, "removed doc should not appear in search results");
        }
    }

    @Test
    void hnsw_builderRequiresFields() throws IOException {
        // lsmTree required
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>hnswBuilder()
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .dimensions(2)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());

        // docIdSerializer required
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>hnswBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .dimensions(2)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());

        // dimensions required
        assertThrows(IllegalArgumentException.class, () ->
                LsmVectorIndex.<Long>hnswBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .build());

        // similarityFunction required
        assertThrows(NullPointerException.class, () ->
                LsmVectorIndex.<Long>hnswBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE))
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .dimensions(2)
                        .build());
    }
}

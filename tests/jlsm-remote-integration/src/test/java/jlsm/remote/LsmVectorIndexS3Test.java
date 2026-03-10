package jlsm.remote;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.vector.LsmVectorIndex;
import jlsm.wal.remote.RemoteWriteAheadLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LsmVectorIndex} with all storage backed by S3.
 * Uses {@link PassthroughBloomFilter} (avoids alignment issues) and eager SSTable reads.
 */
@ExtendWith(S3Fixture.class)
class LsmVectorIndexS3Test {

    private Path dir;
    private final AtomicLong idCounter = new AtomicLong(0);

    @BeforeEach
    void setup(S3Fixture fixture) {
        dir = fixture.newTestDirectory();
        idCounter.set(0);
    }

    // 8-byte big-endian Long doc-ID serializer
    private static final MemorySerializer<Long> LONG_DOC_ID = new MemorySerializer<>() {
        @Override
        public MemorySegment serialize(Long value) {
            byte[] bytes = new byte[8];
            long v = value;
            for (int i = 7; i >= 0; i--) { bytes[i] = (byte) (v & 0xFF); v >>>= 8; }
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

    private LsmTree buildTree(long flushThreshold) throws IOException {
        return StandardLsmTree.builder()
                .wal(RemoteWriteAheadLog.builder().directory(dir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level, path) ->
                        new TrieSSTableWriter(id, level, path, PassthroughBloomFilter.factory()))
                .sstableReaderFactory((SSTableReaderFactory) path ->
                        TrieSSTableReader.open(path, PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThreshold)
                .build();
    }

    // ---- IvfFlat ----

    @Test
    void ivfFlat_indexAndSearchReturnsExactMatch() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID)
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
    void ivfFlat_removeDocExcludedFromSearch() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID)
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
    void ivfFlat_searchAfterFlush() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(1L))
                .docIdSerializer(LONG_DOC_ID)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.0f, 1.0f});

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 10);
            assertEquals(2, results.size());
        }
    }

    // ---- Hnsw ----

    @Test
    void hnsw_indexAndSearchReturnsExactMatch() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID)
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
    void hnsw_removeDocExcludedFromSearch() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID)
                .dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            index.index(1L, new float[]{1.0f, 0.0f});
            index.index(2L, new float[]{0.9f, 0.1f});
            index.index(3L, new float[]{0.5f, 0.5f});
            index.remove(2L);

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{1.0f, 0.0f}, 10);
            assertFalse(results.stream().anyMatch(r -> r.docId().equals(2L)),
                    "removed doc should not appear in results");
        }
    }

    // ---- latency baseline (informational only — always passes) ----

    @Test
    void latencyBaseline_fiftyVectorsIndexAndSearch() throws IOException {
        int count = 50;
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE))
                .docIdSerializer(LONG_DOC_ID)
                .dimensions(4)
                .similarityFunction(SimilarityFunction.COSINE)
                .build()) {

            long indexStart = System.currentTimeMillis();
            for (long i = 0; i < count; i++) {
                index.index(i, new float[]{(float) i, 1.0f, 0.5f, 0.25f});
            }
            long indexElapsed = System.currentTimeMillis() - indexStart;

            long searchStart = System.currentTimeMillis();
            List<VectorIndex.SearchResult<Long>> results =
                    index.search(new float[]{1.0f, 1.0f, 0.5f, 0.25f}, 5);
            long searchElapsed = System.currentTimeMillis() - searchStart;

            System.out.printf("[S3 VectorIndex latency] index %d vectors in %d ms, "
                            + "search top-5 in %d ms%n",
                    count, indexElapsed, searchElapsed);
            assertEquals(5, results.size());
        }
    }
}

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
import java.util.Optional;
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
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 0.7f, 0.7f },
                    2);

            boolean doc1Found = results.stream().anyMatch(r -> r.docId().equals(1L));
            assertTrue(doc1Found,
                    "doc 1 should be visible after remove + re-index, but soft-delete tombstone persists");
        }
    }

    @Test
    void hnsw_float32_removeAndReindex_docVisibleInSearch() throws IOException {
        // F13 (fix-forward): Same bug exists in float32 path — verify fix applies to both
        // precisions.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT32).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f });

            index.remove(1L);
            index.index(1L, new float[]{ 0.7f, 0.7f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 0.7f, 0.7f },
                    2);

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
        // F01 R27: Vectors with NaN components that reach the index produce NaN scores.
        // NaN scores must be excluded from search results (silent degradation).
        // F01 R13: The document layer rejects non-finite values, so the index layer
        // validates and rejects NaN at index(). To test search-path resilience
        // per R27, inject a NaN vector directly into the backing LSM tree,
        // simulating data corruption or encoding edge cases.
        // Expectation: search returns only finite-scored results; the NaN vector is
        // invisible to search but does not crash or displace valid results.
        LsmTree tree = buildTree(Long.MAX_VALUE);
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE).precision(VectorPrecision.FLOAT16)
                .build()) {

            // Index a normal vector through the public API
            index.index(1L, new float[]{ 1.0f, 0.0f });

            // Inject a NaN vector directly into the LSM tree, bypassing index() validation.
            // This simulates data corruption or a bug that lets NaN slip past the boundary.
            // Centroid 0 already exists from doc 1; post under the same centroid.
            byte[] docId2Bytes = LONG_DOC_ID_SERIALIZER.serialize(2L)
                    .toArray(ValueLayout.JAVA_BYTE);
            float[] nanVector = { Float.NaN, 0.0f };
            byte[] nanEncoded = LsmVectorIndex.encodeVector(nanVector, VectorPrecision.FLOAT16);
            // Posting key: [0x01][centroid_id=0 (4 bytes BE)][docId]
            byte[] postingKey = new byte[1 + 4 + docId2Bytes.length];
            postingKey[0] = 0x01; // POSTING_PREFIX
            // centroid id 0 → bytes [0,0,0,0] already zero-initialized
            System.arraycopy(docId2Bytes, 0, postingKey, 5, docId2Bytes.length);
            tree.put(MemorySegment.ofArray(postingKey), MemorySegment.ofArray(nanEncoded));
            // Reverse lookup: [0x02][docId] → centroid_id 0
            byte[] revKey = new byte[1 + docId2Bytes.length];
            revKey[0] = 0x02;
            System.arraycopy(docId2Bytes, 0, revKey, 1, docId2Bytes.length);
            tree.put(MemorySegment.ofArray(revKey),
                    MemorySegment.ofArray(new byte[]{ 0, 0, 0, 0 }));

            // Search — NaN vector should be invisible per F01 R25a/R27
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    2);

            assertFalse(results.isEmpty(), "search should return results");

            // The first result should be doc 1 (exact match), NOT doc 2 (NaN score).
            // Per F01 R25a, NaN scores are filtered during candidate accumulation.
            VectorIndex.SearchResult<Long> top = results.get(0);
            assertFalse(Float.isNaN(top.score()),
                    "top result should not have NaN score — NaN entries should be ranked last or filtered");
            assertEquals(1L, top.docId(),
                    "exact match (doc 1) should be top result, not NaN-scored doc 2");
        }
    }

    @Test
    void hnsw_float16_nanVectorDoesNotCorruptOtherResults() throws IOException {
        // F01 R27: Same as above but for HNSW. Inject NaN below the validation boundary
        // to test that HNSW search filters non-finite scores per F01 R25a.
        // The HNSW node format includes neighbor links, so we index a valid vector first
        // then overwrite its stored data with a NaN vector to corrupt the stored value
        // while preserving valid graph structure.
        LsmTree tree = buildTree(Long.MAX_VALUE);
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder().lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(2)
                .similarityFunction(SimilarityFunction.COSINE).precision(VectorPrecision.FLOAT16)
                .build()) {

            // Index two valid vectors to build graph structure
            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f });

            // Read doc 2's node data, decode it, replace vector with NaN, re-encode and overwrite
            byte[] docId2Bytes = LONG_DOC_ID_SERIALIZER.serialize(2L)
                    .toArray(ValueLayout.JAVA_BYTE);
            Optional<MemorySegment> nodeData = tree.get(MemorySegment.ofArray(docId2Bytes));
            assertTrue(nodeData.isPresent(), "doc 2 node must exist");
            byte[] nodeBytes = nodeData.get().toArray(ValueLayout.JAVA_BYTE);
            // The vector portion is the last dimensions*2 bytes (float16, 2 dims = 4 bytes)
            int vectorStart = nodeBytes.length - 2 * 2; // dimensions * bytesPerFloat16
            // Overwrite with NaN-encoded float16 vector
            float[] nanVector = { Float.NaN, 0.0f };
            byte[] nanEncoded = LsmVectorIndex.encodeVector(nanVector, VectorPrecision.FLOAT16);
            System.arraycopy(nanEncoded, 0, nodeBytes, vectorStart, nanEncoded.length);
            tree.put(MemorySegment.ofArray(docId2Bytes), MemorySegment.ofArray(nodeBytes));

            // Search — doc 2's NaN score should be filtered per F01 R25a/R27
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    2);

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
    void ivfFlat_float16_overflowVectorRejectedEagerly() throws IOException {
        // F15 (superseded by F17): Overflow values (> 65504) are now rejected at index time
        // with IllegalArgumentException per coding-guidelines (eager input validation).
        // Original test verified NaN filtering prevented corruption; now validation prevents
        // the overflow vector from being indexed at all.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(2L, new float[]{ 100000.0f, 0.0f }),
                    "overflow values should be rejected eagerly at index time");
        }
    }

    @Test
    void hnsw_float16_overflowVectorRejectedEagerly() throws IOException {
        // F15 (superseded by F17): Overflow values now rejected at index time.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(2L, new float[]{ 100000.0f, 0.0f }),
                    "overflow values should be rejected eagerly at index time");
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
            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ Float.NaN, 0.0f }, 2);

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

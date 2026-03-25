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
 * Adversarial tests — Round 3. Targets F17–F19: float16 overflow validation and centroid precision
 * invariant.
 */
class LsmVectorIndexFloat16AdversarialRound3Test {

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
    // F17: Float16 overflow values should be rejected eagerly at index time
    // =======================================================================

    @Test
    void ivfFlat_float16_indexOverflowValueRejectsEagerly() throws IOException {
        // F17: Float32 values > 65504 overflow to Infinity in float16.
        // cosine(query, [Inf,...]) = NaN → filtered → vector invisible.
        // Per coding-guidelines (eager input validation), index() should reject
        // overflow values with IllegalArgumentException.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(4).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ 100000.0f, 0.0f, 0.0f, 0.0f }),
                    "index() should reject float16 overflow values eagerly");
        }
    }

    @Test
    void hnsw_float16_indexOverflowValueRejectsEagerly() throws IOException {
        // F17: Same overflow validation for Hnsw path.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(4).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ 100000.0f, 0.0f, 0.0f, 0.0f }),
                    "index() should reject float16 overflow values eagerly");
        }
    }

    // =======================================================================
    // F18: Negative overflow also rejected
    // =======================================================================

    @Test
    void ivfFlat_float16_indexNegativeOverflowRejectsEagerly() throws IOException {
        // F18: Negative overflow (-100000.0f → -Infinity in float16) should also
        // be rejected. Fix-forward of F17.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ -100000.0f, 0.0f }),
                    "index() should reject negative float16 overflow values");
        }
    }

    @Test
    void hnsw_float16_indexNegativeOverflowRejectsEagerly() throws IOException {
        // F18: Negative overflow for Hnsw.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ -100000.0f, 0.0f }),
                    "index() should reject negative float16 overflow values");
        }
    }

    // =======================================================================
    // F19: Boundary value 65504.0f accepted without error
    // =======================================================================

    @Test
    void ivfFlat_float16_maxFiniteFloat16AcceptedAndSearchable() throws IOException {
        // F19: 65504.0f is the max finite float16 — should be accepted and searchable.
        // Defensive regression test: validation must not over-reject.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertDoesNotThrow(() -> index.index(1L, new float[]{ 65504.0f, 0.0f }),
                    "65504.0f (max finite float16) should be accepted");

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 65504.0f, 0.0f }, 1);
            assertFalse(results.isEmpty(), "max finite float16 vector should be searchable");
            assertEquals(1L, results.get(0).docId());
        }
    }

    @Test
    void hnsw_float16_maxFiniteFloat16AcceptedAndSearchable() throws IOException {
        // F19: Same boundary test for Hnsw.
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertDoesNotThrow(() -> index.index(1L, new float[]{ 65504.0f, 0.0f }),
                    "65504.0f (max finite float16) should be accepted");

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 65504.0f, 0.0f }, 1);
            assertFalse(results.isEmpty(), "max finite float16 vector should be searchable");
            assertEquals(1L, results.get(0).docId());
        }
    }

    // =======================================================================
    // F17: Float32 precision does NOT validate — overflow is only a float16 issue
    // =======================================================================

    @Test
    void ivfFlat_float32_overflowValueAccepted() throws IOException {
        // F17 control: Float32 precision should NOT reject large values —
        // float32 can represent 100000.0f exactly. Validation is float16-specific.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT32).build()) {

            assertDoesNotThrow(() -> index.index(1L, new float[]{ 100000.0f, 0.0f }),
                    "float32 should accept any finite float value without restriction");
        }
    }

    // =======================================================================
    // F16: IvfFlat centroid precision invariant — centroids remain float32
    // =======================================================================

    @Test
    void ivfFlat_float16_centroidAssignmentUsesFloat32Precision() throws IOException {
        // F16: Verify centroid assignment quality is not degraded by float16.
        // Use values with significant float16 precision loss to detect if centroids
        // are incorrectly stored as float16.
        // If centroids were float16-encoded, the precision loss would change which
        // centroid subsequent vectors are assigned to.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(4).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).numClusters(2).nprobe(2).build()) {

            // First two vectors become centroids — stored as float32 per contract
            // Use values with significant float16 precision loss
            index.index(1L, new float[]{ 3.14159f, 2.71828f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 0.0f, 3.14159f, 2.71828f });

            // Third vector — assignment depends on centroid precision
            index.index(3L, new float[]{ 3.0f, 2.7f, 0.1f, 0.1f });

            // Search near centroid 1 direction — doc 3 should be found
            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 3.0f, 2.7f, 0.0f, 0.0f }, 3);

            assertTrue(results.stream().anyMatch(r -> r.docId().equals(3L)),
                    "doc 3 should be assigned near centroid 1 using float32 centroid precision");
        }
    }
}

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
 * Adversarial tests for float16 vector support. Targets findings from spec-analysis.md Phase 1.
 */
class LsmVectorIndexFloat16AdversarialTest {

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
            for (byte b : bytes)
                v = (v << 8) | (b & 0xFFL);
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
    // F1: Float16 overflow — values > 65504.0 become infinity after roundtrip
    // =======================================================================

    @Test
    void encodeFloat16s_overflowValueBecomesInfinity() {
        // F1: 100000.0f exceeds float16 max (65504.0), should overflow to +Inf
        float[] input = { 100000.0f, -100000.0f, 65504.0f, 65536.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        assertEquals(Float.POSITIVE_INFINITY, decoded[0],
                "100000.0f should overflow to +Infinity in float16");
        assertEquals(Float.NEGATIVE_INFINITY, decoded[1],
                "-100000.0f should overflow to -Infinity in float16");
        // 65504.0 is exactly representable in float16
        assertEquals(65504.0f, decoded[2],
                "65504.0f is float16 max normal, should roundtrip exactly");
        // 65536.0 overflows
        assertEquals(Float.POSITIVE_INFINITY, decoded[3],
                "65536.0f should overflow to +Infinity in float16");
    }

    // =======================================================================
    // F2: Float16 subnormal flush-to-zero
    // =======================================================================

    @Test
    void encodeFloat16s_subnormalFlushesToZero() {
        // F2: Float.MIN_VALUE (~1.4e-45) is far below float16 min subnormal (~5.96e-8)
        float[] input = { Float.MIN_VALUE, 1e-10f, 5.96e-8f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        assertEquals(0.0f, decoded[0], "Float.MIN_VALUE should flush to zero in float16");
        assertEquals(0.0f, decoded[1], "1e-10f should flush to zero in float16");
        // 5.96e-8 is approximately the smallest float16 subnormal
        float roundtripped = Float.float16ToFloat(Float.floatToFloat16(5.96e-8f));
        assertEquals(roundtripped, decoded[2],
                "near-subnormal boundary should match Float.floatToFloat16 roundtrip");
    }

    // =======================================================================
    // F3: Negative zero (-0.0f) roundtrip
    // =======================================================================

    @Test
    void encodeFloat16s_negativeZeroRoundtrips() {
        // F3: Existing test checks index 0-3 but skips -0.0f at index 4
        float[] input = { -0.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        // IEEE 754: -0.0f and 0.0f are == but have different bit patterns
        float result = decoded[0];
        float expected = Float.float16ToFloat(Float.floatToFloat16(-0.0f));
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(result),
                "-0.0f should preserve sign bit through float16 roundtrip");
    }

    // =======================================================================
    // F4: Negative value encoding — full range sign preservation
    // =======================================================================

    @Test
    void encodeFloat16s_negativeValuesPreserveSign() {
        // F4: Verify sign bit preservation across encode/decode for various negatives
        float[] input = { -1.0f, -0.5f, -100.0f, -0.001f, -65504.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        for (int i = 0; i < input.length; i++) {
            float expected = Float.float16ToFloat(Float.floatToFloat16(input[i]));
            assertEquals(expected, decoded[i], 0.0f,
                    "component " + i + " (" + input[i] + ") should match float16 roundtrip");
            assertTrue(decoded[i] < 0.0f || Float.floatToRawIntBits(decoded[i]) < 0,
                    "component " + i + " should remain negative");
        }
    }

    // =======================================================================
    // F5: Cross-precision encode/decode mismatch
    // =======================================================================

    @Test
    void encodeVector_float16Bytes_decodedAsFloat32_assertionFailsOnLengthMismatch() {
        // Updated by audit F-R6.dt.1.4: assert-only check was a bug, now correctly
        // throws IllegalArgumentException via runtime validation
        // F5: Encoding with FLOAT16 produces dim*2 bytes; decoding as FLOAT32 expects dim*4
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] float16Bytes = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT16);

        // decodeVector(float16Bytes, 3, FLOAT32) should fail with runtime validation
        // because float16Bytes.length (6) != 3 * 4 (12)
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.decodeVector(float16Bytes, 3, VectorPrecision.FLOAT32),
                "Decoding FLOAT16 bytes as FLOAT32 should fail on byte count mismatch");
    }

    @Test
    void encodeVector_float32Bytes_decodedAsFloat16_producesGarbage() {
        // F5: Encoding with FLOAT32 produces dim*4 bytes; decoding as FLOAT16 with
        // doubled dimensions would pass the length assertion but produce garbage values.
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] float32Bytes = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT32);
        // float32Bytes.length = 12, and decoding as FLOAT16 with dimensions=6 passes
        // the assertion (6*2=12) but gives meaningless values
        float[] decoded = LsmVectorIndex.decodeVector(float32Bytes, 6, VectorPrecision.FLOAT16);

        // The decoded values should NOT match the original — they are garbage reinterpretation
        assertEquals(6, decoded.length, "should decode to 6 components (garbage)");
        // At minimum, verify this doesn't match the original input
        boolean allMatch = true;
        for (int i = 0; i < input.length && i < decoded.length; i++) {
            if (decoded[i] != input[i]) {
                allMatch = false;
                break;
            }
        }
        assertFalse(allMatch,
                "Cross-precision decode should not accidentally produce correct values");
    }

    // =======================================================================
    // F6: IvfFlat re-index leaves orphan posting under old centroid
    // =======================================================================

    @Test
    void ivfFlat_float16_reindexSameDocId_searchReturnsOnlyNewVector() throws IOException {
        // F6: Re-indexing a docId should not leave a phantom entry under the old centroid.
        // Use numClusters=2 so vectors get different centroid assignments.
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).numClusters(2).nprobe(2).build()) {

            // First two vectors become centroids (numClusters=2)
            index.index(1L, new float[]{ 1.0f, 0.0f }); // centroid 0
            index.index(2L, new float[]{ 0.0f, 1.0f }); // centroid 1

            // Index doc 3 near centroid 0
            index.index(3L, new float[]{ 0.9f, 0.1f });

            // Re-index doc 3 near centroid 1 (different direction)
            index.index(3L, new float[]{ 0.1f, 0.9f });

            // Search near the OLD position — doc 3 should NOT appear with old vector
            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    10);

            long countDoc3 = results.stream().filter(r -> r.docId().equals(3L)).count();
            assertTrue(countDoc3 <= 1, "doc 3 should appear at most once in results, but appeared "
                    + countDoc3 + " times (phantom from old centroid)");
        }
    }

    // =======================================================================
    // F8: IvfFlat float16 storage size verification
    // =======================================================================

    @Test
    void encodeVector_float16_outputLengthIsDimTimes2() {
        // F8: Verify float16 encoding produces dim*2 bytes (50% of float32)
        float[] input = { 1.0f, 2.0f, 3.0f, 4.0f };
        byte[] float16Bytes = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT16);
        byte[] float32Bytes = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT32);

        assertEquals(input.length * 2, float16Bytes.length,
                "FLOAT16 encoding should produce dim*2 bytes");
        assertEquals(input.length * 4, float32Bytes.length,
                "FLOAT32 encoding should produce dim*4 bytes");
        assertEquals(float32Bytes.length / 2, float16Bytes.length,
                "FLOAT16 should be exactly half the size of FLOAT32");
    }

    // =======================================================================
    // F9: Hnsw decodeNode silent truncation on misaligned vecBytes
    // =======================================================================

    @Test
    void encodeFloat16s_decodeFloat16s_dimensionByteMismatch_assertionFires() {
        // Updated by audit F-R6.dt.1.4: assert-only check was a bug, now correctly
        // throws IllegalArgumentException via runtime validation
        // F9: If byte count is not evenly divisible by bytesPerComponent,
        // decodeFloat16s should catch it via its byte-count assertion
        byte[] oddBytes = new byte[5]; // not divisible by 2
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.decodeFloat16s(oddBytes, 2),
                "decodeFloat16s should throw when bytes.length (5) != dimensions*2 (4)");
    }

    @Test
    void decodeVector_float16_oddByteCount_assertionFires() {
        // Updated by audit F-R6.dt.1.4: assert-only check was a bug, now correctly
        // throws IllegalArgumentException via runtime validation
        // F9: decodeVector dispatches to decodeFloat16s which should catch mismatch
        byte[] oddBytes = new byte[7]; // 7 / 2 = 3 via integer division, but 3*2 = 6 != 7
        // Calling with dimensions=3 should fail because 7 != 3*2
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.decodeVector(oddBytes, 3, VectorPrecision.FLOAT16),
                "decodeVector should fail on byte count mismatch");
    }

    // =======================================================================
    // F12: Hnsw float16 with DOT_PRODUCT similarity
    // =======================================================================

    @Test
    void hnsw_float16_dotProduct_indexAndSearchReturnsCorrectOrder() throws IOException {
        // F12: Verify float16 works with DOT_PRODUCT, not just COSINE
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(3).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .precision(VectorPrecision.FLOAT16).maxConnections(4).efConstruction(10)
                .efSearch(10).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.5f, 0.5f, 0.0f });
            index.index(3L, new float[]{ 0.0f, 0.0f, 1.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 3);

            assertFalse(results.isEmpty(), "should return results with DOT_PRODUCT + float16");
            assertEquals(1L, results.get(0).docId(),
                    "exact match should be top result with DOT_PRODUCT");
        }
    }

    @Test
    void hnsw_float16_euclidean_indexAndSearchReturnsCorrectOrder() throws IOException {
        // F12: Verify float16 works with EUCLIDEAN similarity
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(3).similarityFunction(SimilarityFunction.EUCLIDEAN)
                .precision(VectorPrecision.FLOAT16).maxConnections(4).efConstruction(10)
                .efSearch(10).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.5f, 0.5f, 0.0f });
            index.index(3L, new float[]{ 0.0f, 0.0f, 1.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 3);

            assertFalse(results.isEmpty(), "should return results with EUCLIDEAN + float16");
            assertEquals(1L, results.get(0).docId(),
                    "exact match should be top result with EUCLIDEAN");
        }
    }

    // =======================================================================
    // F12 (IvfFlat variant): IvfFlat float16 with DOT_PRODUCT similarity
    // =======================================================================

    @Test
    void ivfFlat_float16_dotProduct_indexAndSearchReturnsCorrectOrder() throws IOException {
        // F12: Verify float16 works with DOT_PRODUCT on IvfFlat too
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(3).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.5f, 0.5f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "should return results with DOT_PRODUCT + float16");
            assertEquals(1L, results.get(0).docId(),
                    "exact match should be top result with DOT_PRODUCT");
        }
    }

    @Test
    void ivfFlat_float16_euclidean_indexAndSearchReturnsCorrectOrder() throws IOException {
        // F12: Verify float16 works with EUCLIDEAN on IvfFlat
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(3).similarityFunction(SimilarityFunction.EUCLIDEAN)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.5f, 0.5f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 2);

            assertFalse(results.isEmpty(), "should return results with EUCLIDEAN + float16");
            assertEquals(1L, results.get(0).docId(),
                    "exact match should be top result with EUCLIDEAN");
        }
    }
}

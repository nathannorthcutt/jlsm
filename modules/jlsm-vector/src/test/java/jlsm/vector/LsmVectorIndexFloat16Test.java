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

class LsmVectorIndexFloat16Test {

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

    // -----------------------------------------------------------------------
    // Float16 encoding helpers
    // -----------------------------------------------------------------------

    @Test
    void encodeFloat16s_roundtripPreservesValuesWithinPrecision() {
        float[] input = { 1.0f, -1.0f, 0.5f, 0.25f, 3.14f, 100.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        assertEquals(input.length, decoded.length);
        for (int i = 0; i < input.length; i++) {
            // Float16 has ~3 decimal digits of precision; allow relative error
            float expected = Float.float16ToFloat(Float.floatToFloat16(input[i]));
            assertEquals(expected, decoded[i], 0.0f,
                    "component " + i + " should match float16 roundtrip");
        }
    }

    @Test
    void encodeFloat16s_outputLengthIsDimensionsTimes2() {
        float[] input = { 1.0f, 2.0f, 3.0f, 4.0f, 5.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        assertEquals(input.length * 2, encoded.length);
    }

    @Test
    void encodeFloat16s_handlesSpecialValues_nanInfinityZero() {
        float[] input = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f,
                -0.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] decoded = LsmVectorIndex.decodeFloat16s(encoded, input.length);

        assertTrue(Float.isNaN(decoded[0]), "NaN should roundtrip");
        assertEquals(Float.POSITIVE_INFINITY, decoded[1], "positive infinity should roundtrip");
        assertEquals(Float.NEGATIVE_INFINITY, decoded[2], "negative infinity should roundtrip");
        assertEquals(0.0f, decoded[3], "zero should roundtrip");
    }

    // -----------------------------------------------------------------------
    // encodeVector / decodeVector dispatch
    // -----------------------------------------------------------------------

    @Test
    void encodeVector_float32_matchesEncodeFloats() {
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] viaDispatch = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT32);
        byte[] viaDirect = LsmVectorIndex.encodeFloats(input);
        assertArrayEquals(viaDirect, viaDispatch);
    }

    @Test
    void encodeVector_float16_matchesEncodeFloat16s() {
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] viaDispatch = LsmVectorIndex.encodeVector(input, VectorPrecision.FLOAT16);
        byte[] viaDirect = LsmVectorIndex.encodeFloat16s(input);
        assertArrayEquals(viaDirect, viaDispatch);
    }

    @Test
    void decodeVector_float32_matchesDecodeFloats() {
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] encoded = LsmVectorIndex.encodeFloats(input);
        float[] viaDispatch = LsmVectorIndex.decodeVector(encoded, 3, VectorPrecision.FLOAT32);
        float[] viaDirect = LsmVectorIndex.decodeFloats(encoded, 3);
        assertArrayEquals(viaDirect, viaDispatch);
    }

    @Test
    void decodeVector_float16_matchesDecodeFloat16s() {
        float[] input = { 1.0f, 2.0f, 3.0f };
        byte[] encoded = LsmVectorIndex.encodeFloat16s(input);
        float[] viaDispatch = LsmVectorIndex.decodeVector(encoded, 3, VectorPrecision.FLOAT16);
        float[] viaDirect = LsmVectorIndex.decodeFloat16s(encoded, 3);
        assertArrayEquals(viaDirect, viaDispatch);
    }

    // -----------------------------------------------------------------------
    // IvfFlat float16
    // -----------------------------------------------------------------------

    @Test
    void ivfFlat_defaultPrecisionIsFloat32() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE).build()) {

            assertEquals(VectorPrecision.FLOAT32, index.precision());
        }
    }

    @Test
    void ivfFlat_float16_precisionReturnsFloat16() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertEquals(VectorPrecision.FLOAT16, index.precision());
        }
    }

    @Test
    void ivfFlat_float16_indexThenSearchReturnsResults() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(4).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f, 0.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f, 0.0f }, 2);

            assertEquals(2, results.size());
            assertEquals(1L, results.get(0).docId(), "nearest doc should be exact match");
        }
    }

    @Test
    void ivfFlat_float16_searchScoresAreReasonable() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    1);

            assertEquals(1, results.size());
            // Cosine of identical vectors should be ~1.0 even after float16 roundtrip
            assertEquals(1.0f, results.get(0).score(), 0.01f,
                    "cosine of identical float16 vectors should be near 1.0");
        }
    }

    @Test
    void ivfFlat_float16_removeExcludesFromSearch() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.9f, 0.1f });
            index.remove(2L);

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    10);

            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).docId());
        }
    }

    // -----------------------------------------------------------------------
    // Hnsw float16
    // -----------------------------------------------------------------------

    @Test
    void hnsw_defaultPrecisionIsFloat32() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE).build()) {

            assertEquals(VectorPrecision.FLOAT32, index.precision());
        }
    }

    @Test
    void hnsw_float16_precisionReturnsFloat16() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            assertEquals(VectorPrecision.FLOAT16, index.precision());
        }
    }

    @Test
    void hnsw_float16_indexThenSearchReturnsResults() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(4).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).maxConnections(4).efConstruction(10)
                .efSearch(10).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f, 0.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f, 0.0f }, 2);

            assertEquals(2, results.size());
            assertEquals(1L, results.get(0).docId(), "nearest doc should be exact match");
        }
    }

    @Test
    void hnsw_float16_searchScoresAreReasonable() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    1);

            assertEquals(1, results.size());
            assertEquals(1.0f, results.get(0).score(), 0.01f,
                    "cosine of identical float16 vectors should be near 1.0");
        }
    }

    @Test
    void hnsw_float16_removeExcludesFromSearch() throws IOException {
        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.COSINE)
                .precision(VectorPrecision.FLOAT16).build()) {

            index.index(1L, new float[]{ 1.0f, 0.0f });
            index.index(2L, new float[]{ 0.9f, 0.1f });
            index.index(3L, new float[]{ 0.5f, 0.5f });

            index.remove(2L);

            List<VectorIndex.SearchResult<Long>> results = index.search(new float[]{ 1.0f, 0.0f },
                    10);
            boolean containsRemoved = results.stream().anyMatch(r -> r.docId().equals(2L));
            assertFalse(containsRemoved, "removed doc should not appear in search results");
        }
    }

    // -----------------------------------------------------------------------
    // Builder error cases
    // -----------------------------------------------------------------------

    @Test
    void ivfFlat_builderPrecisionNullThrowsNPE() {
        var builder = LsmVectorIndex.<Long>ivfFlatBuilder();
        assertThrows(NullPointerException.class, () -> builder.precision(null));
    }

    @Test
    void hnsw_builderPrecisionNullThrowsNPE() {
        var builder = LsmVectorIndex.<Long>hnswBuilder();
        assertThrows(NullPointerException.class, () -> builder.precision(null));
    }
}

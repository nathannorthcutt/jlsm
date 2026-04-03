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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for data transformation concerns in LsmVectorIndex.
 */
class DataTransformationAdversarialTest {

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

    private LsmTree buildTree() throws IOException {
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

    // Finding: F-R5.dt.1.2
    // Bug: validateFloat16Components (line 145-146) explicitly skips Infinity with `continue`,
    //      allowing infinite components through. This is a latent defect — currently masked by
    //      validateFiniteComponents (line 452) which catches Infinity first. But the skip in
    //      validateFloat16Components is still wrong: Infinity should be rejected there too,
    //      as defense-in-depth against callers that might invoke it without validateFiniteComponents.
    // Correct behavior: validateFloat16Components should reject Infinity with IllegalArgumentException,
    //                    not silently skip it
    // Fix location: LsmVectorIndex.validateFloat16Components, line 145-146
    // Regression watch: Ensure Infinity rejection does not break encoding of other special values
    @Test
    void test_IvfFlat_index_infiniteComponentProducesDegenerateCentroidAssignment() {
        // Strategy 1: Verify that validateFloat16Components itself rejects Infinity
        // (not just relying on validateFiniteComponents as the outer guard)
        float[] posInfVector = { 1.0f, Float.POSITIVE_INFINITY };

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.validateFloat16Components(posInfVector),
                "validateFloat16Components must reject Infinity, not skip it");

        assertTrue(ex.getMessage().toLowerCase().contains("infin")
                        || ex.getMessage().contains("non-finite"),
                "Exception message should mention infinity or non-finite, got: " + ex.getMessage());

        // Also verify negative infinity
        float[] negInfVector = { Float.NEGATIVE_INFINITY, 0.5f };
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.validateFloat16Components(negInfVector),
                "validateFloat16Components must reject negative infinity too");
    }

    // Finding: F-R5.dt.1.4
    // Bug: assignCentroid computes newCid = centroids.size(), which collides with an existing
    //       centroid ID when the scan returns non-contiguous IDs (e.g., centroid 1 deleted,
    //       remaining {0,2} → size=2 → newCid=2 overwrites existing centroid 2)
    // Correct behavior: newCid should be max(existingIds)+1 or otherwise avoid collision
    // Fix location: IvfFlat.assignCentroid, line 658
    // Regression watch: ensure new centroid IDs do not produce duplicate keys or skip valid IDs
    @Test
    void test_assignCentroid_nonContiguousIds_noCollision() throws IOException {
        // Use a shared LsmTree reference so we can directly delete a centroid key
        LsmTree tree = buildTree();

        // numClusters=5 so the first 5 indexed vectors each become a centroid
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .precision(VectorPrecision.FLOAT32).numClusters(5).build()) {

            // Index 3 vectors → creates centroids {0, 1, 2}
            float[] vec0 = {1.0f, 0.0f};
            float[] vec1 = {0.0f, 1.0f};
            float[] vec2 = {-1.0f, 0.0f};

            index.index(100L, vec0); // centroid 0
            index.index(101L, vec1); // centroid 1
            index.index(102L, vec2); // centroid 2

            // Read centroid 2's original stored value for later comparison
            byte[] centroid2Key = {0x00, 0x00, 0x00, 0x00, 0x02};
            var originalCentroid2 = tree.get(MemorySegment.ofArray(centroid2Key));
            assertTrue(originalCentroid2.isPresent(), "Centroid 2 should exist");
            byte[] originalCentroid2Bytes = originalCentroid2.get().toArray(ValueLayout.JAVA_BYTE);

            // Delete centroid 1 directly from the backing LSM tree, simulating corruption
            byte[] centroid1Key = {0x00, 0x00, 0x00, 0x00, 0x01};
            tree.delete(MemorySegment.ofArray(centroid1Key));

            // Now centroids visible in scan are {0, 2} — size=2, but numClusters=5
            // Index a 4th vector → assignCentroid should create a NEW centroid
            // Bug: newCid = centroids.size() = 2, which collides with existing centroid 2
            float[] vec3 = {0.0f, -1.0f};  // very different from vec2
            index.index(103L, vec3); // should create centroid 3, NOT overwrite centroid 2

            // Read centroid 2 again — it must be unchanged
            var afterCentroid2 = tree.get(MemorySegment.ofArray(centroid2Key));
            assertTrue(afterCentroid2.isPresent(),
                    "Centroid 2 should still exist after indexing a new vector");
            byte[] afterCentroid2Bytes = afterCentroid2.get().toArray(ValueLayout.JAVA_BYTE);

            assertArrayEquals(originalCentroid2Bytes, afterCentroid2Bytes,
                    "Centroid 2 data must not be overwritten when a new centroid is created " +
                    "after non-contiguous ID gap. newCid should not collide with existing " +
                    "centroid IDs.");
        }
    }

    // Finding: F-R5.dt.1.1
    // Bug: NaN vector components bypass validateFloat16Components (line 120 skips NaN with continue)
    //      and poison centroid assignment — NaN vectors silently get assigned to centroid 0
    // Correct behavior: validateFloat16Components should reject NaN components with IllegalArgumentException
    // Fix location: LsmVectorIndex.validateFloat16Components, line 120
    // Regression watch: Ensure NaN rejection does not break encoding of other special values (subnormals, zero)
    @Test
    void test_IvfFlat_index_nanComponentBypassesFloat16Validation() throws IOException {
        try (VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(buildTree()).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .precision(VectorPrecision.FLOAT16).build()) {

            // A vector with a NaN component should be rejected at index time
            float[] nanVector = { 1.0f, Float.NaN };

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, nanVector),
                    "NaN vector components must be rejected by float16 validation");

            assertTrue(ex.getMessage().contains("NaN"),
                    "Exception message should mention NaN, got: " + ex.getMessage());
        }
    }

    // Finding: F-R5.dt.2.2
    // Bug: decodeNode does not validate neighborCount for non-negative values;
    //      a corrupted negative neighborCount causes IllegalArgumentException from
    //      ArrayList(negativeCapacity) instead of a proper IOException
    // Correct behavior: decodeNode should validate neighborCount >= 0 and throw
    //                    IOException("Corrupted node: ...") on negative values
    // Fix location: LsmVectorIndex.Hnsw.decodeNode, after reading neighborCount
    // Regression watch: Ensure validation does not reject valid zero neighborCount
    @Test
    void test_Hnsw_decodeNode_negativeNeighborCountThrowsIOException() throws IOException {
        LsmTree tree = buildTree();

        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(tree).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .maxConnections(4).efConstruction(10).efSearch(10)
                .precision(VectorPrecision.FLOAT32).build()) {

            // Index first vector to create a valid node
            float[] vec1 = {1.0f, 0.5f};
            index.index(1L, vec1);

            // Read the stored node bytes for doc 1L
            byte[] doc1Key = LONG_DOC_ID_SERIALIZER.serialize(1L).toArray(ValueLayout.JAVA_BYTE);
            MemorySegment doc1KeySeg = MemorySegment.ofArray(doc1Key);
            var stored = tree.get(doc1KeySeg);
            assertTrue(stored.isPresent(), "Doc 1L should be stored");
            byte[] nodeBytes = stored.get().toArray(ValueLayout.JAVA_BYTE);

            // Node format: [4-byte docIdLen][4-byte layerCount][per layer: [4-byte neighborCount]...]
            // Corrupt the first neighborCount (at offset 8) to -1 (0xFFFFFFFF)
            nodeBytes[8] = (byte) 0xFF;
            nodeBytes[9] = (byte) 0xFF;
            nodeBytes[10] = (byte) 0xFF;
            nodeBytes[11] = (byte) 0xFF;
            tree.put(doc1KeySeg, MemorySegment.ofArray(nodeBytes));

            // Index a second vector — HNSW reads doc 1L (entry point) via decodeNode
            // Bug: negative neighborCount causes IllegalArgumentException from ArrayList
            // Correct: should throw IOException indicating corrupted node data
            float[] vec2 = {0.5f, 1.0f};
            IOException ex = assertThrows(IOException.class,
                    () -> index.index(2L, vec2),
                    "decodeNode must throw IOException when neighborCount is negative, " +
                    "not IllegalArgumentException from ArrayList");

            assertTrue(ex.getMessage().contains("neighborCount") ||
                            ex.getMessage().contains("Corrupted"),
                    "IOException message should mention neighborCount or corruption, got: " +
                    ex.getMessage());
        }
    }

    // Finding: F-R5.dt.2.1
    // Bug: decodeNode silently truncates vector when remaining bytes not divisible by bytesPerComponent
    // Correct behavior: decodeNode should throw an exception when vecBytes % bytesPerComponent != 0
    // Fix location: LsmVectorIndex.Hnsw.decodeNode, line 1197-1199
    // Regression watch: Ensure the validation does not reject valid nodes with correctly aligned vector bytes
    @Test
    void test_Hnsw_decodeNode_truncatedVectorBytesDetected() throws IOException {
        // Build a shared LSM tree so we can directly corrupt stored node bytes
        LsmTree tree = buildTree();

        try (VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(tree).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(2).similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .maxConnections(4).efConstruction(10).efSearch(10)
                .precision(VectorPrecision.FLOAT16).build()) {

            // Index first vector — this creates a valid node in the tree
            float[] vec1 = {1.0f, 0.5f};
            index.index(1L, vec1);

            // Get the encoded bytes for doc 1L from the tree
            byte[] doc1Key = LONG_DOC_ID_SERIALIZER.serialize(1L).toArray(ValueLayout.JAVA_BYTE);
            MemorySegment doc1KeySeg = MemorySegment.ofArray(doc1Key);
            var stored = tree.get(doc1KeySeg);
            assertTrue(stored.isPresent(), "Doc 1L should be stored");
            byte[] originalBytes = stored.get().toArray(ValueLayout.JAVA_BYTE);

            // Corrupt the node by truncating 1 byte from the end.
            // For FLOAT16 with 2 dimensions: vector bytes = 4 bytes (2 * 2).
            // After truncation: vector bytes = 3, which is NOT divisible by 2.
            // Bug: decodeNode computes dimensions = 3/2 = 1 (silent truncation).
            // Correct: decodeNode should detect the misalignment and throw.
            byte[] corruptedBytes = Arrays.copyOf(originalBytes, originalBytes.length - 1);
            tree.put(doc1KeySeg, MemorySegment.ofArray(corruptedBytes));

            // Index a second vector — HNSW will read doc 1L (the entry point)
            // via scoreNode/decodeNode during neighbor updates.
            // With the fix: this should throw IOException wrapping the validation error.
            // Without the fix: silently truncates doc 1L's vector to 1 dimension
            // and re-encodes it, permanently losing data.
            float[] vec2 = {0.5f, 1.0f};
            assertThrows(IOException.class,
                    () -> index.index(2L, vec2),
                    "decodeNode must throw when vector bytes are not divisible by " +
                    "bytesPerComponent — silent truncation corrupts data");
        }
    }
}

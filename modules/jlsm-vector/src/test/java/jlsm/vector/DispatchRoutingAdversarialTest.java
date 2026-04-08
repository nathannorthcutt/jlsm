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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DispatchRoutingAdversarialTest {

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

    // Finding: F-R5.dispatch_routing.1.2
    // Bug: FLOAT32 path has no validation dispatch — NaN/Infinity vectors silently indexed
    // and invisible in search because scoring produces NaN which is filtered out
    // Correct behavior: index() should reject NaN/Infinity vectors for ALL precisions,
    // not just FLOAT16
    // Fix location: LsmVectorIndex.java IvfFlat.index() ~line 432, Hnsw.index() ~line 788
    // Regression watch: Ensure FLOAT16 validation still works after generalizing
    @Test
    void test_ivfFlat_index_float32_rejects_nan_vector() throws IOException {
        int dimensions = 3;

        try (LsmTree tree = buildTree(1024 * 1024)) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.COSINE).numClusters(2).nprobe(2)
                    .precision(VectorPrecision.FLOAT32).build();

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ Float.NaN, 0.0f, 0.0f }),
                    "FLOAT32 index should reject NaN vector components");
        }
    }

    @Test
    void test_ivfFlat_index_float32_rejects_infinity_vector() throws IOException {
        int dimensions = 3;

        try (LsmTree tree = buildTree(1024 * 1024)) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.COSINE).numClusters(2).nprobe(2)
                    .precision(VectorPrecision.FLOAT32).build();

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ Float.POSITIVE_INFINITY, 0.0f, 0.0f }),
                    "FLOAT32 index should reject Infinity vector components");
        }
    }

    @Test
    void test_hnsw_index_float32_rejects_nan_vector() throws IOException {
        int dimensions = 3;

        try (LsmTree tree = buildTree(1024 * 1024)) {
            VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.COSINE).maxConnections(4)
                    .efConstruction(16).efSearch(16).precision(VectorPrecision.FLOAT32).build();

            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, new float[]{ 0.0f, Float.NaN, 0.0f }),
                    "FLOAT32 Hnsw index should reject NaN vector components");
        }
    }

    // Finding: F-R5.dispatch_routing.1.3
    // Bug: Hnsw node written after bidirectional neighbor updates causes trimToM to silently
    // drop new node's backlink — trimToM looks up the new node via lsmTree.get() but it
    // hasn't been written yet, so it's skipped and excluded from the trim selection
    // Correct behavior: The new node should be written to the LSM tree before bidirectional
    // updates so trimToM can look it up and include it in scoring
    // Fix location: LsmVectorIndex.java Hnsw.index() — write new node before the neighbor
    // update loop, then update it again after with final neighbor lists
    // Regression watch: Ensure the new node's final neighbor lists are correctly written
    // (the second write must include all selected neighbors)
    @Test
    void test_hnsw_trimToM_retains_new_node_backlink_when_neighbor_at_capacity()
            throws IOException {
        int dimensions = 3;
        // maxConnections=2 so that by the 4th node insertion, existing nodes are at
        // capacity and trimToM fires during bidirectional updates
        int maxConnections = 2;

        try (LsmTree tree = buildTree(1024 * 1024)) {
            VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.COSINE).maxConnections(maxConnections)
                    .efConstruction(16).efSearch(16).precision(VectorPrecision.FLOAT32).build();

            // Index a cluster of very similar vectors. With maxConnections=2, inserting
            // more than 3 nodes forces trimToM on at least one neighbor.
            // All vectors are nearly identical so the new node should always be a strong
            // candidate to survive trimToM — if trimToM can look it up.
            float[][] vectors = { { 1.0f, 0.0f, 0.0f }, // node 0
                    { 0.99f, 0.01f, 0.0f }, // node 1 — very close to 0
                    { 0.98f, 0.02f, 0.0f }, // node 2 — very close to 0 and 1
                    { 0.97f, 0.03f, 0.0f }, // node 3 — triggers trimToM on full nodes
                    { 0.96f, 0.04f, 0.0f }, // node 4
            };

            for (int i = 0; i < vectors.length; i++) {
                index.index((long) i, vectors[i]);
            }

            // After indexing 5 very similar vectors with maxConnections=2, search should
            // be able to find ALL of them from any starting point via graph traversal.
            // If trimToM silently dropped backlinks (because the new node wasn't written
            // yet), some nodes become unreachable — search from the entry point can't
            // traverse to them and they're missing from results.
            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 5);

            // All 5 nodes are very similar to the query — they should all be reachable
            Set<Long> found = new HashSet<>();
            for (VectorIndex.SearchResult<Long> r : results) {
                found.add(r.docId());
            }

            assertEquals(5, found.size(),
                    "All 5 nodes should be reachable via search but only found " + found
                            + " — missing nodes are likely unreachable due to trimToM "
                            + "dropping backlinks for nodes that weren't written yet");
        }
    }

    // Finding: F-R5.dispatch_routing.1.1
    // Bug: IvfFlat search() decodes centroids via decodeFloats (always FLOAT32) but centroids are
    // stored via encodeVector at the configured precision — FLOAT16 centroids decoded as
    // FLOAT32 produces ArrayIndexOutOfBoundsException or garbage centroid vectors during search
    // Correct behavior: search() centroid loading should use decodeVector with the configured
    // precision, matching the encoding path used in assignCentroid()
    // Fix location: LsmVectorIndex.java search() line 493 (decodeFloats → decodeVector)
    // Regression watch: Ensure FLOAT32 search still works after the fix (decodeVector dispatches
    // correctly)
    @Test
    void test_ivfFlat_search_centroid_decoded_at_configured_precision_float16() throws IOException {
        int dimensions = 3;

        try (LsmTree tree = buildTree(1024 * 1024)) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.COSINE).numClusters(2).nprobe(2)
                    .precision(VectorPrecision.FLOAT16).build();

            // Index two vectors — first becomes centroid 0, second becomes centroid 1
            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f, 0.0f });
            // Third vector assigned to nearest centroid (both centroids now exist)
            index.index(3L, new float[]{ 0.9f, 0.1f, 0.0f });

            // Search triggers centroid loading in search() which uses decodeFloats (FLOAT32)
            // but centroids are stored at FLOAT16 — this should not throw and should return results
            List<VectorIndex.SearchResult<Long>> results = index
                    .search(new float[]{ 1.0f, 0.0f, 0.0f }, 3);

            assertFalse(results.isEmpty(),
                    "Search with FLOAT16 precision should return results but centroid "
                            + "decode mismatch (decodeFloats on FLOAT16 data) caused failure");
            // The indexed vector {1,0,0} should be found
            assertTrue(results.stream().anyMatch(r -> r.docId() == 1L),
                    "Search should find docId=1 which is identical to the query vector");
        }
    }
}

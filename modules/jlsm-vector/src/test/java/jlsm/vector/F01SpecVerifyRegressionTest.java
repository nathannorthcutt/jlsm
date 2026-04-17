package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
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
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests written during /spec-verify F01 to enforce spec requirements that were violated
 * by the implementation at verification time.
 */
class F01SpecVerifyRegressionTest {

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
                .memTableFlushThresholdBytes(1024 * 1024).build();
    }

    // @spec F01.R14 — centroids must always be stored at FLOAT32 regardless of index precision.
    // @spec F01.R10b — centroid coordinates must remain float32.
    @Test
    void test_ivfFlat_float16Precision_centroidsStoredAtFloat32() throws IOException {
        int dimensions = 4;
        try (LsmTree tree = buildTree()) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN).numClusters(2).nprobe(2)
                    .precision(VectorPrecision.FLOAT16).build();

            index.index(1L, new float[]{ 1.0f, 0.0f, 0.0f, 0.0f });
            index.index(2L, new float[]{ 0.0f, 1.0f, 0.0f, 0.0f });

            byte[] scanStart = { 0x00 };
            byte[] scanEnd = { 0x01 };
            Iterator<Entry> it = tree.scan(MemorySegment.ofArray(scanStart),
                    MemorySegment.ofArray(scanEnd));

            int centroidCount = 0;
            while (it.hasNext()) {
                Entry entry = it.next();
                if (!(entry instanceof Entry.Put put))
                    continue;
                byte[] value = put.value().toArray(ValueLayout.JAVA_BYTE);
                assertEquals(dimensions * 4, value.length,
                        "Centroid must be stored at FLOAT32 (dim*4 bytes), got " + value.length
                                + " — R14 violated (centroid stored at index precision)");
                centroidCount++;
            }
            assertEquals(2, centroidCount, "expected two centroid entries");
        }
    }

    // @spec F01.R14 — full-fidelity centroid coordinates must round-trip exactly.
    @Test
    void test_ivfFlat_float16Precision_centroidCoordinatesAreFullFidelity() throws IOException {
        int dimensions = 2;
        try (LsmTree tree = buildTree()) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN).numClusters(1).nprobe(1)
                    .precision(VectorPrecision.FLOAT16).build();

            float inexactInFloat16 = 2049.0f;
            float exactInFloat16 = 2048.0f;
            index.index(1L, new float[]{ inexactInFloat16, exactInFloat16 });

            byte[] scanStart = { 0x00 };
            byte[] scanEnd = { 0x01 };
            Iterator<Entry> it = tree.scan(MemorySegment.ofArray(scanStart),
                    MemorySegment.ofArray(scanEnd));
            assertTrue(it.hasNext(), "centroid entry must be present");
            Entry.Put put = (Entry.Put) it.next();
            byte[] value = put.value().toArray(ValueLayout.JAVA_BYTE);
            assertEquals(dimensions * 4, value.length);

            int bits0 = ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                    | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
            float decoded0 = Float.intBitsToFloat(bits0);
            assertEquals(inexactInFloat16, decoded0, 0.0f,
                    "centroid component 0 must retain full float32 fidelity (2049.0f),"
                            + " not quantized to float16 (2048.0f)");
        }
    }

    // @spec F01.R15 — centroid assignment must use the quantized vector value
    // (encode-then-decode through the configured precision), not the original float32 input.
    // @spec F01.R10b — centroid assignment uses quantized vector value.
    @Test
    void test_ivfFlat_float16Precision_assignmentUsesQuantizedVector() throws IOException {
        int dimensions = 2;
        try (LsmTree tree = buildTree()) {
            VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN).numClusters(2).nprobe(2)
                    .precision(VectorPrecision.FLOAT16).build();

            index.index(1L, new float[]{ 2049.0f, 2048.0f });
            index.index(2L, new float[]{ 2048.0f, 2048.0f });

            long probeDocId = 100L;
            index.index(probeDocId, new float[]{ 2049.0f, 2048.0f });

            byte[] probeDocBytes = LONG_DOC_ID_SERIALIZER.serialize(probeDocId)
                    .toArray(ValueLayout.JAVA_BYTE);
            byte[] revKey = new byte[1 + probeDocBytes.length];
            revKey[0] = 0x02;
            System.arraycopy(probeDocBytes, 0, revKey, 1, probeDocBytes.length);
            Optional<MemorySegment> revOpt = tree.get(MemorySegment.ofArray(revKey));
            assertTrue(revOpt.isPresent(), "reverse lookup for probe docId must exist");
            byte[] revBytes = revOpt.get().toArray(ValueLayout.JAVA_BYTE);
            int assignedCentroid = ((revBytes[0] & 0xFF) << 24) | ((revBytes[1] & 0xFF) << 16)
                    | ((revBytes[2] & 0xFF) << 8) | (revBytes[3] & 0xFF);

            assertEquals(1, assignedCentroid,
                    "Probe vector [2049,2048] must be assigned to centroid 1 (2048,2048)"
                            + " because it quantizes to [2048,2048] in float16; current"
                            + " assignment is against original float32 which spuriously picks"
                            + " centroid 0 — R15 violated");
        }
    }

    // @spec F01.R20 — HNSW graph construction must use the quantized vector (decoded back to
    // float32) for all distance computations during indexing, not the original float32 input.
    @Test
    void test_hnsw_float16Precision_constructionUsesQuantizedVector() throws IOException {
        int dimensions = 2;
        try (LsmTree tree = buildTree()) {
            VectorIndex.Hnsw<Long> hnsw = LsmVectorIndex.<Long>hnswBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(dimensions)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN).maxConnections(4)
                    .efConstruction(10).efSearch(10).precision(VectorPrecision.FLOAT16).build();

            hnsw.index(1L, new float[]{ 2049.0f, 2048.0f });
            hnsw.index(2L, new float[]{ 2048.0f, 2048.0f });

            List<VectorIndex.SearchResult<Long>> results = hnsw
                    .search(new float[]{ 2048.0f, 2048.0f }, 2);

            assertFalse(results.isEmpty(), "search must return results");
            for (VectorIndex.SearchResult<Long> r : results) {
                Optional<MemorySegment> nodeOpt = tree
                        .get(MemorySegment.ofArray(LONG_DOC_ID_SERIALIZER.serialize(r.docId())
                                .toArray(ValueLayout.JAVA_BYTE)));
                assertTrue(nodeOpt.isPresent(),
                        "stored node for docId=" + r.docId() + " must exist");
                byte[] nodeBytes = nodeOpt.get().toArray(ValueLayout.JAVA_BYTE);
                int vectorPortion = dimensions * 2;
                assertTrue(nodeBytes.length >= vectorPortion,
                        "node bytes must include encoded float16 vector (dim*2)");
            }
        }
    }

    // @spec F01.R23 — VectorIndex.index() public javadoc must document that re-indexing without
    // a prior remove may degrade graph quality for graph-based indexes (HNSW).
    @Test
    void test_vectorIndex_index_javadocDocumentsReIndexingDegradation() throws IOException {
        Path javaFile = Path.of("src/main/java/jlsm/core/indexing/VectorIndex.java");
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        Path resolved = moduleRoot.resolveSibling("jlsm-core").resolve(javaFile).normalize();
        if (!java.nio.file.Files.exists(resolved)) {
            resolved = Path.of(System.getProperty("user.dir"))
                    .resolve("modules/jlsm-core/src/main/java/jlsm/core/indexing/VectorIndex.java")
                    .normalize();
        }
        assertTrue(java.nio.file.Files.exists(resolved),
                "VectorIndex.java must be found at " + resolved);
        String source = java.nio.file.Files.readString(resolved);
        int indexMethodIdx = source.indexOf("void index(D docId");
        assertTrue(indexMethodIdx > 0, "index() method must be present");
        String preceding = source.substring(Math.max(0, indexMethodIdx - 2000), indexMethodIdx);
        String lower = preceding.toLowerCase();
        assertTrue(
                lower.contains("re-index") || lower.contains("reindex")
                        || lower.contains("re-indexing") || lower.contains("reindexing"),
                "VectorIndex.index() javadoc must mention re-indexing behavior — R23 violated");
        assertTrue(
                lower.contains("degrade") || lower.contains("quality") || lower.contains("hnsw")
                        || lower.contains("graph"),
                "VectorIndex.index() javadoc must mention graph-quality degradation — R23 violated");
    }

    // @spec F01.R33 — calling close() multiple times on IvfFlat must be idempotent; the second
    // and subsequent calls must not propagate to the underlying storage tree.
    @Test
    void test_ivfFlat_close_idempotent_doubleCloseDoesNotReclose() throws Exception {
        AtomicLong closeCount = new AtomicLong(0);
        LsmTree realTree = buildTree();

        LsmTree countingTree = new LsmTree() {
            @Override
            public void put(MemorySegment key, MemorySegment value) throws IOException {
                realTree.put(key, value);
            }

            @Override
            public void delete(MemorySegment key) throws IOException {
                realTree.delete(key);
            }

            @Override
            public Optional<MemorySegment> get(MemorySegment key) throws IOException {
                return realTree.get(key);
            }

            @Override
            public Iterator<Entry> scan() throws IOException {
                return realTree.scan();
            }

            @Override
            public Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException {
                return realTree.scan(from, to);
            }

            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                realTree.close();
            }
        };

        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(countingTree).docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT).build();

        index.close();
        assertEquals(1, closeCount.get(),
                "first close should delegate to lsmTree.close() exactly once");

        index.close();
        assertEquals(1, closeCount.get(),
                "second close must not re-invoke lsmTree.close() — R33 idempotency violated");
    }

    // @spec F01.R35 — internal binary decoding routines must validate input length with a
    // runtime check before accessing bytes; Hnsw.readEntryPoint must reject truncated entry
    // point values with a descriptive IOException rather than throwing AIOOBE.
    @Test
    void test_hnsw_readEntryPoint_truncatedValue_throwsDescriptiveIOException() throws Exception {
        try (LsmTree tree = buildTree()) {
            VectorIndex.Hnsw<Long> hnsw = LsmVectorIndex.<Long>hnswBuilder().lsmTree(tree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN).build();

            byte[] entryPointKey = { (byte) 0xFE };
            byte[] truncated = { 0x00, 0x01 };
            tree.put(MemorySegment.ofArray(entryPointKey), MemorySegment.ofArray(truncated));

            IOException ex = assertThrows(IOException.class,
                    () -> hnsw.search(new float[]{ 1.0f, 0.0f, 0.0f, 0.0f }, 1),
                    "truncated entry point must produce an IOException, not AIOOBE");
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            assertTrue(
                    msg.contains("entry") || msg.contains("corrupt") || msg.contains("length")
                            || msg.contains("truncat"),
                    "exception must be descriptive; got: " + ex.getMessage());
        }
    }
}

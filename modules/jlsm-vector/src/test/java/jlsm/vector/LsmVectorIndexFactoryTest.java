package jlsm.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;

/**
 * Exercises the {@link LsmVectorIndexFactory} end-to-end: a real LSM-backed vector index produced
 * by the factory must satisfy the index/search/remove/close contract, and the factory must isolate
 * each {@code (tableName, fieldName)} pair on its own storage.
 */
class LsmVectorIndexFactoryTest {

    @TempDir
    Path tempDir;

    private static MemorySegment utf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static String decode(MemorySegment seg) {
        return new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    @Test
    void ivfFlatFactory_producesWorkingVectorIndex() throws IOException {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .numClusters(4).nprobe(4).build();
        try (VectorIndex<MemorySegment> idx = factory.create("docs", "embedding", 3,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN)) {
            assertTrue(idx instanceof VectorIndex.IvfFlat);
            idx.index(utf8("a"), new float[]{ 0.0f, 0.0f, 0.0f });
            idx.index(utf8("b"), new float[]{ 1.0f, 1.0f, 1.0f });
            idx.index(utf8("c"), new float[]{ 10.0f, 10.0f, 10.0f });

            List<VectorIndex.SearchResult<MemorySegment>> hits = idx
                    .search(new float[]{ 0.0f, 0.0f, 0.0f }, 2);
            assertEquals(2, hits.size(), "topK=2 must return 2 hits");
            assertEquals("a", decode(hits.get(0).docId()),
                    "closest result must be exact match 'a'");
        }
    }

    @Test
    void hnswFactory_producesWorkingVectorIndex() throws IOException {
        VectorIndex.Factory factory = LsmVectorIndexFactory.hnsw().rootDirectory(tempDir).build();
        try (VectorIndex<MemorySegment> idx = factory.create("docs", "embedding", 3,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN)) {
            assertTrue(idx instanceof VectorIndex.Hnsw);
            idx.index(utf8("a"), new float[]{ 0.0f, 0.0f, 0.0f });
            idx.index(utf8("b"), new float[]{ 1.0f, 1.0f, 1.0f });
            List<VectorIndex.SearchResult<MemorySegment>> hits = idx
                    .search(new float[]{ 0.0f, 0.0f, 0.0f }, 1);
            assertNotNull(hits);
            assertFalse(hits.isEmpty(), "hnsw must return at least one hit");
        }
    }

    @Test
    void factory_removesDocument() throws IOException {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .numClusters(2).nprobe(2).build();
        try (VectorIndex<MemorySegment> idx = factory.create("docs", "v", 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN)) {
            idx.index(utf8("a"), new float[]{ 0.0f, 0.0f });
            idx.index(utf8("b"), new float[]{ 1.0f, 1.0f });
            idx.remove(utf8("a"));
            List<VectorIndex.SearchResult<MemorySegment>> hits = idx
                    .search(new float[]{ 0.0f, 0.0f }, 5);
            for (VectorIndex.SearchResult<MemorySegment> r : hits) {
                assertFalse(decode(r.docId()).equals("a"), "removed doc must not appear in search");
            }
        }
    }

    @Test
    void factory_isolatesIndicesByTableAndField() throws IOException {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .numClusters(2).nprobe(2).build();
        try (VectorIndex<MemorySegment> users = factory.create("users", "embedding", 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN);
                VectorIndex<MemorySegment> articles = factory.create("articles", "embedding", 2,
                        VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN)) {
            users.index(utf8("u1"), new float[]{ 1.0f, 1.0f });
            articles.index(utf8("a1"), new float[]{ 1.0f, 1.0f });

            // Each index must only see its own docs.
            List<VectorIndex.SearchResult<MemorySegment>> userHits = users
                    .search(new float[]{ 1.0f, 1.0f }, 10);
            List<VectorIndex.SearchResult<MemorySegment>> articleHits = articles
                    .search(new float[]{ 1.0f, 1.0f }, 10);
            assertEquals(1, userHits.size());
            assertEquals("u1", decode(userHits.get(0).docId()));
            assertEquals(1, articleHits.size());
            assertEquals("a1", decode(articleHits.get(0).docId()));
        }
    }

    @Test
    void factory_float16Precision_indexAndSearch() throws IOException {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .numClusters(2).nprobe(2).build();
        try (VectorIndex<MemorySegment> idx = factory.create("docs", "v", 2,
                VectorPrecision.FLOAT16, SimilarityFunction.EUCLIDEAN)) {
            assertEquals(VectorPrecision.FLOAT16, idx.precision());
            idx.index(utf8("a"), new float[]{ 1.0f, 2.0f });
            List<VectorIndex.SearchResult<MemorySegment>> hits = idx
                    .search(new float[]{ 1.0f, 2.0f }, 1);
            assertEquals(1, hits.size());
        }
    }

    @Test
    void factory_rejectsNullTableName() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class, () -> factory.create(null, "f", 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
    }

    @Test
    void factory_rejectsNullFieldName() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class, () -> factory.create("t", null, 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
    }

    @Test
    void factory_rejectsBlankNames() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(IllegalArgumentException.class, () -> factory.create("", "f", 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
        assertThrows(IllegalArgumentException.class, () -> factory.create("t", "", 2,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
    }

    @Test
    void factory_rejectsNonPositiveDimensions() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(IllegalArgumentException.class, () -> factory.create("t", "f", 0,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
        assertThrows(IllegalArgumentException.class, () -> factory.create("t", "f", -1,
                VectorPrecision.FLOAT32, SimilarityFunction.EUCLIDEAN));
    }

    @Test
    void factory_rejectsNullPrecision() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class,
                () -> factory.create("t", "f", 2, null, SimilarityFunction.EUCLIDEAN));
    }

    @Test
    void factory_rejectsNullSimilarityFunction() {
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(tempDir)
                .build();
        assertThrows(NullPointerException.class,
                () -> factory.create("t", "f", 2, VectorPrecision.FLOAT32, null));
    }

    @Test
    void ivfFlatBuilder_rejectsNullRootDirectory() {
        assertThrows(NullPointerException.class,
                () -> LsmVectorIndexFactory.ivfFlat().rootDirectory(null).build());
    }

    @Test
    void ivfFlatBuilder_rejectsMissingRootDirectory() {
        assertThrows(NullPointerException.class, () -> LsmVectorIndexFactory.ivfFlat().build());
    }

    @Test
    void hnswBuilder_rejectsMissingRootDirectory() {
        assertThrows(NullPointerException.class, () -> LsmVectorIndexFactory.hnsw().build());
    }
}

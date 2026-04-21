package jlsm.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.table.FieldType;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.Predicate;
import jlsm.table.StandardJlsmTable;
import jlsm.table.UpdateMode;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.SecondaryIndex;
import jlsm.table.internal.StringKeyedTable;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.TypedStandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;

/**
 * End-to-end integration of {@link LsmVectorIndexFactory} with {@link StandardJlsmTable}. Exercises
 * OBL-F10-vector: a table with a VECTOR index must accept writes and serve nearest-neighbour
 * queries without silently dropping mutations.
 *
 * <p>
 * These tests deliberately reach into {@code jlsm.table.internal} to verify the
 * {@link IndexRegistry} state because the public scan API does not yet expose secondary-index query
 * wiring — that binding is the scope of {@code OBL-F05-R37} (a separate WD).
 */
class VectorTableIntegrationTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private TypedLsmTree.StringKeyed<JlsmDocument> buildTypedTree(JlsmSchema schema)
            throws IOException {
        Path primaryDir = tempDir.resolve("primary-" + idCounter.get());
        java.nio.file.Files.createDirectories(primaryDir);
        final MemorySerializer<JlsmDocument> codec = jlsm.table.DocumentSerializer
                .forSchema(schema);
        return TypedStandardLsmTree.<JlsmDocument>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(primaryDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader
                        .open(path, BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> primaryDir
                        .resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .valueSerializer(codec).build();
    }

    // @spec query.index-types.R6 — table with VECTOR index accepts writes and serves
    // @spec query.vector-index.R1,R2,R3,R4,R5,R6 — table with VECTOR index accepts writes and serves
    // nearest-neighbour queries via the registry's VectorFieldIndex adapter.
    @Test
    void createReadDeleteTable_withVectorIndex_endToEnd() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("docs", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 3)).build();

        Path indexRoot = tempDir.resolve("vec-index-root");
        java.nio.file.Files.createDirectories(indexRoot);
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(indexRoot)
                .numClusters(4).nprobe(4).build();

        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema)
                .addIndex(new IndexDefinition("embedding", IndexType.VECTOR,
                        SimilarityFunction.EUCLIDEAN))
                .vectorFactory(factory).build();

        try (table) {
            // Creates must not silently drop (the previous stub behaviour).
            table.create("d1",
                    JlsmDocument.of(schema, "embedding", new float[]{ 0.0f, 0.0f, 0.0f }));
            table.create("d2",
                    JlsmDocument.of(schema, "embedding", new float[]{ 1.0f, 1.0f, 1.0f }));
            table.create("d3",
                    JlsmDocument.of(schema, "embedding", new float[]{ 10.0f, 10.0f, 10.0f }));

            IndexRegistry registry = ((StringKeyedTable) table).indexRegistry();
            assertFalse(registry.isEmpty(), "registry must contain the VECTOR index");

            Set<String> topTwo = queryVector(registry, "embedding", new float[]{ 0.0f, 0.0f, 0.0f },
                    2);
            // d1 is the exact match, d2 is the second-closest; d3 is far away.
            assertTrue(topTwo.contains("d1"),
                    "closest-to-origin result must include exact match d1; got " + topTwo);
            assertEquals(2, topTwo.size(), "topK=2 must return 2 docs; got " + topTwo);
            assertFalse(topTwo.contains("d3"),
                    "far-away d3 must not appear in top-2; got " + topTwo);

            // Update — old vector removed, new vector indexed (R88).
            table.update("d1",
                    JlsmDocument.of(schema, "embedding", new float[]{ 100.0f, 100.0f, 100.0f }),
                    UpdateMode.REPLACE);
            topTwo = queryVector(registry, "embedding", new float[]{ 0.0f, 0.0f, 0.0f }, 2);
            assertFalse(topTwo.contains("d1"),
                    "after update d1 must no longer be close to origin; got " + topTwo);

            // Delete — d2 removed (R89)
            table.delete("d2");
            Set<String> all = queryVector(registry, "embedding", new float[]{ 0.0f, 0.0f, 0.0f },
                    10);
            assertFalse(all.contains("d2"), "after delete d2 must not appear");
        }
    }

    // @spec query.vector-index.R1 — table builder must reject a VECTOR definition when no factory is
    // supplied, surfacing mis-wiring at build() rather than on the first write.
    @Test
    void tableBuilder_rejectsVectorWithoutFactory() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("docs", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 2)).build();

        var builder = StandardJlsmTable.stringKeyedBuilder().lsmTree(buildTypedTree(schema))
                .schema(schema).addIndex(new IndexDefinition("embedding", IndexType.VECTOR,
                        SimilarityFunction.EUCLIDEAN));

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, builder::build,
                "VECTOR without factory should surface as build-time IAE");
        assertTrue(iae.getMessage().contains("VectorIndex.Factory"),
                "failure message must explain the missing factory: " + iae.getMessage());
    }

    // @spec query.vector-index.R6 — closing the table closes the registry which closes the VectorFieldIndex
    // adapter which closes the underlying VectorIndex; no resources leak.
    @Test
    void tableClose_closesVectorIndex() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("docs", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 2)).build();

        Path indexRoot = tempDir.resolve("vec-close");
        java.nio.file.Files.createDirectories(indexRoot);
        VectorIndex.Factory factory = LsmVectorIndexFactory.ivfFlat().rootDirectory(indexRoot)
                .numClusters(2).nprobe(2).build();

        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema)
                .addIndex(new IndexDefinition("embedding", IndexType.VECTOR,
                        SimilarityFunction.EUCLIDEAN))
                .vectorFactory(factory).build();

        table.create("d1", JlsmDocument.of(schema, "embedding", new float[]{ 1.0f, 2.0f }));
        table.close();
        IndexRegistry registry = ((StringKeyedTable) table).indexRegistry();
        assertThrows(IllegalStateException.class, () -> registry
                .findIndex(new Predicate.VectorNearest("embedding", new float[]{ 1.0f, 2.0f }, 1)));
    }

    @Test
    void noIndexDefinitions_tableBehavesAsBefore() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("t", 1)
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 2)).build();
        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema).build();
        try (table) {
            table.create("k1", JlsmDocument.of(schema, "embedding", new float[]{ 1.0f, 2.0f }));
            assertEquals(1.0f, table.get("k1").orElseThrow().getFloat32Vector("embedding")[0],
                    1e-6);
            // @spec engine.in-process-database-engine.R37 (WD-03) — a schema-configured table always materialises an
            // IndexRegistry (even with zero index definitions) so table.query() can execute
            // scan-and-filter queries. The registry must be present and empty.
            assertTrue(
                    table instanceof StringKeyedTable skt && skt.indexRegistry() != null
                            && skt.indexRegistry().isEmpty(),
                    "schema + no indexDefinitions → empty registry (was: no registry; changed by WD-03 to enable query binding)");
        }
    }

    private static Set<String> queryVector(IndexRegistry registry, String field, float[] q,
            int topK) throws IOException {
        SecondaryIndex idx = registry.findIndex(new Predicate.VectorNearest(field, q, topK));
        if (idx == null) {
            return Set.of();
        }
        Iterator<MemorySegment> it = idx.lookup(new Predicate.VectorNearest(field, q, topK));
        Set<String> hits = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        while (it.hasNext()) {
            String s = new String(it.next().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            hits.add(s);
            ordered.add(s);
        }
        return hits;
    }
}

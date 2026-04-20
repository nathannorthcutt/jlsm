package jlsm.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.FullTextIndex;
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
 * End-to-end integration of {@link LsmFullTextIndexFactory} with {@link StandardJlsmTable}.
 * Exercises OBL-F10-fulltext: a table with a FULL_TEXT index must accept writes and serve full-text
 * queries without throwing {@link UnsupportedOperationException}.
 *
 * <p>
 * These tests deliberately reach into {@code jlsm.table.internal} to verify the
 * {@link IndexRegistry} state because the public scan API does not yet expose secondary-index query
 * wiring — that binding is the scope of {@code OBL-F05-R37} (a separate WD).
 */
class FullTextTableIntegrationTest {

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

    // @spec F10.R5,R79,R80,R81,R82,R83,R84 — table with FULL_TEXT index accepts writes and
    // serves full-text queries via the registry's FullTextFieldIndex adapter.
    @Test
    void createReadDeleteTable_withFullTextIndex_endToEnd() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("users", 1).field("bio", FieldType.string()).build();

        Path indexRoot = tempDir.resolve("ft-index-root");
        java.nio.file.Files.createDirectories(indexRoot);
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(indexRoot)
                .build();

        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema)
                .addIndex(new IndexDefinition("bio", IndexType.FULL_TEXT)).fullTextFactory(factory)
                .build();

        try (table) {
            // Creates must not throw UOE (the previous stub behaviour).
            table.create("u1", JlsmDocument.of(schema, "bio", "hello world"));
            table.create("u2", JlsmDocument.of(schema, "bio", "foo bar"));
            table.create("u3", JlsmDocument.of(schema, "bio", "hello there"));

            // Exercise the full-text index via the registry accessor. This is the same
            // IndexRegistry StringKeyedTable just populated.
            IndexRegistry registry = ((StringKeyedTable) table).indexRegistry();
            assertFalse(registry.isEmpty(), "registry must contain the FULL_TEXT index");

            Set<String> hits = queryFullText(registry, "bio", "hello");
            assertEquals(Set.of("u1", "u3"), hits,
                    "FULL_TEXT lookup must return documents containing 'hello'");

            // Update — old terms removed, new terms indexed (R82).
            table.update("u1", JlsmDocument.of(schema, "bio", "totally different"),
                    UpdateMode.REPLACE);
            hits = queryFullText(registry, "bio", "hello");
            assertEquals(Set.of("u3"), hits, "after update 'hello' must no longer match u1");
            hits = queryFullText(registry, "bio", "different");
            assertEquals(Set.of("u1"), hits, "updated term must be indexed");

            // Delete — all terms for u3 must go (R83).
            table.delete("u3");
            hits = queryFullText(registry, "bio", "hello");
            assertTrue(hits.isEmpty(), "after delete u3 must not appear");
        }
    }

    // @spec F10.R79 — table builder must reject a FULL_TEXT definition when no factory is
    // supplied, surfacing mis-wiring at build() rather than on the first write.
    @Test
    void tableBuilder_rejectsFullTextWithoutFactory() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("users", 1).field("bio", FieldType.string()).build();

        var builder = StandardJlsmTable.stringKeyedBuilder().lsmTree(buildTypedTree(schema))
                .schema(schema).addIndex(new IndexDefinition("bio", IndexType.FULL_TEXT));

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, builder::build,
                "FULL_TEXT without factory should surface as build-time IAE");
        assertTrue(iae.getMessage().contains("FullTextIndex.Factory"),
                "failure message must explain the missing factory: " + iae.getMessage());
    }

    /**
     * Drives a FullTextMatch through the registry's index to return matching primary keys as
     * strings.
     */
    private static Set<String> queryFullText(IndexRegistry registry, String field, String query)
            throws IOException {
        SecondaryIndex idx = registry.findIndex(new Predicate.FullTextMatch(field, query));
        if (idx == null) {
            return Set.of();
        }
        Iterator<java.lang.foreign.MemorySegment> it = idx
                .lookup(new Predicate.FullTextMatch(field, query));
        Set<String> hits = new HashSet<>();
        while (it.hasNext()) {
            hits.add(new String(it.next().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return hits;
    }

    // @spec F10.R84 — closing the table closes the registry which closes the FullTextFieldIndex
    // adapter which closes the underlying FullTextIndex; no resources leak.
    @Test
    void tableClose_closesFullTextIndex() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("posts", 1).field("body", FieldType.string())
                .build();

        Path indexRoot = tempDir.resolve("ft-close");
        java.nio.file.Files.createDirectories(indexRoot);
        FullTextIndex.Factory factory = LsmFullTextIndexFactory.builder().rootDirectory(indexRoot)
                .build();

        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema)
                .addIndex(new IndexDefinition("body", IndexType.FULL_TEXT)).fullTextFactory(factory)
                .build();

        table.create("p1", JlsmDocument.of(schema, "body", "the quick brown fox"));
        table.close();
        // After close, the registry must be closed too; calling findIndex after close should
        // throw IllegalStateException (documented registry close semantics).
        IndexRegistry registry = ((StringKeyedTable) table).indexRegistry();
        assertThrows(IllegalStateException.class,
                () -> registry.findIndex(new Predicate.FullTextMatch("body", "hello")));
    }

    @Test
    void noIndexDefinitions_tableBehavesAsBefore() throws IOException {
        JlsmSchema schema = JlsmSchema.builder("t", 1).field("body", FieldType.string()).build();
        JlsmTable.StringKeyed table = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTypedTree(schema)).schema(schema).build();
        try (table) {
            table.create("k1", JlsmDocument.of(schema, "body", "hi"));
            assertEquals("hi", table.get("k1").orElseThrow().getString("body"));
            assertTrue(table instanceof StringKeyedTable skt && skt.indexRegistry() == null,
                    "no indexDefinitions → no registry");
        }
    }

}

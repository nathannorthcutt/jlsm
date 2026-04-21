package jlsm.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.TypedStandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;

/**
 * End-to-end tests covering {@link TableQuery#execute()} through a {@link JlsmTable.StringKeyed}
 * built via {@link StandardJlsmTable}.
 *
 * <p>
 * Resolves OBL-F05-R37: {@code Table.query()} must produce a functional {@link TableQuery} that
 * runs against the table's primary tree and its registered secondary indices.
 */
// @spec engine.in-process-database-engine.R37 — Table.query() returns a bound TableQuery
// @spec query.table-query.R8,R9 — execute() returns Iterator<TableEntry<K>> routed through
// QueryExecutor; unbound TableQuery.execute() throws UnsupportedOperationException.
class TableQueryExecutionTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private JlsmSchema personSchema() {
        return JlsmSchema.builder("person", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).field("city", FieldType.Primitive.STRING)
                .build();
    }

    private TypedLsmTree.StringKeyed<JlsmDocument> buildTree(JlsmSchema schema, String suffix)
            throws IOException {
        final Path dir = tempDir.resolve("primary-" + suffix + "-" + idCounter.get());
        Files.createDirectories(dir);
        final MemorySerializer<JlsmDocument> codec = DocumentSerializer.forSchema(schema);
        return TypedStandardLsmTree.<JlsmDocument>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(dir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(1024 * 1024).valueSerializer(codec).build();
    }

    private JlsmTable.StringKeyed buildTable(JlsmSchema schema, String suffix,
            List<IndexDefinition> indices) throws IOException {
        final var builder = StandardJlsmTable.stringKeyedBuilder()
                .lsmTree(buildTree(schema, suffix)).schema(schema);
        for (IndexDefinition def : indices) {
            builder.addIndex(def);
        }
        return builder.build();
    }

    private JlsmTable.StringKeyed buildTable(JlsmSchema schema, String suffix) throws IOException {
        return buildTable(schema, suffix, List.of());
    }

    private static <T> List<T> collect(Iterator<T> it) {
        var list = new ArrayList<T>();
        it.forEachRemaining(list::add);
        return list;
    }

    private static Set<String> keys(List<TableEntry<String>> entries) {
        var keys = new HashSet<String>();
        for (TableEntry<String> e : entries) {
            keys.add(e.key());
        }
        return keys;
    }

    // ── query() returns non-null ─────────────────────────────────────────

    @Test
    void query_returnsNonNull_whenSchemaConfigured() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "non-null")) {
            TableQuery<String> q = table.query();
            assertTrue(q != null, "query() must return a non-null TableQuery");
        }
    }

    // ── Bound query: index-backed leaf predicate ─────────────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — Eq on indexed field routes through
    // IndexRegistry.findAndLookup
    void boundQuery_eqOnIndexedField_usesIndex() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "eq-indexed",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));
            table.create("p2", JlsmDocument.of(schema, "name", "Bob", "age", 25, "city", "LA"));
            table.create("p3", JlsmDocument.of(schema, "name", "Alice", "age", 35, "city", "SF"));

            TableQuery<String> q = table.query().where("name").eq("Alice");
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(2, results.size(), "should find both Alice entries");
            assertEquals(Set.of("p1", "p3"), keys(results));
        }
    }

    // ── Bound query: scan-fallback on non-indexed field ──────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — predicate on unindexed field falls back to
    // scan-and-filter
    void boundQuery_scanFallbackWhenNoMatchingIndex() throws IOException {
        final JlsmSchema schema = personSchema();
        // Register an EQUALITY index on a *different* field so the registry exists but
        // the predicate below has no matching index and must fall back to scan.
        try (var table = buildTable(schema, "scan-fallback",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));
            table.create("p2", JlsmDocument.of(schema, "name", "Bob", "age", 25, "city", "NYC"));
            table.create("p3", JlsmDocument.of(schema, "name", "Carol", "age", 35, "city", "LA"));

            TableQuery<String> q = table.query().where("city").eq("NYC");
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(2, results.size(), "scan-and-filter should find both NYC entries");
            assertEquals(Set.of("p1", "p2"), keys(results));
        }
    }

    // ── Bound query: combined index + scan (AND) ─────────────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — AND across indexed and unindexed predicates
    // intersects child result sets
    void boundQuery_andCombinesIndexAndScanPredicates() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "and-combine",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));
            table.create("p2", JlsmDocument.of(schema, "name", "Alice", "age", 25, "city", "LA"));
            table.create("p3", JlsmDocument.of(schema, "name", "Bob", "age", 30, "city", "NYC"));

            TableQuery<String> q = table.query().where("name").eq("Alice").and("city").eq("NYC");
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(1, results.size(), "only Alice in NYC matches");
            assertEquals("p1", results.get(0).key());
        }
    }

    // ── Bound query: OR union ────────────────────────────────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — OR unions child result sets with deduplication
    void boundQuery_orUnionsPredicates() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "or-union",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));
            table.create("p2", JlsmDocument.of(schema, "name", "Bob", "age", 25, "city", "LA"));
            table.create("p3", JlsmDocument.of(schema, "name", "Carol", "age", 35, "city", "SF"));

            TableQuery<String> q = table.query().where("name").eq("Alice").or("name").eq("Bob");
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(2, results.size(), "OR of Alice and Bob returns both");
            assertEquals(Set.of("p1", "p2"), keys(results));
        }
    }

    // ── Bound query: empty result set ────────────────────────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — empty result on predicate with no matches
    void boundQuery_emptyResultWhenNoMatches() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "empty",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));

            TableQuery<String> q = table.query().where("name").eq("Zelda");
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(0, results.size(), "no matches → empty iterator");
        }
    }

    // ── Bound query: range (Gte) on unindexed field ──────────────────────

    @Test
    // @spec engine.in-process-database-engine.R37 — Gte falls back to scan-and-filter when no RANGE
    // index is registered
    void boundQuery_gteScanFallback() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "gte-scan",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 20, "city", "NYC"));
            table.create("p2", JlsmDocument.of(schema, "name", "Bob", "age", 30, "city", "LA"));
            table.create("p3", JlsmDocument.of(schema, "name", "Carol", "age", 40, "city", "SF"));

            TableQuery<String> q = table.query().where("age").gte(30);
            List<TableEntry<String>> results = collect(q.execute());

            assertEquals(2, results.size(), "Bob (30) and Carol (40) match age>=30");
            assertEquals(Set.of("p2", "p3"), keys(results));
        }
    }

    // ── Unbound TableQuery: execute() throws UOE ─────────────────────────

    @Test
    // @spec query.table-query.R9 — an unbound TableQuery (constructed without a binding context)
    // throws UOE
    void unboundTableQuery_executeThrowsUOE() {
        TableQuery<String> unbound = TableQuery.unbound();
        unbound.where("foo").eq("bar");
        assertThrows(UnsupportedOperationException.class, unbound::execute);
    }

    // ── Schema mismatch: unknown field surfaces a clear exception ────────

    @Test
    // @spec engine.in-process-database-engine.R37 — query against a non-existent field surfaces
    // IllegalArgumentException via
    // QueryExecutor/schema field lookup
    void boundQuery_unknownField_throwsIAE() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "unknown-field",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            table.create("p1", JlsmDocument.of(schema, "name", "Alice", "age", 30, "city", "NYC"));

            TableQuery<String> q = table.query().where("nonexistent").eq("value");
            assertThrows(IllegalArgumentException.class, q::execute);
        }
    }

    // ── Predicate tree exposure is preserved on bound queries ────────────

    @Test
    void boundQuery_predicateTreeRemainsInspectable() throws IOException {
        final JlsmSchema schema = personSchema();
        try (var table = buildTable(schema, "predicate-inspect",
                List.of(new IndexDefinition("name", IndexType.EQUALITY)))) {
            TableQuery<String> q = table.query().where("name").eq("Alice");
            assertInstanceOf(Predicate.Eq.class, q.predicate());
        }
    }
}

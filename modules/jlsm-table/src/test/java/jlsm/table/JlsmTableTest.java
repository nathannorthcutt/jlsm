package jlsm.table;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class JlsmTableTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).field("email", FieldType.Primitive.STRING)
                .build();
    }

    private TypedLsmTree.StringKeyed<JlsmDocument> buildStringTree(Path dir, JlsmSchema schema)
            throws IOException {
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

    private TypedLsmTree.LongKeyed<JlsmDocument> buildLongTree(Path dir, JlsmSchema schema)
            throws IOException {
        final MemorySerializer<JlsmDocument> codec = DocumentSerializer.forSchema(schema);
        return TypedStandardLsmTree.<JlsmDocument>longKeyedBuilder()
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

    // -----------------------------------------------------------------------
    // StringKeyed tests
    // -----------------------------------------------------------------------

    @Test
    void stringKeyed_createAndGet_returnsDocument() throws IOException {
        final Path dir = tempDir.resolve("test_createAndGet");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30, "email",
                    "alice@example.com");

            table.create("user1", doc);

            final Optional<JlsmDocument> result = table.get("user1");
            assertTrue(result.isPresent(), "document should be present");
            assertEquals("Alice", result.get().getString("name"));
            assertEquals(30, result.get().getInt("age"));
            assertEquals("alice@example.com", result.get().getString("email"));
        }
    }

    @Test
    void stringKeyed_getMissing_returnsEmpty() throws IOException {
        final Path dir = tempDir.resolve("test_getMissing");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final Optional<JlsmDocument> result = table.get("nonexistent");
            assertTrue(result.isEmpty(), "should return empty for missing key");
        }
    }

    @Test
    void stringKeyed_createDuplicate_throwsDuplicateKeyException() throws IOException {
        final Path dir = tempDir.resolve("test_createDuplicate");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

            table.create("user1", doc);

            assertThrows(DuplicateKeyException.class, () -> table.create("user1", doc));
        }
    }

    @Test
    void stringKeyed_updateReplace_replacesDocument() throws IOException {
        final Path dir = tempDir.resolve("test_updateReplace");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument original = JlsmDocument.of(schema, "name", "Alice", "age", 30,
                    "email", "alice@old.com");
            table.create("user1", original);

            final JlsmDocument updated = JlsmDocument.of(schema, "name", "Alice B.", "age", 31,
                    "email", "alice@new.com");
            table.update("user1", updated, UpdateMode.REPLACE);

            final Optional<JlsmDocument> result = table.get("user1");
            assertTrue(result.isPresent());
            assertEquals("Alice B.", result.get().getString("name"));
            assertEquals(31, result.get().getInt("age"));
            assertEquals("alice@new.com", result.get().getString("email"));
        }
    }

    @Test
    void stringKeyed_updatePatch_preservesNullFields() throws IOException {
        final Path dir = tempDir.resolve("test_updatePatch");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument original = JlsmDocument.of(schema, "name", "Alice", "age", 30,
                    "email", "alice@example.com");
            table.create("user1", original);

            // Patch only the age field; name and email are null in the patch doc
            final JlsmDocument patch = JlsmDocument.of(schema, "age", 31);
            table.update("user1", patch, UpdateMode.PATCH);

            final Optional<JlsmDocument> result = table.get("user1");
            assertTrue(result.isPresent());
            assertEquals("Alice", result.get().getString("name"),
                    "name should be preserved from original");
            assertEquals(31, result.get().getInt("age"), "age should be updated");
            assertEquals("alice@example.com", result.get().getString("email"),
                    "email should be preserved from original");
        }
    }

    @Test
    void stringKeyed_updateMissing_throwsKeyNotFoundException() throws IOException {
        final Path dir = tempDir.resolve("test_updateMissing");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

            assertThrows(KeyNotFoundException.class,
                    () -> table.update("nonexistent", doc, UpdateMode.REPLACE));
        }
    }

    @Test
    void stringKeyed_delete_makesKeyAbsent() throws IOException {
        final Path dir = tempDir.resolve("test_delete");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
            table.create("user1", doc);

            table.delete("user1");

            final Optional<JlsmDocument> result = table.get("user1");
            assertTrue(result.isEmpty(), "deleted key should return empty");
        }
    }

    @Test
    void stringKeyed_getAllInRange_halfOpenSemantics() throws IOException {
        final Path dir = tempDir.resolve("test_getAllInRange");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildStringTree(dir, schema);
                var table = StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            table.create("a", JlsmDocument.of(schema, "name", "A", "age", 1));
            table.create("b", JlsmDocument.of(schema, "name", "B", "age", 2));
            table.create("c", JlsmDocument.of(schema, "name", "C", "age", 3));
            table.create("d", JlsmDocument.of(schema, "name", "D", "age", 4));

            final Iterator<TableEntry<String>> it = table.getAllInRange("b", "d");
            final List<TableEntry<String>> entries = new ArrayList<>();
            while (it.hasNext()) {
                entries.add(it.next());
            }

            assertEquals(2, entries.size(), "half-open range [b, d) should return b, c");
            assertEquals("b", entries.get(0).key());
            assertEquals("B", entries.get(0).document().getString("name"));
            assertEquals("c", entries.get(1).key());
            assertEquals("C", entries.get(1).document().getString("name"));
        }
    }

    // -----------------------------------------------------------------------
    // LongKeyed tests
    // -----------------------------------------------------------------------

    @Test
    void longKeyed_create_andGet() throws IOException {
        final Path dir = tempDir.resolve("test_longKeyed_createAndGet");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildLongTree(dir, schema);
                var table = StandardJlsmTable.longKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30, "email",
                    "alice@example.com");

            table.create(42L, doc);

            final Optional<JlsmDocument> result = table.get(42L);
            assertTrue(result.isPresent(), "document should be present");
            assertEquals("Alice", result.get().getString("name"));
            assertEquals(30, result.get().getInt("age"));
        }
    }

    @Test
    void longKeyed_getAllInRange_numericOrder() throws IOException {
        final Path dir = tempDir.resolve("test_longKeyed_range");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();

        try (var tree = buildLongTree(dir, schema);
                var table = StandardJlsmTable.longKeyedBuilder().lsmTree(tree).schema(schema)
                        .build()) {
            table.create(1L, JlsmDocument.of(schema, "name", "One", "age", 1));
            table.create(2L, JlsmDocument.of(schema, "name", "Two", "age", 2));
            table.create(3L, JlsmDocument.of(schema, "name", "Three", "age", 3));
            table.create(4L, JlsmDocument.of(schema, "name", "Four", "age", 4));
            table.create(5L, JlsmDocument.of(schema, "name", "Five", "age", 5));

            final Iterator<TableEntry<Long>> it = table.getAllInRange(2L, 5L);
            final List<TableEntry<Long>> entries = new ArrayList<>();
            while (it.hasNext()) {
                entries.add(it.next());
            }

            assertEquals(3, entries.size(), "half-open range [2, 5) should return 2, 3, 4");
            assertEquals(2L, entries.get(0).key());
            assertEquals("Two", entries.get(0).document().getString("name"));
            assertEquals(3L, entries.get(1).key());
            assertEquals("Three", entries.get(1).document().getString("name"));
            assertEquals(4L, entries.get(2).key());
            assertEquals("Four", entries.get(2).document().getString("name"));
        }
    }
}

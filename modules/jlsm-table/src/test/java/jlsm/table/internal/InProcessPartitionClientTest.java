package jlsm.table.internal;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.table.DocumentSerializer;
import jlsm.table.DuplicateKeyException;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.KeyNotFoundException;
import jlsm.table.PartitionDescriptor;
import jlsm.table.Predicate;
import jlsm.table.StandardJlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.TypedStandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InProcessPartitionClientTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();
    }

    private static PartitionDescriptor descriptor(long id) {
        final MemorySegment low = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        final MemorySegment high = MemorySegment.ofArray("z".getBytes(StandardCharsets.UTF_8));
        return new PartitionDescriptor(id, low, high, "local", 0L);
    }

    private JlsmTable.StringKeyed buildTable(Path dir, JlsmSchema schema) throws IOException {
        final MemorySerializer<JlsmDocument> codec = DocumentSerializer.forSchema(schema);
        final TypedLsmTree.StringKeyed<JlsmDocument> tree = TypedStandardLsmTree
                .<JlsmDocument>stringKeyedBuilder()
                .wal(LocalWriteAheadLog.builder().directory(dir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> dir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(1024 * 1024).valueSerializer(codec).build();
        return StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema).build();
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void constructor_nullDescriptor_throwsNullPointerException() throws IOException {
        final Path dir = tempDir.resolve("ctor_null_desc");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema)) {
            assertThrows(NullPointerException.class,
                    () -> new InProcessPartitionClient(null, table));
        }
    }

    @Test
    void constructor_nullTable_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new InProcessPartitionClient(descriptor(1L), null));
    }

    @Test
    void descriptor_returnsConstructorArgument() throws IOException {
        final Path dir = tempDir.resolve("descriptor_roundtrip");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(42L), table)) {
            assertEquals(42L, client.descriptor().id());
        }
    }

    // -------------------------------------------------------------------------
    // create / get
    // -------------------------------------------------------------------------

    @Test
    void create_and_get_roundtrip() throws IOException {
        final Path dir = tempDir.resolve("create_get");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

            client.create("alice", doc);

            final Optional<JlsmDocument> result = client.get("alice");
            assertTrue(result.isPresent(), "document should be present after create");
            assertEquals("Alice", result.get().getString("name"));
            assertEquals(30, result.get().getInt("age"));
        }
    }

    @Test
    void get_missingKey_returnsEmpty() throws IOException {
        final Path dir = tempDir.resolve("get_missing");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final Optional<JlsmDocument> result = client.get("nonexistent");
            assertTrue(result.isEmpty(), "should return empty for missing key");
        }
    }

    @Test
    void create_duplicateKey_throwsDuplicateKeyException() throws IOException {
        final Path dir = tempDir.resolve("create_duplicate");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
            client.create("alice", doc);

            assertThrows(DuplicateKeyException.class, () -> client.create("alice", doc));
        }
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    @Test
    void update_replace_updatesDocument() throws IOException {
        final Path dir = tempDir.resolve("update_replace");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final JlsmDocument original = JlsmDocument.of(schema, "name", "Alice", "age", 30);
            client.create("alice", original);

            final JlsmDocument updated = JlsmDocument.of(schema, "name", "Alice B.", "age", 31);
            client.update("alice", updated, UpdateMode.REPLACE);

            final Optional<JlsmDocument> result = client.get("alice");
            assertTrue(result.isPresent());
            assertEquals("Alice B.", result.get().getString("name"));
            assertEquals(31, result.get().getInt("age"));
        }
    }

    @Test
    void update_missingKey_throwsKeyNotFoundException() throws IOException {
        final Path dir = tempDir.resolve("update_missing");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Bob", "age", 25);

            assertThrows(KeyNotFoundException.class,
                    () -> client.update("nonexistent", doc, UpdateMode.REPLACE));
        }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    void delete_removesDocument() throws IOException {
        final Path dir = tempDir.resolve("delete_removes");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
            client.create("alice", doc);

            client.delete("alice");

            final Optional<JlsmDocument> result = client.get("alice");
            assertTrue(result.isEmpty(), "deleted key should return empty");
        }
    }

    // -------------------------------------------------------------------------
    // getRange
    // -------------------------------------------------------------------------

    @Test
    void getRange_returnsEntriesInHalfOpenRange() throws IOException {
        final Path dir = tempDir.resolve("getRange_halfOpen");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            client.create("a", JlsmDocument.of(schema, "name", "A", "age", 1));
            client.create("b", JlsmDocument.of(schema, "name", "B", "age", 2));
            client.create("c", JlsmDocument.of(schema, "name", "C", "age", 3));
            client.create("d", JlsmDocument.of(schema, "name", "D", "age", 4));

            final Iterator<TableEntry<String>> it = client.getRange("b", "d");
            final List<TableEntry<String>> entries = new ArrayList<>();
            while (it.hasNext()) {
                entries.add(it.next());
            }

            assertEquals(2, entries.size(), "half-open range [b,d) should return b and c");
            assertEquals("b", entries.get(0).key());
            assertEquals("c", entries.get(1).key());
        }
    }

    @Test
    void getRange_emptyRange_returnsNoEntries() throws IOException {
        final Path dir = tempDir.resolve("getRange_empty");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            client.create("z", JlsmDocument.of(schema, "name", "Z", "age", 99));

            final Iterator<TableEntry<String>> it = client.getRange("a", "b");
            assertFalse(it.hasNext(), "no entries should exist in range [a,b)");
        }
    }

    // -------------------------------------------------------------------------
    // query — throws UnsupportedOperationException
    // -------------------------------------------------------------------------

    @Test
    void query_throwsUnsupportedOperationException() throws IOException {
        final Path dir = tempDir.resolve("query_unsupported");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        try (var table = buildTable(dir, schema);
                var client = new InProcessPartitionClient(descriptor(1L), table)) {
            final Predicate pred = new Predicate.Eq("name", "Alice");
            assertThrows(UnsupportedOperationException.class, () -> client.query(pred, 10));
        }
    }

    // -------------------------------------------------------------------------
    // close
    // -------------------------------------------------------------------------

    @Test
    void close_doesNotThrow() throws IOException {
        final Path dir = tempDir.resolve("close_ok");
        Files.createDirectories(dir);
        final JlsmSchema schema = testSchema();
        final JlsmTable.StringKeyed table = buildTable(dir, schema);
        final InProcessPartitionClient client = new InProcessPartitionClient(descriptor(1L), table);
        // Should not throw
        assertDoesNotThrow(client::close);
    }
}

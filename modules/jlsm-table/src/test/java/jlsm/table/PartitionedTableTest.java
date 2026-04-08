package jlsm.table;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.table.internal.InProcessPartitionClient;
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

/**
 * Tests for {@link PartitionedTable} — WU-3 coordinator.
 *
 * <p>
 * Uses real in-process LSM trees via {@link InProcessPartitionClient} to exercise end-to-end
 * routing, range fan-out, and lifecycle management.
 */
class PartitionedTableTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("value", FieldType.Primitive.INT32).build();
    }

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Builds a real JlsmTable.StringKeyed backed by an LSM tree in the given directory. */
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

    /**
     * Builds a two-partition setup: partition 1 = [a, m), partition 2 = [m, z{).
     *
     * <p>
     * Returns a PartitionedTable with both partitions backed by real LSM trees. The caller is
     * responsible for closing the returned table.
     */
    private PartitionedTable buildTwoPartitionTable(Path baseDir, JlsmSchema schema)
            throws IOException {
        final Path dir1 = baseDir.resolve("part1");
        final Path dir2 = baseDir.resolve("part2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("m"), seg("{"), "local",
                0L);
        // Note: '{' is ASCII 123, just above 'z' (122), so [m, {) covers m..z inclusive

        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));
        final JlsmTable.StringKeyed table1 = buildTable(dir1, schema);
        final JlsmTable.StringKeyed table2 = buildTable(dir2, schema);

        return PartitionedTable.builder().partitionConfig(config).schema(schema)
                .partitionClientFactory(desc -> {
                    if (desc.id() == 1L)
                        return new InProcessPartitionClient(desc, table1);
                    if (desc.id() == 2L)
                        return new InProcessPartitionClient(desc, table2);
                    throw new IllegalStateException("unexpected descriptor id: " + desc.id());
                }).build();
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void builder_missingConfig_throwsIllegalStateException() {
        final JlsmSchema schema = testSchema();
        assertThrows(IllegalStateException.class, () -> PartitionedTable.builder().schema(schema)
                .partitionClientFactory(desc -> null).build());
    }

    @Test
    void builder_missingFactory_throwsIllegalStateException() {
        final PartitionDescriptor desc = new PartitionDescriptor(1L, seg("a"), seg("z"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc));
        assertThrows(IllegalStateException.class,
                () -> PartitionedTable.builder().partitionConfig(config).build());
    }

    @Test
    void builder_nullConfig_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> PartitionedTable.builder().partitionConfig(null));
    }

    @Test
    void builder_nullFactory_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> PartitionedTable.builder().partitionClientFactory(null));
    }

    // -------------------------------------------------------------------------
    // config()
    // -------------------------------------------------------------------------

    @Test
    void config_returnsPartitionConfig() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("config_returns");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final PartitionConfig config = table.config();
            assertNotNull(config, "config must not be null");
            assertEquals(2, config.partitionCount(), "expected two partitions");
        }
    }

    // -------------------------------------------------------------------------
    // CRUD routing — single-key operations
    // -------------------------------------------------------------------------

    @Test
    void create_and_get_routesToCorrectPartition() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("crud_routing");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "value", 42);

            // 'b' is in partition 1 [a, m)
            table.create("b", doc);
            final Optional<JlsmDocument> result = table.get("b");
            assertTrue(result.isPresent(), "document should be found in correct partition");
            assertEquals("Alice", result.get().getString("name"));
        }
    }

    @Test
    void get_missingKey_returnsEmpty() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("get_missing");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final Optional<JlsmDocument> result = table.get("b");
            assertTrue(result.isEmpty(), "missing key should return empty");
        }
    }

    @Test
    void create_inPartition2_routesCorrectly() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("crud_part2");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Zeus", "value", 99);

            // 'z' is in partition 2 [m, {)
            table.create("z", doc);
            final Optional<JlsmDocument> result = table.get("z");
            assertTrue(result.isPresent(), "document in partition 2 should be found");
            assertEquals("Zeus", result.get().getString("name"));
        }
    }

    @Test
    void create_duplicateKey_throwsDuplicateKeyException() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("crud_duplicate");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "value", 42);
            table.create("b", doc);

            assertThrows(DuplicateKeyException.class, () -> table.create("b", doc));
        }
    }

    @Test
    void update_routesToCorrectPartition() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("update_routing");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument original = JlsmDocument.of(schema, "name", "Alice", "value", 1);
            final JlsmDocument updated = JlsmDocument.of(schema, "name", "Alice B.", "value", 2);

            table.create("b", original);
            table.update("b", updated, UpdateMode.REPLACE);

            final Optional<JlsmDocument> result = table.get("b");
            assertTrue(result.isPresent());
            assertEquals("Alice B.", result.get().getString("name"));
            assertEquals(2, result.get().getInt("value"));
        }
    }

    @Test
    void delete_routesToCorrectPartition() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("delete_routing");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "value", 1);
            table.create("b", doc);
            table.delete("b");

            final Optional<JlsmDocument> result = table.get("b");
            assertTrue(result.isEmpty(), "deleted key should return empty");
        }
    }

    @Test
    void create_keyOutsideAllRanges_throwsIllegalArgumentException() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("out_of_range");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final JlsmDocument doc = JlsmDocument.of(schema, "name", "OutOfRange", "value", 0);
            // '1' (ASCII 49) is below 'a' (ASCII 97) — outside all partition ranges
            assertThrows(IllegalArgumentException.class, () -> table.create("1", doc));
        }
    }

    // -------------------------------------------------------------------------
    // getRange — multi-partition fan-out
    // -------------------------------------------------------------------------

    @Test
    void getRange_withinSinglePartition_returnsCorrectEntries() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("range_single_part");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            table.create("a", JlsmDocument.of(schema, "name", "A", "value", 1));
            table.create("b", JlsmDocument.of(schema, "name", "B", "value", 2));
            table.create("c", JlsmDocument.of(schema, "name", "C", "value", 3));

            final Iterator<TableEntry<String>> it = table.getRange("a", "c");
            final List<String> keys = collectKeys(it);

            assertEquals(List.of("a", "b"), keys, "half-open [a,c) should return a and b");
        }
    }

    @Test
    void getRange_acrossPartitions_mergesInKeyOrder() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("range_multi_part");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            // Insert keys spanning both partitions:
            // partition 1 [a, m): e, f, g, k
            // partition 2 [m, {): n, p, r
            table.create("e", JlsmDocument.of(schema, "name", "E", "value", 5));
            table.create("f", JlsmDocument.of(schema, "name", "F", "value", 6));
            table.create("g", JlsmDocument.of(schema, "name", "G", "value", 7));
            table.create("k", JlsmDocument.of(schema, "name", "K", "value", 11));
            table.create("n", JlsmDocument.of(schema, "name", "N", "value", 14));
            table.create("p", JlsmDocument.of(schema, "name", "P", "value", 16));
            table.create("r", JlsmDocument.of(schema, "name", "R", "value", 18));

            // Query [f, p) — spans both partitions; should include f,g,k,n
            final Iterator<TableEntry<String>> it = table.getRange("f", "p");
            final List<String> keys = collectKeys(it);

            assertEquals(List.of("f", "g", "k", "n"), keys,
                    "merged range [f,p) should return entries from both partitions in key order");
        }
    }

    @Test
    void getRange_emptyResult_returnsEmptyIterator() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("range_empty");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            table.create("z", JlsmDocument.of(schema, "name", "Z", "value", 99));

            final Iterator<TableEntry<String>> it = table.getRange("a", "b");
            assertFalse(it.hasNext(), "no entries in [a,b) should yield empty iterator");
        }
    }

    // -------------------------------------------------------------------------
    // query — throws UnsupportedOperationException
    // -------------------------------------------------------------------------

    @Test
    void query_throwsUnsupportedOperationException() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("query_unsupported");
        try (var table = buildTwoPartitionTable(baseDir, schema)) {
            final Predicate pred = new Predicate.Eq("name", "Alice");
            assertThrows(UnsupportedOperationException.class, () -> table.query(pred, 10));
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle — close()
    // -------------------------------------------------------------------------

    @Test
    void close_closesAllPartitionClients() throws IOException {
        final JlsmSchema schema = testSchema();
        final Path baseDir = tempDir.resolve("close_lifecycle");
        // Just verify close does not throw; double-close behavior is not specified
        final PartitionedTable table = buildTwoPartitionTable(baseDir, schema);
        assertDoesNotThrow(table::close);
    }

    @Test
    void close_withFailingClient_accumulatesAndThrows() throws IOException {
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("m"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));

        // Client 1 throws on close, client 2 is a no-op — both close() must be called
        final boolean[] client2Closed = { false };
        final PartitionClient failingClient = new StubPartitionClient(desc1) {
            @Override
            public void close() throws IOException {
                throw new IOException("simulated close failure");
            }
        };
        final PartitionClient client2 = new StubPartitionClient(desc2) {
            @Override
            public void close() throws IOException {
                client2Closed[0] = true;
            }
        };

        final PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .partitionClientFactory(desc -> {
                    if (desc.id() == 1L)
                        return failingClient;
                    return client2;
                }).build();

        final IOException thrown = assertThrows(IOException.class, table::close);
        assertTrue(client2Closed[0], "client 2 must be closed even when client 1 throws");
        assertNotNull(thrown, "an IOException must be thrown when a client fails to close");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> collectKeys(Iterator<TableEntry<String>> it) {
        final List<String> keys = new ArrayList<>();
        while (it.hasNext()) {
            keys.add(it.next().key());
        }
        return keys;
    }

    /**
     * Minimal stub PartitionClient for lifecycle tests. All operation methods throw
     * UnsupportedOperationException by default; subclasses override only what they need.
     */
    private abstract static class StubPartitionClient implements PartitionClient {

        private final PartitionDescriptor descriptor;

        StubPartitionClient(PartitionDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public PartitionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void doCreate(String key, JlsmDocument doc) throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Optional<JlsmDocument> doGet(String key) throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void doDelete(String key) throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey)
                throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit)
                throws IOException {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void close() throws IOException {
            // no-op by default
        }
    }
}

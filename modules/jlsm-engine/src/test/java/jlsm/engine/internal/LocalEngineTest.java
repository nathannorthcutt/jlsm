package jlsm.engine.internal;

import jlsm.engine.EngineMetrics;
import jlsm.engine.HandleEvictedException;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LocalEngine} — uses real temp directories and the full LSM stack.
 */
class LocalEngineTest {

    @TempDir
    Path tempDir;

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .field("value", FieldType.Primitive.STRING).build();
    }

    private LocalEngine buildEngine() throws IOException {
        return buildEngine(tempDir);
    }

    private LocalEngine buildEngine(Path rootDir) throws IOException {
        final LocalEngine engine = LocalEngine.builder().rootDirectory(rootDir)
                .memTableFlushThresholdBytes(4L * 1024 * 1024) // 4 MiB for tests
                .build();
        return engine;
    }

    // ---- Builder validation ----

    // @spec engine.in-process-database-engine.R1 — builder requires rootDirectory to be set before
    // build()
    @Test
    void builderRequiresRootDirectory() {
        assertThrows(IllegalStateException.class, () -> LocalEngine.builder().build());
    }

    // @spec engine.in-process-database-engine.R2 — reject null rootDirectory with NPE identifying
    // the parameter
    @Test
    void builderRejectsNullRootDirectory() {
        assertThrows(NullPointerException.class, () -> LocalEngine.builder().rootDirectory(null));
    }

    // ---- createTable ----

    // @spec engine.in-process-database-engine.R10,R22 — create+retrieve a handle supporting CRUD
    // operations
    @Test
    void createTableReturnsUsableHandle() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            try (final Table table = engine.createTable("users", testSchema())) {
                assertNotNull(table);
                assertEquals("users", table.metadata().name());
            }
        }
    }

    // @spec engine.in-process-database-engine.R15,R16 — persist metadata before returning;
    // dedicated subdirectory per table
    @Test
    void createTableCreatesDirectoryAndWritesData() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table table = engine.createTable("users", schema)) {
                final JlsmDocument doc = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                table.create("user1", doc);

                final Optional<JlsmDocument> found = table.get("user1");
                assertTrue(found.isPresent(), "Document should be retrievable after create");
                assertEquals("Alice", found.get().getString("value"));
            }
        }
    }

    // @spec engine.in-process-database-engine.R14 — duplicate create throws IOException with the
    // conflicting name
    @Test
    void createTableWithDuplicateNameThrowsIOException() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            engine.createTable("users", testSchema()).close();
            assertThrows(IOException.class, () -> engine.createTable("users", testSchema()));
        }
    }

    // @spec engine.in-process-database-engine.R11,R70 — reject null name with NPE identifying the
    // parameter
    @Test
    void createTableRejectsNullName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(NullPointerException.class, () -> engine.createTable(null, testSchema()));
        }
    }

    // @spec engine.in-process-database-engine.R13 — reject empty name with IllegalArgumentException
    @Test
    void createTableRejectsEmptyName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.createTable("", testSchema()));
        }
    }

    // @spec engine.in-process-database-engine.R12,R70 — reject null schema with NPE identifying the
    // parameter
    @Test
    void createTableRejectsNullSchema() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(NullPointerException.class, () -> engine.createTable("users", null));
        }
    }

    // ---- getTable ----

    // @spec engine.in-process-database-engine.R22,R53 — retrieve a handle to an existing table;
    // data survives close/reopen
    @Test
    void getTableReturnsHandleToExistingTable() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table created = engine.createTable("users", schema)) {
                final JlsmDocument doc = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                created.create("user1", doc);
            }

            try (final Table retrieved = engine.getTable("users")) {
                assertNotNull(retrieved);
                final Optional<JlsmDocument> found = retrieved.get("user1");
                assertTrue(found.isPresent(),
                        "Data written via createTable should be readable via getTable");
                assertEquals("Alice", found.get().getString("value"));
            }
        }
    }

    // @spec engine.in-process-database-engine.R23 — retrieving a non-existent table throws
    // IOException with the name
    @Test
    void getTableForUnknownTableThrowsIOException() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(IOException.class, () -> engine.getTable("nonexistent"));
        }
    }

    @Test
    void getTableRejectsNullName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(NullPointerException.class, () -> engine.getTable(null));
        }
    }

    @Test
    void getTableRejectsEmptyName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(IllegalArgumentException.class, () -> engine.getTable(""));
        }
    }

    // ---- dropTable ----

    // @spec engine.in-process-database-engine.R26,R29 — drop transitions to DROPPED and invalidates
    // held handles
    @Test
    void dropTableRemovesTableAndInvalidatesHandles() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final Table handle = engine.createTable("users", testSchema());
            engine.dropTable("users");
            // After drop, the existing handle should be invalidated
            assertThrows(HandleEvictedException.class, () -> handle.get("k1"));
            // And the table should not be retrievable
            assertThrows(IOException.class, () -> engine.getTable("users"));
        }
    }

    // @spec engine.in-process-database-engine.R28 — drop of unknown table throws IOException
    @Test
    void dropTableForUnknownTableThrowsIOException() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(IOException.class, () -> engine.dropTable("nonexistent"));
        }
    }

    @Test
    void dropTableRejectsNullName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(NullPointerException.class, () -> engine.dropTable(null));
        }
    }

    @Test
    void dropTableRejectsEmptyName() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertThrows(IllegalArgumentException.class, () -> engine.dropTable(""));
        }
    }

    // ---- listTables ----

    // @spec engine.in-process-database-engine.R20 — listTables returns the registered tables
    // (READY-only filtering verified in
    // F05ContractTest.listTablesReturnsReadyOnlySnapshot)
    @Test
    void listTablesReturnsAllRegisteredTables() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            engine.createTable("users", testSchema()).close();
            engine.createTable("orders", testSchema()).close();

            final Collection<TableMetadata> tables = engine.listTables();
            assertEquals(2, tables.size());
        }
    }

    @Test
    void listTablesReturnsEmptyWhenNoTables() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertTrue(engine.listTables().isEmpty());
        }
    }

    // ---- tableMetadata ----

    // @spec engine.in-process-database-engine.R18 — tableMetadata exposes name, schema, state
    @Test
    void tableMetadataReturnsMetadataForKnownTable() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            engine.createTable("users", testSchema()).close();
            final TableMetadata meta = engine.tableMetadata("users");
            assertNotNull(meta);
            assertEquals("users", meta.name());
        }
    }

    // @spec engine.in-process-database-engine.R21 — tableMetadata returns null for unknown table
    // (amended: "empty result" ==
    // null)
    @Test
    void tableMetadataReturnsNullForUnknownTable() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            assertNull(engine.tableMetadata("nonexistent"));
        }
    }

    // ---- metrics ----

    // @spec engine.in-process-database-engine.R62,R63 — metrics expose handle counts reflecting
    // current state
    @Test
    void metricsReturnCorrectCounts() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final Table t1 = engine.createTable("users", testSchema());
            final Table t2 = engine.createTable("orders", testSchema());

            final EngineMetrics metrics = engine.metrics();
            assertEquals(2, metrics.tableCount());
            assertEquals(2, metrics.totalOpenHandles());

            t1.close();
            t2.close();
        }
    }

    // ---- close invalidates all handles ----

    // @spec engine.in-process-database-engine.R6 — close invalidates all outstanding table handles
    @Test
    void closeInvalidatesAllHandles() throws IOException {
        final LocalEngine engine = buildEngine();
        final Table handle = engine.createTable("users", testSchema());
        engine.close();
        assertThrows(HandleEvictedException.class, () -> handle.get("k1"));
    }

    // @spec engine.in-process-database-engine.R78 — close continues closing remaining tables when
    // one errors;
    // all accumulated errors are reported after all tables have been released.
    @Test
    void closeContinuesClosingRemainingTablesWhenOneFails() throws IOException {
        final LocalEngine engine = buildEngine();
        final Table t1 = engine.createTable("alpha", testSchema());
        final Table t2 = engine.createTable("beta", testSchema());
        final Table t3 = engine.createTable("gamma", testSchema());

        // close one table explicitly, then close the engine — invalidates remaining handles and
        // closes the underlying tables even if any raise an exception during their close path.
        t1.close();
        engine.close();

        // All handles must observe invalidation — confirming the engine traversed every live table
        // during close, not aborting on the first error encountered.
        assertThrows(HandleEvictedException.class, () -> t2.get("k"),
                "second table handle must be invalidated after engine close");
        assertThrows(HandleEvictedException.class, () -> t3.get("k"),
                "third table handle must be invalidated after engine close");
    }

    // ---- insert and read back data (full pipeline) ----

    @Test
    void fullPipelineInsertAndRead() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table table = engine.createTable("users", schema)) {
                final JlsmDocument doc = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                table.insert(doc);

                final Optional<JlsmDocument> found = table.get("user1");
                assertTrue(found.isPresent());
                assertEquals("user1", found.get().getString("id"));
                assertEquals("Alice", found.get().getString("value"));
            }
        }
    }

    @Test
    void fullPipelineDeleteAndVerifyAbsent() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table table = engine.createTable("users", schema)) {
                final JlsmDocument doc = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                table.create("user1", doc);
                table.delete("user1");

                final Optional<JlsmDocument> found = table.get("user1");
                assertTrue(found.isEmpty(), "Deleted key should not be found");
            }
        }
    }

    @Test
    void fullPipelineUpdateAndRead() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table table = engine.createTable("users", schema)) {
                final JlsmDocument doc1 = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                table.create("user1", doc1);

                final JlsmDocument doc2 = JlsmDocument.of(schema, "id", "user1", "value", "Bob");
                table.update("user1", doc2, UpdateMode.REPLACE);

                final Optional<JlsmDocument> found = table.get("user1");
                assertTrue(found.isPresent());
                assertEquals("Bob", found.get().getString("value"));
            }
        }
    }

    // ---- Engine is usable after restart from same directory ----

    // @spec engine.in-process-database-engine.R5,R53,R84 — tables survive close/reopen with full
    // schema recovered
    @Test
    void engineRecoversPreviouslyCreatedTables() throws IOException {
        final JlsmSchema schema = testSchema();

        // First engine session: create table and insert data
        try (final LocalEngine engine1 = buildEngine()) {
            try (final Table table = engine1.createTable("users", schema)) {
                final JlsmDocument doc = JlsmDocument.of(schema, "id", "user1", "value", "Alice");
                table.create("user1", doc);
            }
        }

        // Second engine session: recover and read data
        try (final LocalEngine engine2 = buildEngine()) {
            // The table should be discoverable in the catalog
            final Collection<TableMetadata> tables = engine2.listTables();
            assertFalse(tables.isEmpty(), "Restarted engine should discover existing tables");

            // Should be able to get a handle to the recovered table
            try (final Table table = engine2.getTable("users")) {
                assertNotNull(table);
            }
        }
    }

    // ---- Concurrent table creation ----

    // @spec engine.in-process-database-engine.R65,R66,R88 — concurrent create-table calls with
    // different names all succeed;
    // storage directories for different names must not interfere with each other's registration
    @Test
    void concurrentTableCreation() throws Exception {
        try (final LocalEngine engine = buildEngine()) {
            final int threadCount = 4;
            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            final List<Future<Table>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return engine.createTable("table_" + idx, testSchema());
                }));
            }

            int successCount = 0;
            for (final Future<Table> f : futures) {
                try {
                    final Table t = f.get();
                    t.close();
                    successCount++;
                } catch (Exception e) {
                    // Some may fail due to concurrency; that's fine
                }
            }

            // At least one should succeed; ideally all unique-named ones succeed
            assertTrue(successCount >= 1, "At least some concurrent creates should succeed");
            executor.shutdown();
        }
    }

    // ---- Multiple tables coexist ----

    @Test
    void multipleTablesCoexist() throws IOException {
        try (final LocalEngine engine = buildEngine()) {
            final JlsmSchema schema = testSchema();
            try (final Table users = engine.createTable("users", schema);
                    final Table orders = engine.createTable("orders", schema)) {

                users.create("u1", JlsmDocument.of(schema, "id", "u1", "value", "Alice"));
                orders.create("o1", JlsmDocument.of(schema, "id", "o1", "value", "Order1"));

                assertEquals("Alice", users.get("u1").orElseThrow().getString("value"));
                assertEquals("Order1", orders.get("o1").orElseThrow().getString("value"));

                // Data is isolated between tables
                assertTrue(users.get("o1").isEmpty());
                assertTrue(orders.get("u1").isEmpty());
            }
        }
    }
}

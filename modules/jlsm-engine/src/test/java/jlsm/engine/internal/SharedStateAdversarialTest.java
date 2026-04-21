package jlsm.engine.internal;

import jlsm.engine.Table;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for shared-state concerns in the engine module.
 */
class SharedStateAdversarialTest {

    @TempDir
    Path tempDir;

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .field("value", FieldType.Primitive.STRING).build();
    }

    private LocalEngine buildEngine() throws Exception {
        return LocalEngine.builder().rootDirectory(tempDir)
                .memTableFlushThresholdBytes(4L * 1024 * 1024).build();
    }

    private LocalEngine buildEngineWithLowHandleLimits() throws Exception {
        return LocalEngine.builder().rootDirectory(tempDir)
                .memTableFlushThresholdBytes(4L * 1024 * 1024).maxHandlesPerSourcePerTable(2)
                .maxHandlesPerTable(64).maxTotalHandles(1024).build();
    }

    // Finding: F-R1.shared_state.2.1
    // Bug: LocalEngine.close() has no idempotency guard — double-close retries all cleanup
    // Correct behavior: close() should guard with AtomicBoolean; second close() is a no-op;
    // after close, mutating operations must throw IllegalStateException (R8)
    // Fix location: LocalEngine — add closed field, check in close() and all public methods
    // Regression watch: Ensure close() is idempotent and doesn't throw on double-close
    // @spec engine.in-process-database-engine.R7,R8,R9 — idempotent close; post-close mutating/read
    // ops throw ISE
    @Test
    @Timeout(10)
    void test_LocalEngine_sharedState_doubleCloseRetriesCleanup() throws Exception {
        final LocalEngine engine = buildEngine();
        engine.close();

        // R7: Second close must be a no-op — succeed silently
        assertDoesNotThrow(() -> engine.close(),
                "Second close() must succeed silently (R7: idempotent close)");

        // R8: After close, mutating operations must throw IllegalStateException with
        // a message indicating the engine is closed. Without a closed guard on
        // LocalEngine, this will NOT throw ISE from the engine — the request leaks
        // through to sub-components (catalog throws ISE about "TableCatalog is closed",
        // not "Engine is closed"). The engine must be the one rejecting.
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.createTable("orders", testSchema()),
                "createTable() after close() must throw IllegalStateException (R8)");
        assertTrue(ex.getMessage().toLowerCase().contains("engine"),
                "Exception must originate from the engine (not leak from sub-components), got: "
                        + ex.getMessage());
    }

    // Finding: F-R1.shared_state.2.3
    // Bug: LocalEngine.createTable() does not check catalog loading state — if register()
    // is called before open() completes, an existing table on disk may not yet be in
    // the in-memory tables map, so putIfAbsent succeeds and overwrites metadata.
    // Correct behavior: register() should reject calls while the catalog is still loading
    // Fix location: TableCatalog.register() — add loading check before putIfAbsent
    // Regression watch: Ensure register() after open() still works normally
    @Test
    @Timeout(10)
    void test_TableCatalog_sharedState_registerWhileLoadingOverwritesExistingTable()
            throws Exception {
        // Set up a table directory on disk that would be discovered by open()
        final JlsmSchema schema = testSchema();
        try (final LocalEngine engine = buildEngine()) {
            engine.createTable("preexisting", schema).close();
        }

        // Verify the table directory and metadata file exist on disk
        final Path tableDir = tempDir.resolve("preexisting");
        assertTrue(Files.exists(tableDir), "Table directory must exist on disk");
        assertTrue(Files.exists(tableDir.resolve("table.meta")),
                "Metadata file must exist on disk");

        // Create a new catalog WITHOUT calling open() — loading is still true
        final TableCatalog catalog = new TableCatalog(tempDir);
        // At this point, loading==true and tables map is empty

        // Attempt to register a table that exists on disk but not in the in-memory map.
        // Without a loading guard, this succeeds and overwrites the existing metadata.
        // With a loading guard, this throws IllegalStateException.
        final JlsmSchema differentSchema = JlsmSchema.builder("preexisting", 2)
                .field("id", FieldType.Primitive.STRING).field("extra", FieldType.Primitive.INT64)
                .build();

        assertThrows(IllegalStateException.class,
                () -> catalog.register("preexisting", differentSchema),
                "register() must reject calls while catalog is still loading");
        catalog.close();
    }

    // Finding: F-R1.shared_state.2.2
    // Bug: LocalEngine.getTable() non-atomic check-then-create leaks WAL + tree resources
    // Correct behavior: Only one JlsmTable (WAL + tree) should be created per table name,
    // even under concurrent getTable calls. No double WAL allocation.
    // Fix location: LocalEngine.getTable() lines 153-162 — replace get/putIfAbsent with
    // computeIfAbsent to ensure atomic lazy creation
    // Regression watch: computeIfAbsent lambda must handle checked IOException properly
    @Test
    @Timeout(30)
    void test_LocalEngine_sharedState_getTableRaceLeaksResources() throws Exception {
        // Phase 1: create a table and close the engine — table exists in catalog on disk
        try (final LocalEngine engine = buildEngine()) {
            engine.createTable("orders", testSchema()).close();
        }

        // Delete all WAL segment files so the next engine start sees an empty directory.
        // This forces each WAL construction to call openNewSegment() with CREATE_NEW,
        // which will cause FileAlreadyExistsException if two WALs race.
        final Path tableDir = tempDir.resolve("orders");
        try (var files = Files.list(tableDir)) {
            files.filter(p -> p.getFileName().toString().matches("wal-\\d+\\.log")).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }

        // Phase 2: open a fresh engine — "orders" is in catalog but not in liveTables
        try (final LocalEngine engine = buildEngine()) {
            final int threadCount = 8;
            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            try {
                // Race: all threads call getTable("orders") concurrently.
                // Without atomic lazy creation, multiple threads will each call
                // createJlsmTable() → LocalWriteAheadLog.build() → openNewSegment()
                // with CREATE_NEW on the same segment file, causing
                // FileAlreadyExistsException for losers.
                final List<Future<Table>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return engine.getTable("orders");
                    }));
                }

                // ALL threads must succeed — no IOException from WAL file races
                final List<Table> tables = new java.util.ArrayList<>();
                for (Future<Table> f : futures) {
                    tables.add(f.get(10, TimeUnit.SECONDS));
                }

                // All returned tables must be usable
                for (Table t : tables) {
                    assertNotNull(t, "Table handle from concurrent getTable must not be null");
                }

                // Close all table handles
                for (Table t : tables) {
                    t.close();
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        // If we reach here without IOException, the engine closed cleanly
    }

    // Finding: F-R1.shared_state.2.14
    // Bug: LocalEngine.createTable() uses liveTables.put() which unconditionally overwrites any
    // entry a concurrent getTable().computeIfAbsent() may have already inserted. The
    // overwritten JlsmTable (and its WAL + tree) is leaked — never closed.
    // Correct behavior: createTable() must use putIfAbsent and close the duplicate if another
    // thread already populated the entry (via getTable's computeIfAbsent).
    // Fix location: LocalEngine.createTable() line 114 — replace put() with putIfAbsent()
    // Regression watch: The returned Table must wrap the winning JlsmTable, not the loser
    @Test
    @Timeout(30)
    void test_LocalEngine_sharedState_createTableOverwritesLiveTablesEntry() throws Exception {
        // Use a separate tempDir per iteration to avoid cross-test interference
        final int iterations = 20;
        for (int iter = 0; iter < iterations; iter++) {
            final Path iterDir = tempDir.resolve("iter-" + iter);
            Files.createDirectories(iterDir);

            try (final LocalEngine engine = LocalEngine.builder().rootDirectory(iterDir)
                    .memTableFlushThresholdBytes(4L * 1024 * 1024).build()) {

                final JlsmSchema schema = testSchema();
                final String tableName = "orders";
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final ExecutorService executor = Executors.newFixedThreadPool(2);

                try {
                    // Thread 1: createTable — registers in catalog, creates JlsmTable, puts in
                    // liveTables
                    final Future<Table> createFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return engine.createTable(tableName, schema);
                    });

                    // Thread 2: getTable — waits briefly for catalog entry, then computeIfAbsent
                    final Future<Table> getFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        // Retry getTable until catalog has the entry (createTable must register
                        // first)
                        for (int attempt = 0; attempt < 100; attempt++) {
                            try {
                                return engine.getTable(tableName);
                            } catch (IOException e) {
                                // Table not yet registered — retry
                                Thread.onSpinWait();
                            }
                        }
                        throw new IOException("getTable never succeeded after retries");
                    });

                    // Collect results — both must succeed
                    final Table createdTable = createFuture.get(10, TimeUnit.SECONDS);
                    final Table gottenTable;
                    try {
                        gottenTable = getFuture.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        createdTable.close();
                        continue; // getTable never found the entry — no race this iteration
                    }

                    // Both operations returned a Table. Close them both.
                    createdTable.close();
                    gottenTable.close();
                } finally {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }

            // After engine.close(): count WAL segment files in the table directory.
            // With the bug (put overwrites), the losing JlsmTable is leaked and its WAL
            // segment file is never closed — it may still be locked or there may be
            // orphan segment files from the duplicate WAL construction.
            final Path tableDir = iterDir.resolve("orders");
            if (Files.exists(tableDir)) {
                try (var walFiles = Files.list(tableDir)) {
                    final long walSegmentCount = walFiles
                            .filter(p -> p.getFileName().toString().matches("wal-\\d+\\.log"))
                            .count();
                    // A correct implementation creates exactly 1 WAL per table. If put()
                    // overwrites, the duplicate JlsmTable also created a WAL in the same
                    // directory, producing 2+ WAL segment files.
                    assertTrue(walSegmentCount <= 1,
                            "Iteration " + iter + ": expected at most 1 WAL segment file but found "
                                    + walSegmentCount + " — leaked JlsmTable from put() overwrite");
                }
            }
        }
    }

    // Finding: F-R1.shared_state.2.4
    // Bug: LocalEngine uses Thread.currentThread().getName() as sourceId — not unique.
    // Virtual threads with the same name share per-source-per-table handle limits,
    // causing premature eviction when different callers happen to share a thread name.
    // Correct behavior: Each distinct caller (thread) should get its own sourceId so that
    // per-source-per-table limits are enforced independently.
    // Fix location: LocalEngine.java lines 117, 168 — replace getName() with threadId()
    // Regression watch: Ensure sourceId is still non-null and usable as a map key
    // @spec engine.in-process-database-engine.R42,R91 — source identifier must be thread ID (unique
    // per JVM thread)
    @Test
    @Timeout(30)
    void test_LocalEngine_sharedState_threadNameSourceIdNotUnique() throws Exception {
        // Build engine with a low per-source-per-table limit of 2
        try (final LocalEngine engine = buildEngineWithLowHandleLimits()) {
            engine.createTable("orders", testSchema()).close();

            // Spawn 4 virtual threads all named "shared-worker". Each calls getTable("orders").
            // With getName() as sourceId, all 4 map to sourceId="shared-worker". The limit
            // of 2 per-source-per-table means after the 2nd registration, the 3rd and 4th
            // trigger eviction of the 1st and 2nd — handles that belong to different threads
            // get evicted prematurely.
            final int threadCount = 4;
            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final Set<String> distinctSourceIds = ConcurrentHashMap.newKeySet();

            final Thread.Builder.OfVirtual vtBuilder = Thread.ofVirtual().name("shared-worker");
            final List<Thread> threads = new java.util.ArrayList<>();
            final List<Table> tables = java.util.Collections
                    .synchronizedList(new java.util.ArrayList<>());
            final List<Throwable> errors = java.util.Collections
                    .synchronizedList(new java.util.ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                final Thread vt = vtBuilder.start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        final Table t = engine.getTable("orders");
                        tables.add(t);
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
                threads.add(vt);
            }

            for (final Thread t : threads) {
                t.join(10_000);
            }

            assertTrue(errors.isEmpty(),
                    "All getTable calls must succeed, but got errors: " + errors);
            assertEquals(threadCount, tables.size(),
                    "All threads must have obtained a table handle");

            // Now check the engine metrics — each thread should have been tracked as a
            // distinct source. With getName(), they all map to "shared-worker" and the
            // per-source count is 4 (exceeding the limit of 2, which triggers eviction).
            // With threadId(), each is a distinct source with count 1 — no eviction.
            final var metrics = engine.metrics();
            final var perSourcePerTable = metrics.handlesPerSourcePerTable().get("orders");
            assertNotNull(perSourcePerTable, "Should have per-source tracking for 'orders' table");

            // With the bug (getName), there is only 1 source entry ("shared-worker")
            // and it has been evicted down to maxHandlesPerSourcePerTable (2).
            // With the fix (threadId), there are 4 distinct source entries, each with 1 handle.
            // Assert that we have more than 1 distinct source — proves sources are unique.
            assertTrue(perSourcePerTable.size() > 1,
                    "Each virtual thread must be tracked as a distinct source, but found only "
                            + perSourcePerTable.size() + " source(s): " + perSourcePerTable.keySet()
                            + ". Thread.getName() is not unique for virtual threads.");

            // Assert total handles equals threadCount — no premature eviction occurred
            final int totalHandles = perSourcePerTable.values().stream().mapToInt(Integer::intValue)
                    .sum();
            assertEquals(threadCount, totalHandles,
                    "All " + threadCount + " handles must be tracked without eviction, "
                            + "but only " + totalHandles + " remain — premature eviction "
                            + "from shared sourceId");

            // Clean up
            for (final Table t : tables) {
                t.close();
            }
        }
    }

}

package jlsm.engine.internal;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.engine.EncryptionMetadata;
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

    // Finding: F-R1.shared_state.1.1
    // Bug: TableCatalog.updateEncryption writes the new encryption-aware table.meta to disk
    // before bumping the catalog-index high-water. If catalogIndex.setHighwater fails after
    // writeMetadata succeeded, the on-disk table.meta has been atomically replaced with the
    // encryption-aware format but the in-memory map and catalog-index entry are not updated —
    // memory and disk diverge indefinitely.
    // Correct behavior: updateEncryption is atomic from the caller's perspective. On any
    // failure of setHighwater after writeMetadata, the on-disk table.meta must be restored
    // to its prior plaintext state so memory and disk remain in sync.
    // Fix location: TableCatalog.updateEncryption lines 296-303 — wrap setHighwater in a
    // try/catch that rewrites the prior metadata on failure.
    // Regression watch: rewriting must use the original (existing) metadata and pre-encryption
    // format; any rewrite failure must be added as a suppressed exception.
    @Test
    @Timeout(10)
    void test_TableCatalog_sharedState_updateEncryptionRollbackOnSetHighwaterFailure()
            throws Exception {
        final TableCatalog catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("plain", testSchema());

        final Path metaPath = tempDir.resolve("plain").resolve("table.meta");
        final byte[] originalMeta = Files.readAllBytes(metaPath);
        assertTrue(originalMeta.length > 0, "table.meta must be non-empty after register");

        // Inject a failure for catalog-index persist by replacing the catalog-index.bin file
        // with a non-empty directory of the same name. CatalogIndex.persistAtomically performs
        // Files.move(temp, indexPath, ATOMIC_MOVE, REPLACE_EXISTING) — that move cannot replace
        // a non-empty directory, so it surfaces an IOException AFTER writeMetadata succeeded.
        final Path indexPath = tempDir.resolve("catalog-index.bin");
        Files.deleteIfExists(indexPath);
        Files.createDirectory(indexPath);
        Files.writeString(indexPath.resolve("blocker"), "x");

        final TableScope scope = new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
        final EncryptionMetadata encryption = new EncryptionMetadata(scope);

        // updateEncryption must fail because setHighwater cannot persist the bumped version.
        assertThrows(IOException.class, () -> catalog.updateEncryption("plain", encryption),
                "updateEncryption must fail when catalog-index persist fails");

        // Critical invariant: if updateEncryption surfaced a failure to the caller, the
        // on-disk table.meta must NOT have been left in the partially-committed
        // encryption-aware state. Either the rewrite restored the original bytes, or the
        // implementation deferred the writeMetadata until after setHighwater. In both cases
        // the bytes on disk must equal the original plaintext metadata, keeping disk and
        // memory in sync.
        final byte[] postFailureMeta = Files.readAllBytes(metaPath);
        assertArrayEquals(originalMeta, postFailureMeta,
                "on-disk table.meta must be unchanged after a failed updateEncryption — "
                        + "memory and disk must stay in sync (R7b/R11a atomicity)");

        // Clean up the directory blocker so close() can proceed.
        Files.delete(indexPath.resolve("blocker"));
        Files.delete(indexPath);
        catalog.close();
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: CatalogIndex.setHighwater publishes the new version to in-memory readers via
    // entries.put(...) BEFORE persistAtomically() runs. If persist fails, the value is
    // rolled back, but a concurrent highwater() reader between the put and the rollback
    // observes a transient, not-yet-durable value. The card guarantee "memory and disk
    // stay in sync" is true after rollback but false during the persist window.
    // Correct behavior: setHighwater must not publish a value to in-memory readers until
    // its on-disk persist has succeeded. After a failed setHighwater, no concurrent reader
    // may have observed the failed value (memory == disk transiently as well as after).
    // Fix location: CatalogIndex.setHighwater lines 156-167 — defer entries.put(...) until
    // persistAtomically() returns successfully (or use stage-then-publish).
    // Regression watch: monotonicity check (R9a-mono) must still occur prior to any persist;
    // persistLock must continue to serialise; rollback path becomes a no-op once publish is
    // ordered after persist.
    @Test
    @Timeout(30)
    void test_CatalogIndex_sharedState_setHighwaterPublishesBeforePersistDurable()
            throws Exception {
        // Construct the index against an empty rootDir, then block catalog-index
        // persistence by creating a non-empty directory at the index file path.
        // CatalogIndex.persistAtomically performs
        // Files.move(temp, indexPath, ATOMIC_MOVE, REPLACE_EXISTING) which cannot replace
        // a non-empty directory — every setHighwater attempt will fail in persistAtomically
        // AFTER (with the bug) entries.put has already published.
        final CatalogIndex index = new CatalogIndex(tempDir);
        final Path indexPath = tempDir.resolve("catalog-index.bin");
        Files.deleteIfExists(indexPath);
        Files.createDirectory(indexPath);
        Files.writeString(indexPath.resolve("blocker"), "x");
        try {
            final String tableName = "t1";
            final int newVersion = 5;

            // Race a reader against the failing writer. With publish-before-persist (bug),
            // the reader will, across many iterations, observe newVersion at least once.
            // With publish-after-persist (fix), the reader can never observe newVersion
            // because persist always fails before publication.
            final int iterations = 500;
            final java.util.concurrent.atomic.AtomicBoolean transientObserved = new java.util.concurrent.atomic.AtomicBoolean(
                    false);

            for (int i = 0; i < iterations && !transientObserved.get(); i++) {
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final ExecutorService executor = Executors.newFixedThreadPool(2);
                final java.util.concurrent.atomic.AtomicBoolean writerDone = new java.util.concurrent.atomic.AtomicBoolean(
                        false);
                try {
                    final Future<?> readerFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        // Spin reading highwater until the writer is done. If the bug exists,
                        // some iteration's spin will catch the transient newVersion publication.
                        while (!writerDone.get()) {
                            final var observed = index.highwater(tableName);
                            if (observed.isPresent() && observed.get() == newVersion) {
                                transientObserved.set(true);
                                return null;
                            }
                            Thread.onSpinWait();
                        }
                        // One final read after the writer completed and rolled back.
                        final var finalObserved = index.highwater(tableName);
                        if (finalObserved.isPresent() && finalObserved.get() == newVersion) {
                            transientObserved.set(true);
                        }
                        return null;
                    });

                    final Future<?> writerFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        // Expected to fail with IOException in persistAtomically.
                        try {
                            index.setHighwater(tableName, newVersion);
                            // If this somehow succeeded the test setup is broken.
                            throw new AssertionError("setHighwater unexpectedly succeeded");
                        } catch (IOException expected) {
                            // expected
                        } finally {
                            writerDone.set(true);
                        }
                        return null;
                    });

                    writerFuture.get(10, TimeUnit.SECONDS);
                    readerFuture.get(10, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }

            assertFalse(transientObserved.get(),
                    "Concurrent highwater() reader observed the not-yet-durable version "
                            + newVersion
                            + " during a failing setHighwater — entries.put publishes before "
                            + "persistAtomically completes (transient publish-before-persist).");
        } finally {
            // Clean up the blocker directory so @TempDir teardown doesn't fail.
            try {
                Files.deleteIfExists(indexPath.resolve("blocker"));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(indexPath);
            } catch (IOException ignored) {
            }
        }
    }

    // Finding: F-R1.shared_state.1.2
    // Bug: TableCatalog.registerInternal calls tables.putIfAbsent (publishing a READY entry)
    // BEFORE Files.createDirectories and writeMetadata. A concurrent reader observing the entry
    // via catalog.get(name) sees a READY TableMetadata while the on-disk directory and table.meta
    // do not yet exist — a published-before-fully-formed window.
    // Correct behavior: Any TableMetadata observed via get(name) with state=READY must have its
    // on-disk directory and table.meta file fully formed. The publish-of-READY must follow disk
    // state being durable (or the entry must be staged as LOADING and only transitioned to READY
    // after I/O completes).
    // Fix location: TableCatalog.registerInternal lines 233-252 — sequence the publish after the
    // I/O steps (or use a LOADING placeholder during I/O).
    // Regression watch: TOCTOU name-claim defence must remain — two concurrent registers of the
    // same name must still produce one winner; readers during the I/O window must not observe a
    // bogus READY entry pointing at non-existent disk state.
    @Test
    @Timeout(30)
    void test_TableCatalog_sharedState_registerPublishesBeforeDiskStateExists() throws Exception {
        // Run many independent register+get races. Each iteration uses a fresh catalog and a
        // unique table name so iterations do not interfere. The race window is small (between
        // putIfAbsent at line 240 and Files.createDirectories at line 249), so we use many
        // iterations and a tight reader spin to catch the bug at least once.
        final int iterations = 200;
        boolean violationObserved = false;
        String violationDetail = null;

        for (int i = 0; i < iterations && !violationObserved; i++) {
            final int iter = i;
            final Path iterDir = tempDir.resolve("iter-" + iter);
            Files.createDirectories(iterDir);
            final TableCatalog catalog = new TableCatalog(iterDir);
            catalog.open();
            final String tableName = "t" + iter;
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(
                    false);
            final java.util.concurrent.atomic.AtomicReference<String> violationRef = new java.util.concurrent.atomic.AtomicReference<>();
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // Reader: spin on get(name) and check the invariant — any READY entry must have
                // its on-disk directory AND table.meta file present. Record the first violation.
                final Future<?> readerFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    final Path tableDir = iterDir.resolve(tableName);
                    final Path metaFile = tableDir.resolve("table.meta");
                    while (!done.get()) {
                        final var maybe = catalog.get(tableName);
                        if (maybe.isPresent() && maybe.get()
                                .state() == jlsm.engine.TableMetadata.TableState.READY) {
                            // Invariant: directory and metadata file must already exist.
                            final boolean dirExists = Files.exists(tableDir);
                            final boolean metaExists = Files.exists(metaFile);
                            if (!dirExists || !metaExists) {
                                violationRef.compareAndSet(null,
                                        "iter=" + iter + " READY observed but dirExists="
                                                + dirExists + " metaExists=" + metaExists);
                                return null;
                            }
                            // Once we've successfully observed a fully-formed READY entry, stop.
                            return null;
                        }
                        Thread.onSpinWait();
                    }
                    return null;
                });

                // Writer: call register; this publishes the entry and then performs I/O.
                final Future<?> writerFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    catalog.register(tableName, testSchema());
                    return null;
                });

                writerFuture.get(10, TimeUnit.SECONDS);
                done.set(true);
                readerFuture.get(10, TimeUnit.SECONDS);

                final String violation = violationRef.get();
                if (violation != null) {
                    violationObserved = true;
                    violationDetail = violation;
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                catalog.close();
            }
        }

        assertFalse(violationObserved,
                "Concurrent reader observed a READY TableMetadata before its on-disk state was "
                        + "fully formed (publish-before-disk-state race in registerInternal): "
                        + violationDetail);
    }

    // Finding: F-R1.shared_state.1.4
    // Bug: TableCatalog.open() loads catalogIndex once at line 102, then scans the directory
    // at line 104. If a concurrent JVM (B) atomically updates catalog-index.bin between
    // those two steps — adding entry "t1" at the moment A's directory scan finds "t1/" —
    // A's *in-memory* CatalogIndex is stale (it does not know about "t1"), so the lookup
    // at line 125 returns Optional.empty() and the directory is silently skipped at line
    // 127. The legitimately-registered table is invisible to A's API surface for the
    // lifetime of A's process.
    // Correct behavior: when open() finds a directory whose table.meta exists but whose
    // in-memory catalog-index entry is missing, it must re-read the on-disk catalog-index
    // before silently skipping. If the on-disk index now has the entry (because a peer
    // JVM persisted concurrently), the table must be loaded. R9a-mono cold-start defence
    // is preserved: the table only loads when an authoritative catalog-index entry exists.
    // Fix location: TableCatalog.open() lines 102-128 — collect skipped directories,
    // re-read the on-disk catalog-index after the scan completes, and re-check skipped
    // directories against the fresh index.
    // Regression watch: the cold-start downgrade defence must remain intact — a directory
    // with no on-disk catalog-index entry must still be skipped after re-read.
    @Test
    @Timeout(30)
    void test_TableCatalog_sharedState_openSkipsTableRegisteredByPeerJvmDuringOpenScan()
            throws Exception {
        // Pre-stage the disk state that exists at the race window:
        // - <root>/t-target/table.meta exists and is valid (peer JVM B has written it)
        // - <root>/catalog-index.bin starts empty (peer JVM B has not yet persisted the
        // index entry)
        // Then ensure peer's setHighwater persist completes BETWEEN A's CatalogIndex
        // construction (line 102) and A's deferred re-read (line 154). The fix path
        // re-reads the on-disk catalog-index after the scan and finds the now-present
        // entry; without the fix, the in-memory cache is stale and the directory is
        // silently skipped.
        //
        // To deterministically satisfy "peer's persist completes mid-open()", we create
        // many decoy directories so A's scan loop runs long enough for peer's atomic-move
        // to land. Peer also gets a head start via a barrier so it begins as soon as
        // possible.
        final Path iterDir = tempDir.resolve("race");
        Files.createDirectories(iterDir);

        // Create the target table directory + valid table.meta. Use seed catalog then
        // delete catalog-index.bin so the on-disk state is "B has written meta but
        // not yet persisted the index entry".
        try (final TableCatalog seed = new TableCatalog(iterDir)) {
            seed.open();
            seed.register("t-target", testSchema());
            // Add many decoy tables so the scan loop is long enough that peer's
            // setHighwater has time to complete persistAtomically (which includes a
            // force(true) fsync). The decoys remain indexed and load normally.
            for (int j = 0; j < 200; j++) {
                seed.register("decoy-" + j, testSchema());
            }
        }
        Files.deleteIfExists(iterDir.resolve("catalog-index.bin"));
        // Re-create catalog-index.bin with ONLY the decoy entries so the decoys still
        // load normally — but t-target is missing from the index. This represents
        // peer JVM B's mid-flight state: meta written for all tables, but index
        // entry for t-target not yet persisted.
        try (final TableCatalog reseed = new TableCatalog(iterDir)) {
            reseed.open();
            // open() with no catalog-index sees an empty index; per R9a-mono cold-start
            // defence, all directories with table.meta but no index entry are skipped.
            // We need to rebuild the catalog-index without using register() (which
            // would re-write table.meta). Instead, we use a fresh CatalogIndex helper
            // and only set decoy entries.
        }
        // Manually populate catalog-index.bin with decoy entries only (no t-target).
        // FORMAT_PRE_ENCRYPTION = 0.
        final CatalogIndex helper = new CatalogIndex(iterDir);
        for (int j = 0; j < 200; j++) {
            helper.setHighwater("decoy-" + j, 0);
        }
        // Verify pre-stage: t-target meta exists, catalog-index has decoys only.
        assertTrue(Files.exists(iterDir.resolve("t-target").resolve("table.meta")),
                "Pre-stage: t-target/table.meta must exist");
        assertTrue(Files.exists(iterDir.resolve("catalog-index.bin")),
                "Pre-stage: catalog-index.bin must exist (with decoy entries)");
        assertTrue(new CatalogIndex(iterDir).highwater("t-target").isEmpty(),
                "Pre-stage: catalog-index must NOT yet contain t-target");

        // Step 2: race a fresh open() against a concurrent setHighwater("t-target").
        final TableCatalog catalogA = new TableCatalog(iterDir);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<?> openFuture = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                catalogA.open();
                return null;
            });
            final Future<?> peerFuture = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                // Simulate JVM B's late-arriving setHighwater. We construct a separate
                // CatalogIndex against the same root and persist t-target's entry.
                // catalogA's open() is iterating ~200 decoy directories, so this
                // setHighwater (one open+write+fsync+rename) lands during the scan.
                final CatalogIndex peerIndex = new CatalogIndex(iterDir);
                peerIndex.setHighwater("t-target", 0);
                return null;
            });
            openFuture.get(15, TimeUnit.SECONDS);
            peerFuture.get(15, TimeUnit.SECONDS);

            // After both complete, t-target has a valid table.meta AND catalog-index.bin
            // has its entry. A correct implementation must surface t-target via get():
            // either it loaded it on the first pass (peer's persist happened before
            // catalogA's CatalogIndex construction) OR it re-read the on-disk index
            // after the scan and recovered.
            assertTrue(catalogA.get("t-target").isPresent(),
                    "TableCatalog.open() silently skipped t-target whose catalog-index entry "
                            + "was persisted by a concurrent peer during A's open(). "
                            + "open() must re-read the on-disk catalog-index before treating "
                            + "an unindexed directory as 'not-existent'.");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            catalogA.close();
        }
    }

}

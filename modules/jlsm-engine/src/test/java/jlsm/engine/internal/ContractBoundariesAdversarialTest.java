package jlsm.engine.internal;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.engine.AllocationTracking;
import jlsm.engine.HandleEvictedException;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in the engine handle tracking subsystem.
 */
class ContractBoundariesAdversarialTest {

    // Finding: F-R1.cb.1.1
    // Bug: register() does not call evictIfNeeded — handles accumulate beyond configured limits
    // Correct behavior: After register(), handle count should not exceed
    // maxHandlesPerSourcePerTable
    // Fix location: HandleTracker.register() — should call evictIfNeeded(tableName) after inserting
    // Regression watch: eviction must invalidate oldest handle, not the newly registered one
    // @spec engine.in-process-database-engine.R80 — register must invoke eviction when limits are
    // exceeded
    @Test
    void test_HandleTracker_register_doesNotEnforceLimits() throws IOException {
        final int perSourceLimit = 2;
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(perSourceLimit)
                .maxHandlesPerTable(10).maxTotalHandles(10).build()) {

            // Register 3 handles for the same table+source — exceeds perSourceLimit of 2
            HandleRegistration reg1 = tracker.register("users", "src-1");
            HandleRegistration reg2 = tracker.register("users", "src-1");
            HandleRegistration reg3 = tracker.register("users", "src-1");

            // After registration, limits should be enforced: at most 2 handles should be valid.
            // The oldest handle (reg1) should have been evicted (invalidated).
            assertTrue(reg1.isInvalidated(),
                    "Oldest handle should be evicted when per-source-per-table limit is exceeded");

            // The newest handles should still be valid
            assertFalse(reg3.isInvalidated(), "Newest handle should remain valid after eviction");

            // Total open handles should not exceed the per-source limit
            var snapshot = tracker.snapshot();
            assertTrue(snapshot.totalOpenHandles() <= perSourceLimit,
                    "Total open handles (" + snapshot.totalOpenHandles()
                            + ") should not exceed per-source-per-table limit (" + perSourceLimit
                            + ")");
        }
    }

    // Finding: F-R1.cb.1.2
    // Bug: CatalogTable.checkValid() passes hardcoded 0 for handleCountAtEviction
    // Correct behavior: handleCountAtEviction should reflect the actual number of open handles
    // Fix location: CatalogTable.checkValid() — should query tracker for the real handle count
    // Regression watch: handle count must be queried at eviction-check time, not cached
    @Test
    void test_LocalTable_checkValid_hardcodesZeroHandleCountAtEviction() throws IOException {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
                .build();
        final TableMetadata metadata = new TableMetadata("users", schema, Instant.now(),
                TableMetadata.TableState.READY);

        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(10)
                .maxHandlesPerTable(10).maxTotalHandles(10)
                .allocationTracking(AllocationTracking.OFF).build()) {

            // Register multiple handles so the count is definitely > 0
            HandleRegistration reg1 = tracker.register("users", "src-1");
            HandleRegistration reg2 = tracker.register("users", "src-2");
            HandleRegistration reg3 = tracker.register("users", "src-3");

            // Invalidate reg1 to simulate eviction
            reg1.invalidate(HandleEvictedException.Reason.EVICTION);

            // Create a CatalogTable with the invalidated registration
            final var stub = new StubStringKeyedTable();
            final CatalogTable table = new CatalogTable(stub, reg1, tracker, metadata);

            // Calling get() triggers checkValid() which should throw HandleEvictedException
            final HandleEvictedException ex = assertThrows(HandleEvictedException.class,
                    () -> table.get("key"));

            // The handleCountAtEviction should reflect the actual open handle count (>0)
            // Bug: checkValid() passes hardcoded 0, so handleCountAtEviction() returns 0
            assertTrue(ex.handleCountAtEviction() > 0,
                    "handleCountAtEviction should be > 0 when handles are open, but got "
                            + ex.handleCountAtEviction());
        }
    }

    // Finding: F-R1.cb.1.11
    // Bug: LocalEngine.createTable() does not close JlsmTable on handle registration failure
    // Correct behavior: If handleTracker.register() throws, the JlsmTable created at line 99
    // should be closed, removed from liveTables, and the catalog entry unregistered
    // Fix location: LocalEngine.createTable() lines 99-106 — wrap in try-catch with rollback
    // Regression watch: rollback must close jlsmTable before removing from liveTables/catalog
    // @spec engine.in-process-database-engine.R17,R87 — createTable rollback removes orphaned
    // resources on any failure;
    // if directory creation / registration fails the table must not appear in the catalog on a
    // subsequent startup.
    @Test
    void test_LocalEngine_createTable_doesNotCloseJlsmTableOnHandleRegistrationFailure(
            @TempDir Path tempDir) throws IOException {
        // Build an engine, then close it so that handleTracker is closed.
        // Calling createTable() after close will succeed at catalog.register() and
        // createJlsmTable(), but handleTracker.register() will throw IllegalStateException.
        final LocalEngine engine = LocalEngine.builder().rootDirectory(tempDir)
                .memTableFlushThresholdBytes(4L * 1024 * 1024).build();
        engine.close();

        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
                .build();

        // createTable should throw because handleTracker is closed
        assertThrows(IllegalStateException.class, () -> engine.createTable("leaked", schema));

        // After the failed createTable, the catalog entry should be rolled back.
        // If the bug is present, the catalog still contains "leaked" and a new engine
        // on the same directory would see a table with no properly initialized resources.
        // With the fix, createTable rolls back: closes jlsmTable, removes from liveTables,
        // unregisters from catalog.
        try (LocalEngine engine2 = LocalEngine.builder().rootDirectory(tempDir)
                .memTableFlushThresholdBytes(4L * 1024 * 1024).build()) {

            // If rollback worked, "leaked" should NOT appear in the catalog
            // (because the failed createTable cleaned up after itself).
            // If the bug is present, the catalog on-disk still has the "leaked" table
            // directory and metadata, so the new engine would find it — but the
            // table's WAL/SSTable state may be inconsistent.
            assertDoesNotThrow(() -> engine2.createTable("leaked", schema),
                    "Should be able to create 'leaked' table on a fresh engine — "
                            + "the failed createTable should have rolled back the catalog entry");
        }
    }

    // Finding: F-R1.cb.1.4
    // Bug: invalidateTable() removes sourceMap from ConcurrentHashMap before synchronizing —
    // concurrent register() can create a new unsynchronized map for a dropped table
    // Correct behavior: register() must not succeed for a table that is being invalidated
    // Fix location: HandleTracker.invalidateTable() and/or register() — must prevent
    // registration during or after invalidation of a table
    // Regression watch: existing registrations must still be invalidated; normal
    // register/invalidate
    // without concurrency must continue to work
    @Test
    @Timeout(10)
    void test_HandleTracker_invalidateTable_concurrentRegisterCreatesOrphanedHandle()
            throws Exception {
        // Run multiple iterations to increase the likelihood of hitting the race window.
        // The race: invalidateTable does tableHandles.remove(tableName) then synchronized(old),
        // while register does computeIfAbsent (creates new map) then synchronized(new) —
        // the new registration is never invalidated because invalidateTable synchronized on
        // the old removed map.
        for (int iteration = 0; iteration < 200; iteration++) {
            try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(100)
                    .maxHandlesPerTable(100).maxTotalHandles(100)
                    .allocationTracking(AllocationTracking.OFF).build()) {

                // Pre-register a handle so invalidateTable has something to remove
                HandleRegistration preExisting = tracker.register("users", "pre");

                final CyclicBarrier barrier = new CyclicBarrier(2);
                final AtomicReference<HandleRegistration> concurrentReg = new AtomicReference<>();
                final AtomicReference<Throwable> registerError = new AtomicReference<>();

                // Thread A: invalidateTable
                Thread invalidator = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        tracker.invalidateTable("users",
                                HandleEvictedException.Reason.TABLE_DROPPED);
                    } catch (Exception e) {
                        // ignore barrier exceptions
                    }
                });

                // Thread B: register for the same table
                Thread registerer = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        concurrentReg.set(tracker.register("users", "concurrent-src"));
                    } catch (Throwable e) {
                        registerError.set(e);
                    }
                });

                invalidator.join(5000);
                registerer.join(5000);

                // If register() succeeded (no exception), the handle must either:
                // 1. Have been invalidated (because invalidateTable saw it), OR
                // 2. register() should have been rejected (not allowed for a dropped table)
                // The bug: the handle is valid (not invalidated) for a dropped table.
                HandleRegistration reg = concurrentReg.get();
                if (reg != null) {
                    // A registration was created for a table that was being/has been dropped.
                    // It MUST be invalidated — a non-invalidated handle for a dropped table
                    // is the bug this finding describes.
                    assertTrue(reg.isInvalidated(),
                            "Iteration " + iteration + ": Handle registered concurrently with "
                                    + "invalidateTable must be invalidated — found a live handle "
                                    + "for a dropped table");
                }
                // If registerError is set, register() threw (which is also acceptable behavior
                // since the table was being dropped).

                // Pre-existing handle must always be invalidated
                assertTrue(preExisting.isInvalidated(),
                        "Pre-existing handle must be invalidated after invalidateTable");
            }
        }
    }

    // Finding: F-R1.cb.1.5
    // Bug: No hierarchical limit validation — maxHandlesPerSourcePerTable can exceed
    // maxHandlesPerTable,
    // and maxHandlesPerTable can exceed maxTotalHandles
    // Correct behavior: Builder.build() should reject configurations where
    // maxHandlesPerSourcePerTable > maxHandlesPerTable or maxHandlesPerTable > maxTotalHandles
    // Fix location: HandleTracker.Builder.build() and LocalEngine.Builder.build()
    // Regression watch: valid hierarchies (e.g., 4 <= 16 <= 64) must still be accepted
    // @spec engine.in-process-database-engine.R72,R90 — maxHandlesPerSourcePerTable <=
    // maxHandlesPerTable
    @Test
    void test_HandleTrackerBuilder_allowsNonsensicalHierarchicalLimits() {
        // Per-source (100) > per-table (10) — nonsensical: a single source can never
        // use its full allocation because the table limit evicts first.
        assertThrows(IllegalArgumentException.class,
                () -> HandleTracker.builder().maxHandlesPerSourcePerTable(100)
                        .maxHandlesPerTable(10).maxTotalHandles(1000).build(),
                "maxHandlesPerSourcePerTable (100) exceeding maxHandlesPerTable (10) "
                        + "should be rejected at build time");
    }

    // @spec engine.in-process-database-engine.R73,R90 — maxHandlesPerTable <= maxTotalHandles
    @Test
    void test_HandleTrackerBuilder_allowsPerTableExceedingTotalHandles() {
        // Per-table (100) > total (10) — nonsensical: a single table can never
        // use its full allocation because the total limit evicts first.
        assertThrows(IllegalArgumentException.class,
                () -> HandleTracker.builder().maxHandlesPerSourcePerTable(5).maxHandlesPerTable(100)
                        .maxTotalHandles(10).build(),
                "maxHandlesPerTable (100) exceeding maxTotalHandles (10) "
                        + "should be rejected at build time");
    }

    // @spec engine.in-process-database-engine.R72,R90 — LocalEngine.Builder rejects hierarchy
    // violations
    @Test
    void test_LocalEngineBuilder_allowsNonsensicalHierarchicalLimits(@TempDir Path tempDir) {
        // Same hierarchy violation through LocalEngine.Builder
        assertThrows(IllegalArgumentException.class,
                () -> LocalEngine.builder().rootDirectory(tempDir).maxHandlesPerSourcePerTable(100)
                        .maxHandlesPerTable(10).maxTotalHandles(1000).build(),
                "maxHandlesPerSourcePerTable (100) exceeding maxHandlesPerTable (10) "
                        + "should be rejected at build time in LocalEngine.Builder");
    }

    @Test
    void test_HandleTrackerBuilder_acceptsValidHierarchy() {
        // Valid hierarchy: per-source <= per-table <= total — must not throw
        assertDoesNotThrow(() -> {
            try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(4)
                    .maxHandlesPerTable(16).maxTotalHandles(64).build()) {
                // valid — should succeed
            }
        });
    }

    // Finding: F-R1.cb.1.6
    // Bug: CatalogTable.insert() uses assert-only guard for schema.fields() emptiness —
    // with assertions disabled, getFirst() throws NoSuchElementException instead of
    // a meaningful IllegalStateException
    // Correct behavior: A runtime check should throw IllegalStateException with a clear
    // message about the schema missing a primary key field
    // Fix location: CatalogTable.insert() line 90 — replace assert with runtime if/throw
    // Regression watch: Normal schemas with fields must still work; the exception type
    // must be IllegalStateException (not IllegalArgumentException — the schema is valid
    // per JlsmSchema's contract, but the engine requires at least one field)
    @Test
    void test_LocalTable_insert_assertOnlyGuardForEmptySchemaFields() throws IOException {
        // Create a schema with zero fields — JlsmSchema allows this
        final JlsmSchema emptySchema = JlsmSchema.builder("empty", 1).build();
        final TableMetadata metadata = new TableMetadata("empty_table", emptySchema, Instant.now(),
                TableMetadata.TableState.READY);

        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(10)
                .maxHandlesPerTable(10).maxTotalHandles(10)
                .allocationTracking(AllocationTracking.OFF).build()) {

            final HandleRegistration reg = tracker.register("empty_table", "test-src");
            final var stub = new StubStringKeyedTable();
            final CatalogTable table = new CatalogTable(stub, reg, tracker, metadata);

            // Build a doc using a separate schema that has fields — the table's schema is empty
            final JlsmSchema docSchema = JlsmSchema.builder("doc", 1)
                    .field("id", FieldType.Primitive.STRING).build();
            final JlsmDocument doc = JlsmDocument.of(docSchema, "id", "1");

            // With assertions disabled, the assert is skipped and getFirst() throws
            // NoSuchElementException. The fix should throw IllegalStateException with context.
            final IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> table.insert(doc),
                    "insert() on a table with an empty schema should throw IllegalStateException, "
                            + "not NoSuchElementException");

            // The message should mention the missing primary key field
            assertTrue(ex.getMessage().contains("primary key") || ex.getMessage().contains("field"),
                    "Exception message should mention primary key or field, got: "
                            + ex.getMessage());
        }
    }

    // Finding: F-R1.cb.1.8
    // Bug: HandleEvictedException constructor uses assert-only validation for non-negative
    // handleCountAtEviction — with assertions disabled, negative values are accepted
    // Correct behavior: Constructor should throw IllegalArgumentException for negative
    // handleCountAtEviction values
    // Fix location: HandleEvictedException constructor line 54 — replace assert with runtime check
    // Regression watch: Zero and positive values must still be accepted
    @Test
    void test_HandleEvictedException_acceptsNegativeHandleCountAtEviction() {
        // With assertions disabled (production), the assert at line 54 is skipped.
        // A negative handleCountAtEviction is nonsensical — it should be rejected
        // by a runtime check, not just an assert.
        assertThrows(IllegalArgumentException.class,
                () -> new HandleEvictedException("table", "src", -1, null,
                        HandleEvictedException.Reason.EVICTION),
                "HandleEvictedException should reject negative handleCountAtEviction");
    }

    // Finding: F-R1.contract_boundaries.2.1
    // Bug: LocalEngine wires SSTableWriterFactory as the bare TrieSSTableWriter::new 3-arg
    // constructor reference at line 449 — no WriterCommitHook and no tableNameForLock are
    // threaded through. The R10c writer-finish protocol (acquire per-table catalog lock,
    // re-read fresh encryption scope under the held lease, abort if scope changed mid-write)
    // is therefore structurally absent. A flush triggered after enableEncryption commits a
    // plaintext v5 SSTable into a now-encrypted table directory.
    // Correct behavior: the writer's finish() must consult the freshly-read catalog scope
    // under the per-table catalog lock and refuse to commit when encryption transitioned
    // between writer construction and finish (R10c step 5 IOException). End-to-end, this
    // means: insert() that triggers a flush after enableEncryption() must fail with
    // IOException — never silently commit a plaintext SSTable.
    // Fix location: LocalEngine.createJlsmTable line 449 — wrap the TrieSSTableWriter
    // construction in a factory that supplies a WriterCommitHook backed by catalogLock +
    // catalog and the table name for the lock.
    // Regression watch: tables that never call enableEncryption must still flush normally;
    // the commit hook must only refuse the commit when scope actually transitioned.
    // @spec sstable.footer-encryption-scope.R10c
    @Test
    @Timeout(30)
    void test_LocalEngine_writerFactory_lacksR10cCommitHookWiring(@TempDir Path tempDir)
            throws IOException {
        // Use a tiny flush threshold so the very first insert after enableEncryption forces
        // a flush — that flush goes through the engine's wired SSTableWriterFactory.
        try (LocalEngine engine = LocalEngine.builder().rootDirectory(tempDir)
                .memTableFlushThresholdBytes(1L).build()) {

            final JlsmSchema schema = JlsmSchema.builder("racy", 1)
                    .field("id", FieldType.Primitive.STRING)
                    .field("value", FieldType.Primitive.STRING).build();
            final Table table = engine.createTable("racy", schema);

            // Flip the table to encrypted AFTER the table (and its writer factory) was
            // constructed. The factory captured null scope at build time; the catalog now
            // says the table is encrypted. The R10c protocol requires the writer's finish()
            // to detect this mid-write transition under the per-table catalog lock and
            // refuse to commit — even though no v5 SSTable is yet on disk.
            final TableScope scope = new TableScope(new TenantId("tenant-A"),
                    new DomainId("domain-X"), new TableId("racy"));
            engine.enableEncryption("racy", scope);

            // Insert a single document. With memTableFlushThresholdBytes=1, this triggers
            // a flush; the writer factory wired into the engine now constructs a writer
            // and finish() runs. With the bug, finish() commits a v5 plaintext SSTable
            // and insert() returns normally. With the fix, the writer's commit-hook
            // detects the mid-write transition and throws IOException (R10c step 5).
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            assertThrows(IOException.class, () -> table.insert(doc),
                    "After enableEncryption, a flush triggered by insert() must throw "
                            + "IOException — the writer's R10c commit-hook must refuse "
                            + "to commit a plaintext SSTable into a now-encrypted table");
        }
    }

    // Finding: F-R1.contract_boundaries.2.3
    // Bug: TableCatalog.readMetadata throws `IOException("Unknown metadata format version: " +
    // firstByte)` at line 620, embedding the offending raw byte value in the error message.
    // R12 forbids leaking offending byte values in catalog persistence error messages.
    // Correct behavior: the IOException for an unknown leading format-version byte must NOT
    // contain the offending byte value (decimal, hex, or any other rendering of the byte).
    // The message may identify *that* the format version is unknown but must not reveal *which*
    // byte was on disk.
    // Fix location: TableCatalog.readMetadata line 620 — strip the byte value from the message.
    // Regression watch: known format-version paths (0x4A pre-encryption, 0x02 encryption-aware)
    // must still load normally; only the rejection message changes.
    // @spec sstable.footer-encryption-scope.R12
    @Test
    void test_TableCatalog_readMetadata_unknownFormatVersionLeaksByteValueInR12Message(
            @TempDir Path tempDir) throws Exception {
        // Build a minimal table.meta with an unrecognised leading byte (0xCC). Anything past
        // the first byte is unreachable because the format-version branch throws immediately.
        // Pad to >= 4 bytes so the "metadata file is too short" guard is bypassed and the
        // format-version branch is the one that fires.
        final Path metaPath = tempDir.resolve("table.meta");
        final byte[] tampered = new byte[]{ (byte) 0xCC, 0x00, 0x00, 0x00 };
        java.nio.file.Files.write(metaPath, tampered);

        // Invoke the private static readMetadata via reflection — same package access pattern
        // used by the sibling jlsm.engine.ContractBoundariesAdversarialTest.
        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var readMetadataMethod = catalogClass.getDeclaredMethod("readMetadata", String.class,
                Path.class, int.class);
        readMetadataMethod.setAccessible(true);

        // The reflective invoke wraps the IOException in InvocationTargetException.
        final var ite = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> readMetadataMethod.invoke(null, "victim", metaPath, 0));
        final Throwable cause = ite.getCause();
        assertNotNull(cause, "InvocationTargetException must wrap the underlying IOException");
        assertTrue(cause instanceof IOException,
                "underlying cause must be IOException, got " + cause.getClass().getName());

        final String msg = cause.getMessage();
        assertNotNull(msg, "IOException must carry a message");

        // R12: the offending byte (0xCC == 204 decimal == "cc" hex) must NOT appear in any
        // form in the message. Check the raw byte value, both common renderings, and the
        // signed-byte rendering.
        assertFalse(msg.contains("204"),
                "R12: error message must not contain the offending byte value (decimal '204'); "
                        + "got: " + msg);
        assertFalse(msg.toLowerCase().contains("cc"),
                "R12: error message must not contain the offending byte value (hex 'cc'); "
                        + "got: " + msg);
        assertFalse(msg.contains("-52"),
                "R12: error message must not contain the offending byte value as signed byte "
                        + "('-52'); got: " + msg);
    }

    // ---- Stub for JlsmTable.StringKeyed ----

    private static final class StubStringKeyedTable implements JlsmTable.StringKeyed {

        @Override
        public void create(String key, JlsmDocument doc) throws IOException {
        }

        @Override
        public Optional<JlsmDocument> get(String key) throws IOException {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        }

        @Override
        public void delete(String key) throws IOException {
        }

        @Override
        public Iterator<TableEntry<String>> getAllInRange(String from, String to)
                throws IOException {
            return Collections.emptyIterator();
        }

        @Override
        public void close() throws IOException {
        }
    }
}

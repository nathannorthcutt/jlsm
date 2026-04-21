package jlsm.engine;

import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContractBoundariesAdversarialTest {

    // Finding: F-R1.cb.1.9
    // Bug: EngineMetrics uses assert-only validation for tableCount and totalOpenHandles;
    // with assertions disabled, negative values are silently accepted.
    // Correct behavior: Runtime IllegalArgumentException for negative tableCount or
    // totalOpenHandles
    // Fix location: EngineMetrics.java compact constructor, lines 23-24
    // Regression watch: Ensure existing callers always pass non-negative values
    @Test
    void test_EngineMetrics_negativeTableCount_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new EngineMetrics(-1, 0, Map.of(), Map.of()),
                "tableCount must reject negative values at runtime");
    }

    @Test
    void test_EngineMetrics_negativeTotalOpenHandles_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new EngineMetrics(0, -5, Map.of(), Map.of()),
                "totalOpenHandles must reject negative values at runtime");
    }

    // Finding: F-R1.cb.2.2
    // Bug: writeMetadata serializes field count but not field definitions;
    // readMetadata discards field count and builds a zero-field skeleton schema.
    // Round-trip loses all schema field information.
    // Correct behavior: Recovered metadata should preserve schema field definitions
    // Fix location: TableCatalog.java writeMetadata (lines 204-212) and readMetadata (lines
    // 219-238)
    // Regression watch: Ensure field types (primitive, array, object) survive the round-trip
    @Test
    void test_TableCatalog_metadataRoundTrip_preservesSchemaFields(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("test-schema", 1)
                .field("id", FieldType.Primitive.STRING).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();

        // Access TableCatalog via reflection (package-private class in jlsm.engine.internal)
        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var ctor = catalogClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);

        // Register a table with a schema that has fields
        final Object catalog = ctor.newInstance(tempDir);
        final var openMethod = catalogClass.getDeclaredMethod("open");
        openMethod.setAccessible(true);
        openMethod.invoke(catalog);

        final var registerMethod = catalogClass.getDeclaredMethod("register", String.class,
                JlsmSchema.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(catalog, "users", schema);

        final var closeMethod = catalogClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(catalog);

        // Re-open from disk — readMetadata should reconstruct the schema fields
        final Object recovered = ctor.newInstance(tempDir);
        openMethod.invoke(recovered);
        try {
            final var getMethod = catalogClass.getDeclaredMethod("get", String.class);
            getMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var optMetadata = (java.util.Optional<TableMetadata>) getMethod.invoke(recovered,
                    "users");
            final TableMetadata metadata = optMetadata.orElseThrow(
                    () -> new AssertionError("table 'users' not found after recovery"));

            // The schema must preserve field definitions across the round-trip
            assertEquals(3, metadata.schema().fields().size(),
                    "recovered schema must have the same number of fields as the original");
            assertEquals("id", metadata.schema().fields().get(0).name(),
                    "first field name must be 'id'");
            assertEquals(FieldType.Primitive.STRING, metadata.schema().fields().get(0).type(),
                    "first field type must be STRING");
            assertEquals("name", metadata.schema().fields().get(1).name(),
                    "second field name must be 'name'");
            assertEquals("age", metadata.schema().fields().get(2).name(),
                    "third field name must be 'age'");
            assertEquals(FieldType.Primitive.INT32, metadata.schema().fields().get(2).type(),
                    "third field type must be INT32");
        } finally {
            closeMethod.invoke(recovered);
        }
    }

    // Finding: F-R1.cb.2.3
    // Bug: writeMetadata does not persist TableState; readMetadata always returns LOADING.
    // A READY table round-trips to LOADING after recovery, losing lifecycle state.
    // Correct behavior: Recovered metadata should preserve the persisted TableState
    // Fix location: TableCatalog.java writeMetadata/readMetadata — add state serialization
    // Regression watch: Ensure readMetadata can still parse files written before the fix
    @Test
    void test_TableCatalog_metadataRoundTrip_preservesTableState(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("state-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var ctor = catalogClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);

        // Register a table — register() creates metadata with READY state
        final Object catalog = ctor.newInstance(tempDir);
        final var openMethod = catalogClass.getDeclaredMethod("open");
        openMethod.setAccessible(true);
        openMethod.invoke(catalog);

        final var registerMethod = catalogClass.getDeclaredMethod("register", String.class,
                JlsmSchema.class);
        registerMethod.setAccessible(true);
        final TableMetadata original = (TableMetadata) registerMethod.invoke(catalog, "orders",
                schema);

        // Confirm original state is READY (precondition)
        assertEquals(TableMetadata.TableState.READY, original.state(),
                "registered table must have READY state");

        final var closeMethod = catalogClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(catalog);

        // Re-open from disk — readMetadata should reconstruct the table state
        final Object recovered = ctor.newInstance(tempDir);
        openMethod.invoke(recovered);
        try {
            final var getMethod = catalogClass.getDeclaredMethod("get", String.class);
            getMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var optMetadata = (java.util.Optional<TableMetadata>) getMethod.invoke(recovered,
                    "orders");
            final TableMetadata metadata = optMetadata.orElseThrow(
                    () -> new AssertionError("table 'orders' not found after recovery"));

            // The state must survive the round-trip
            assertEquals(TableMetadata.TableState.READY, metadata.state(),
                    "recovered table state must match persisted state, not default to LOADING");
        } finally {
            closeMethod.invoke(recovered);
        }
    }

    // Finding: F-R1.cb.2.4
    // Bug: open() deletes table directories on transient I/O errors — data loss.
    // When readMetadata throws IOException (e.g., truncated file, disk error),
    // the catch block at lines 84-86 calls deleteDirectoryTree(entry),
    // permanently destroying the table's data directory.
    // Correct behavior: Preserve the table directory and record the table in ERROR state
    // Fix location: TableCatalog.java:84-86 — catch block in open()
    // Regression watch: Ensure genuinely corrupt metadata (bad magic) still reports error state
    // @spec engine.in-process-database-engine.R60,R86 — corrupt table.meta → ERROR state, directory
    // preserved
    @Test
    void test_TableCatalog_open_corruptMetadata_preservesDirectory(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("data-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var ctor = catalogClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);

        // Register a table so its directory and metadata file are created
        final Object catalog = ctor.newInstance(tempDir);
        final var openMethod = catalogClass.getDeclaredMethod("open");
        openMethod.setAccessible(true);
        openMethod.invoke(catalog);

        final var registerMethod = catalogClass.getDeclaredMethod("register", String.class,
                JlsmSchema.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(catalog, "important-data", schema);

        final var closeMethod = catalogClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(catalog);

        // Place a dummy data file in the table directory to simulate real table data
        final Path tableDir = tempDir.resolve("important-data");
        final Path dataFile = tableDir.resolve("sstable-001.dat");
        Files.writeString(dataFile, "precious-data-that-must-not-be-lost");

        // Corrupt the metadata file: write valid magic but truncate mid-stream
        // so readMetadata throws an EOFException (subclass of IOException)
        final Path metadataPath = tableDir.resolve("table.meta");
        try (final DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(metadataPath))) {
            out.writeInt(0x4A4C534D); // valid MAGIC
            // Truncate here — readMetadata will fail reading schema name (EOFException)
        }

        // Re-open the catalog — the corrupt metadata should NOT cause directory deletion
        final Object recovered = ctor.newInstance(tempDir);
        openMethod.invoke(recovered);
        try {
            // The table directory must still exist — data must be preserved
            assertTrue(Files.exists(tableDir),
                    "table directory must not be deleted when metadata read fails");
            assertTrue(Files.exists(dataFile),
                    "table data files must be preserved when metadata is corrupt");

            // The table should appear in ERROR state, not be silently removed
            final var getMethod = catalogClass.getDeclaredMethod("get", String.class);
            getMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var optMetadata = (java.util.Optional<TableMetadata>) getMethod.invoke(recovered,
                    "important-data");
            assertTrue(optMetadata.isPresent(),
                    "table with corrupt metadata must appear in catalog (in ERROR state)");
            assertEquals(TableMetadata.TableState.ERROR, optMetadata.get().state(),
                    "table with corrupt metadata must be in ERROR state");
        } finally {
            closeMethod.invoke(recovered);
        }
    }

    // Finding: F-R1.cb.2.5
    // Bug: No close guard on catalog operations — register() succeeds on a closed catalog,
    // creating orphan directories on disk and inserting into the cleared map.
    // Correct behavior: IllegalStateException thrown when register() is called after close()
    // Fix location: TableCatalog.java register/unregister/get/list/tableDirectory methods
    // Regression watch: Ensure close guard does not interfere with normal pre-close operations
    @Test
    void test_TableCatalog_registerAfterClose_throwsIllegalState(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("close-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var ctor = catalogClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);

        final Object catalog = ctor.newInstance(tempDir);
        final var openMethod = catalogClass.getDeclaredMethod("open");
        openMethod.setAccessible(true);
        openMethod.invoke(catalog);

        // Close the catalog
        final var closeMethod = catalogClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(catalog);

        // Attempt to register on a closed catalog — must throw IllegalStateException
        final var registerMethod = catalogClass.getDeclaredMethod("register", String.class,
                JlsmSchema.class);
        registerMethod.setAccessible(true);

        final var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> registerMethod.invoke(catalog, "orphan-table", schema));
        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "register() on a closed catalog must throw IllegalStateException, but threw: "
                        + thrown.getCause().getClass().getName());
    }

    // Finding: F-R1.cb.2.7
    // Bug: TableMetadata.name emptiness validated only by assert — with assertions disabled
    // (production), empty-name metadata is silently accepted. Must use a runtime check.
    // Correct behavior: IllegalArgumentException thrown for empty name regardless of -ea flag
    // Fix location: TableMetadata.java:26 — replace assert with runtime if/throw
    // Regression watch: Ensure non-empty names still work normally
    @Test
    void test_TableMetadata_emptyName_throwsIllegalArgument() {
        final JlsmSchema schema = JlsmSchema.builder("test-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        // Must throw IllegalArgumentException — NOT AssertionError — for empty name.
        // An AssertionError means the guard is assert-only and disabled in production.
        final var thrown = assertThrows(IllegalArgumentException.class,
                () -> new TableMetadata("", schema, java.time.Instant.now(),
                        TableMetadata.TableState.READY),
                "empty name must be rejected with IllegalArgumentException, not assert");
        assertTrue(thrown.getMessage().contains("empty"), "exception message must mention 'empty'");
    }

    // Finding: F-R1.cb.2.8
    // Bug: LocalTable.metadata() does not call checkValid(), so it returns stale
    // construction-time metadata on an evicted/invalidated handle. Every other
    // operation on LocalTable checks handle validity before proceeding, but
    // metadata() silently returns READY state even after the table is dropped.
    // Correct behavior: metadata() should throw HandleEvictedException on an evicted handle,
    // consistent with all other Table operations
    // Fix location: LocalTable.java:126-128 — metadata() method
    // Regression watch: Ensure metadata() still works on live, valid handles
    @Test
    void test_LocalTable_metadata_throwsOnEvictedHandle(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("meta-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        // Build a LocalEngine via reflection (package-private)
        final Class<?> engineClass = Class.forName("jlsm.engine.internal.LocalEngine");
        final Class<?> builderClass = Class.forName("jlsm.engine.internal.LocalEngine$Builder");
        final Method builderMethod = engineClass.getDeclaredMethod("builder");
        builderMethod.setAccessible(true);
        final Object builder = builderMethod.invoke(null);

        final Method rootDirMethod = builderClass.getDeclaredMethod("rootDirectory", Path.class);
        rootDirMethod.setAccessible(true);
        rootDirMethod.invoke(builder, tempDir);

        final Method buildMethod = builderClass.getDeclaredMethod("build");
        buildMethod.setAccessible(true);
        final Engine engine = (Engine) buildMethod.invoke(builder);

        try {
            // Create a table and get a handle
            final Table handle = engine.createTable("stale-meta", schema);

            // Precondition: metadata() works on a valid handle
            assertEquals(TableMetadata.TableState.READY, handle.metadata().state(),
                    "metadata().state() must be READY on a valid handle");

            // Drop the table — this invalidates all handles
            engine.dropTable("stale-meta");

            // After drop, other operations throw HandleEvictedException
            assertThrows(HandleEvictedException.class, () -> handle.get("any-key"),
                    "get() must throw HandleEvictedException on evicted handle");

            // metadata() must also throw HandleEvictedException — not return stale state
            assertThrows(HandleEvictedException.class, handle::metadata,
                    "metadata() must throw HandleEvictedException on evicted handle, "
                            + "not return stale READY state for a dropped table");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.cb.2.6
    // Bug: TOCTOU race in register — two threads both pass containsKey, both create directories
    // and write metadata, one wins putIfAbsent, the loser's cleanup deletes the shared
    // directory, leaving the winner with a catalog entry pointing to a deleted directory.
    // Correct behavior: After concurrent registration attempts, the winning registration's
    // directory must exist and be intact.
    // Fix location: TableCatalog.java:105-123 — register method
    // Regression watch: Ensure the loser still gets an IOException for duplicate registration
    @Test
    @Timeout(10)
    void test_TableCatalog_concurrentRegister_winnerDirectoryIntact(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("race-schema", 1)
                .field("id", FieldType.Primitive.STRING).build();

        final Class<?> catalogClass = Class.forName("jlsm.engine.internal.TableCatalog");
        final var ctor = catalogClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);

        final Object catalog = ctor.newInstance(tempDir);
        final var openMethod = catalogClass.getDeclaredMethod("open");
        openMethod.setAccessible(true);
        openMethod.invoke(catalog);

        final var registerMethod = catalogClass.getDeclaredMethod("register", String.class,
                JlsmSchema.class);
        registerMethod.setAccessible(true);

        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicReference<Throwable> threadAError = new AtomicReference<>();
        final AtomicReference<Throwable> threadBError = new AtomicReference<>();
        final AtomicReference<TableMetadata> threadAResult = new AtomicReference<>();
        final AtomicReference<TableMetadata> threadBResult = new AtomicReference<>();

        final Runnable task = () -> {
            try {
                barrier.await();
                final TableMetadata result = (TableMetadata) registerMethod.invoke(catalog,
                        "race-table", schema);
                // Determine which thread we are by checking if A or B result is already set
                if (!threadAResult.compareAndSet(null, result)) {
                    threadBResult.set(result);
                }
            } catch (InvocationTargetException e) {
                // Expected for the loser — should be IOException
                if (!threadAError.compareAndSet(null, e.getCause())) {
                    threadBError.set(e.getCause());
                }
            } catch (Exception e) {
                if (!threadAError.compareAndSet(null, e)) {
                    threadBError.set(e);
                }
            }
        };

        final Thread t1 = Thread.ofPlatform().start(task);
        final Thread t2 = Thread.ofPlatform().start(task);
        t1.join(5000);
        t2.join(5000);

        // Exactly one thread must succeed, one must fail with IOException
        final int successes = (threadAResult.get() != null ? 1 : 0)
                + (threadBResult.get() != null ? 1 : 0);
        final Throwable loserError = threadAError.get() != null ? threadAError.get()
                : threadBError.get();

        assertEquals(1, successes, "exactly one concurrent registration must succeed");
        assertNotNull(loserError, "the losing thread must get an error");
        assertInstanceOf(IOException.class, loserError,
                "the losing thread must get an IOException");

        // CRITICAL: the winner's table directory must still exist
        final Path tableDir = tempDir.resolve("race-table");
        assertTrue(Files.exists(tableDir),
                "winner's table directory must exist after concurrent registration — "
                        + "TOCTOU race caused the loser's cleanup to delete the winner's directory");
        assertTrue(Files.exists(tableDir.resolve("table.meta")),
                "winner's metadata file must exist after concurrent registration");

        // The catalog must still have the table
        final var getMethod = catalogClass.getDeclaredMethod("get", String.class);
        getMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var optMetadata = (Optional<TableMetadata>) getMethod.invoke(catalog, "race-table");
        assertTrue(optMetadata.isPresent(),
                "table must be present in catalog after concurrent registration");

        final var closeMethod = catalogClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(catalog);
    }

    // Finding: F-R1.cb.2.9
    // Bug: LocalTable.insert() calls doc.getString(primaryKeyField) without checking the field
    // type is STRING first. For a non-string primary key (e.g., INT32), the error message
    // comes from JlsmDocument.getString() and says "has type INT32, not STRING" — which
    // does not mention the primary key constraint. The error should clearly state that
    // primary keys must be string-typed.
    // Correct behavior: insert() should throw IllegalArgumentException with a message mentioning
    // "primary key" and "string" when the schema's first field is non-string
    // Fix location: LocalTable.java:94-95 — add type check before getString call
    // Regression watch: Ensure string primary keys still work normally
    @Test
    void test_LocalTable_insert_nonStringPrimaryKey_throwsMeaningfulError(@TempDir Path tempDir)
            throws Exception {
        // Schema with INT32 as the primary key field (first field)
        final JlsmSchema schema = JlsmSchema.builder("int-pk-schema", 1)
                .field("id", FieldType.Primitive.INT32).field("name", FieldType.Primitive.STRING)
                .build();

        // Build a LocalEngine
        final Class<?> engineClass = Class.forName("jlsm.engine.internal.LocalEngine");
        final Class<?> builderClass = Class.forName("jlsm.engine.internal.LocalEngine$Builder");
        final Method builderMethod = engineClass.getDeclaredMethod("builder");
        builderMethod.setAccessible(true);
        final Object builder = builderMethod.invoke(null);

        final Method rootDirMethod = builderClass.getDeclaredMethod("rootDirectory", Path.class);
        rootDirMethod.setAccessible(true);
        rootDirMethod.invoke(builder, tempDir);

        final Method buildMethod = builderClass.getDeclaredMethod("build");
        buildMethod.setAccessible(true);
        final Engine engine = (Engine) buildMethod.invoke(builder);

        try {
            final Table handle = engine.createTable("int-pk-table", schema);

            // Create a document with an INT32 primary key value
            final JlsmDocument doc = JlsmDocument.of(schema, "id", 42, "name", "Alice");

            // insert() should throw IAE with a message about primary key requiring string type
            final var thrown = assertThrows(IllegalArgumentException.class,
                    () -> handle.insert(doc),
                    "insert() must throw IllegalArgumentException for non-string primary key");
            assertTrue(thrown.getMessage().toLowerCase().contains("primary key"),
                    "error message must mention 'primary key' — got: " + thrown.getMessage());
            assertTrue(thrown.getMessage().toLowerCase().contains("string"),
                    "error message must mention 'string' — got: " + thrown.getMessage());
        } finally {
            engine.close();
        }
    }
}

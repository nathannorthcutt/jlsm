package jlsm.engine;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-verify regression tests for F05 v2. Each test is annotated with the requirement it enforces.
 * All tests use the public {@link Engine#builder()} factory; no reflection into internal packages.
 */
class F05ContractTest {

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .field("value", FieldType.Primitive.STRING).build();
    }

    // @spec engine.in-process-database-engine.R1 — Engine.builder() must be reachable from the exported public API package
    // without reflection or deep-module tricks.
    @Test
    void engineBuilderIsAccessibleFromPublicApi(@TempDir Path tempDir) throws IOException {
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            assertNotNull(engine, "Engine.builder().build() must return a non-null Engine");
        }
    }

    // @spec engine.in-process-database-engine.R3 — builder must reject a relative root path with IllegalArgumentException
    @Test
    void builderRejectsRelativeRootDirectory() {
        final Path relative = Paths.get("relative-not-absolute");
        assertFalse(relative.isAbsolute(), "precondition: path under test must be relative");
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Engine.builder().rootDirectory(relative).build(),
                "relative root path must be rejected at build time");
        assertTrue(thrown.getMessage().toLowerCase().contains("absolute"),
                "exception message must reference 'absolute'; got: " + thrown.getMessage());
    }

    // @spec engine.in-process-database-engine.R19,R25 — getTable on a LOADING-state table must throw IOException
    @Test
    void getTableThrowsForLoadingState(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = testSchema();
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("loading_tbl", schema).close();
        }
        tamperState(tempDir.resolve("loading_tbl").resolve("table.meta"),
                TableMetadata.TableState.LOADING);
        try (final Engine engine2 = Engine.builder().rootDirectory(tempDir).build()) {
            assertThrows(IOException.class, () -> engine2.getTable("loading_tbl"),
                    "getTable on LOADING-state table must throw IOException");
        }
    }

    // @spec engine.in-process-database-engine.R19,R25 — getTable on an ERROR-state table must throw IOException
    @Test
    void getTableThrowsForErrorState(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = testSchema();
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("error_tbl", schema).close();
        }
        tamperState(tempDir.resolve("error_tbl").resolve("table.meta"),
                TableMetadata.TableState.ERROR);
        try (final Engine engine2 = Engine.builder().rootDirectory(tempDir).build()) {
            assertThrows(IOException.class, () -> engine2.getTable("error_tbl"),
                    "getTable on ERROR-state table must throw IOException");
        }
    }

    // @spec engine.in-process-database-engine.R20 — listTables must return only READY tables and must be a snapshot copy
    @Test
    void listTablesReturnsReadyOnlySnapshot(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = testSchema();
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("ready_tbl", schema).close();
            engine.createTable("error_tbl", schema).close();
        }
        tamperState(tempDir.resolve("error_tbl").resolve("table.meta"),
                TableMetadata.TableState.ERROR);
        try (final Engine engine2 = Engine.builder().rootDirectory(tempDir).build()) {
            final Collection<TableMetadata> listed = engine2.listTables();
            assertEquals(1, listed.size(), "listTables must return READY tables only");
            assertEquals("ready_tbl", listed.iterator().next().name());

            // Snapshot: creating another table after list must not mutate the returned collection
            engine2.createTable("added_after_list", schema).close();
            assertEquals(1, listed.size(),
                    "listTables must return an independent snapshot (not a live view)");
        }
    }

    // @spec engine.in-process-database-engine.R24,R26,R27,R30,R57 — drop persists DROPPED state; on restart getTable throws
    // IOException identifying the dropped state and the table is not listed
    @Test
    void dropPersistsDroppedStateAndIsNotServedAfterRestart(@TempDir Path tempDir)
            throws Exception {
        final JlsmSchema schema = testSchema();
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("gone_tbl", schema).close();
            engine.dropTable("gone_tbl");
        }
        // Tombstone must persist on disk so a future startup recognises the table as gone
        final Path meta = tempDir.resolve("gone_tbl").resolve("table.meta");
        assertTrue(Files.exists(meta),
                "DROPPED tombstone (table.meta) must remain on disk after drop");

        try (final Engine engine2 = Engine.builder().rootDirectory(tempDir).build()) {
            assertThrows(IOException.class, () -> engine2.getTable("gone_tbl"),
                    "getTable on tombstoned table after restart must throw IOException");
            assertTrue(engine2.listTables().isEmpty(),
                    "tombstoned tables must not appear in listTables after restart");
        }
    }

    // @spec engine.in-process-database-engine.R31 — a drop whose data-file deletion fails must still complete successfully;
    // the DROPPED tombstone must be persisted regardless of cleanup outcome. We inject deletion
    // failure by placing data files inside a read-only subdirectory (parent of table.meta stays
    // writable so the tombstone rewrite can succeed).
    @Test
    void dropSucceedsEvenIfDataFileDeletionFails(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = testSchema();
        final Path tableDir = tempDir.resolve("stubborn");
        final Path lockedSubdir = tableDir.resolve("locked");
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("stubborn", schema).close();

            Files.createDirectories(lockedSubdir);
            Files.writeString(lockedSubdir.resolve("stuck.bin"), "data");
            final boolean readOnly = lockedSubdir.toFile().setWritable(false);
            try {
                assertDoesNotThrow(() -> engine.dropTable("stubborn"),
                        "dropTable must not propagate data-file deletion failures");
            } finally {
                lockedSubdir.toFile().setWritable(true);
                if (!readOnly) {
                    return; // platform can't enforce the failure; test still asserts happy path
                }
            }
        }
        assertTrue(Files.exists(tableDir.resolve("table.meta")),
                "DROPPED tombstone must remain even when data-file deletion fails");
    }

    // @spec engine.in-process-database-engine.R54 — per-table metadata writes must be atomic via write-then-rename.
    // Verified by (a) confirming no temp files leak on the happy path; (b) the file is non-empty.
    @Test
    void metadataWriteIsAtomic(@TempDir Path tempDir) throws Exception {
        final JlsmSchema schema = testSchema();
        try (final Engine engine = Engine.builder().rootDirectory(tempDir).build()) {
            engine.createTable("atomic", schema).close();
        }
        final Path meta = tempDir.resolve("atomic").resolve("table.meta");
        assertTrue(Files.exists(meta), "metadata file must exist after createTable returns");
        assertTrue(Files.size(meta) > 0, "metadata file must be non-empty after atomic write");
        try (final var stream = Files.newDirectoryStream(tempDir.resolve("atomic"),
                "table.meta.tmp*")) {
            assertFalse(stream.iterator().hasNext(),
                    "no temp files should remain after atomic metadata write");
        }
    }

    /**
     * Direct binary tamper: rewrites the state ordinal in the trailing int of a table.meta file.
     * The metadata format ends with a 4-byte state ordinal; we overwrite those four bytes.
     */
    private static void tamperState(Path metaFile, TableMetadata.TableState newState)
            throws IOException {
        final byte[] bytes = Files.readAllBytes(metaFile);
        final int newOrdinal = newState.ordinal();
        bytes[bytes.length - 4] = (byte) (newOrdinal >>> 24);
        bytes[bytes.length - 3] = (byte) (newOrdinal >>> 16);
        bytes[bytes.length - 2] = (byte) (newOrdinal >>> 8);
        bytes[bytes.length - 1] = (byte) newOrdinal;
        Files.write(metaFile, bytes);
    }
}

package jlsm.engine.internal;

import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for resource lifecycle concerns in the engine module.
 */
class ResourceLifecycleAdversarialTest {

    // Finding: F-R1.resource_lifecycle.1.8
    // Bug: evictIfNeeded total-limit loop only evicts from the triggering table's sourceMap,
    // even when a different table holds the most handles. The wrong table gets punished.
    // Correct behavior: Total-limit eviction should evict from the table with the most handles
    // (the greediest table globally), not the table that triggered the eviction check.
    // Fix location: HandleTracker.evictIfNeeded() lines 216-227 — total limit loop
    // Regression watch: Per-table and per-source eviction should still target the triggering table
    // @spec F05.R81 — global-limit eviction targets the greediest table globally
    @Test
    void test_HandleTracker_evictIfNeeded_totalLimit_evictsFromWrongTable() {
        // Configure: maxTotalHandles=10, per-table=10, per-source=10
        // Per-table and per-source limits won't trigger; only total limit matters.
        final HandleTracker tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(10)
                .maxHandlesPerTable(10).maxTotalHandles(10).build();

        // Table B has 8 handles (the "greedy" table)
        for (int i = 0; i < 8; i++) {
            tracker.register("tableB", "sourceB");
        }

        // Table A has 2 handles — registering a 3rd will trigger evictIfNeeded("tableA")
        // Total before: 10. After registering tableA's 3rd handle: 11 > maxTotalHandles(10).
        tracker.register("tableA", "sourceA");
        tracker.register("tableA", "sourceA");

        // This registration pushes total to 11, triggering total-limit eviction
        tracker.register("tableA", "sourceA");

        // After eviction, total must be <= 10. The eviction should NOT have come from Table A
        // (which is the triggering table). Table B has 8 handles — it's the greediest globally.
        final int tableACount = tracker.handleCountForTable("tableA");
        final int tableBCount = tracker.handleCountForTable("tableB");
        final int total = tableACount + tableBCount;

        // Total must respect the limit
        assertTrue(total <= 10, "total handles (" + total + ") must be <= maxTotalHandles(10)");

        // Table A should NOT have been evicted — it only has 3 handles while Table B has 8.
        // The greedy-source-first strategy should target Table B.
        assertEquals(3, tableACount,
                "Table A (3 handles) should not be evicted when Table B (8 handles) is greedier — "
                        + "total-limit eviction targeted the wrong table");
        assertEquals(7, tableBCount, "Table B should have lost 1 handle to total-limit eviction");
    }

    // Finding: F-R1.resource_lifecycle.3.6
    // Bug: TableCatalog.register() leaves orphan directory on writeMetadata failure.
    // The catch block removes the map entry but does not delete the directory
    // created by Files.createDirectories().
    // Correct behavior: On writeMetadata failure, register() should clean up the
    // created directory before re-throwing the exception.
    // Fix location: TableCatalog.register() lines 144-152 — catch block
    // Regression watch: Directory cleanup must not throw and mask the original IOException
    @Test
    void test_TableCatalog_register_cleansUpDirectoryOnWriteMetadataFailure(@TempDir Path tempDir)
            throws IOException {
        final TableCatalog catalog = new TableCatalog(tempDir);
        catalog.open();

        final String tableName = "orphan_test";
        final JlsmSchema schema = JlsmSchema.builder("test_schema", 1).build();

        // Pre-create a directory at the metadata file path so writeMetadata's
        // Files.newOutputStream fails — a directory cannot be opened as an output stream.
        final Path tableDir = tempDir.resolve(tableName);
        Files.createDirectories(tableDir.resolve("table.meta"));

        // Act: register() should throw because writeMetadata fails
        assertThrows(IOException.class, () -> catalog.register(tableName, schema));

        // Assert: the table directory must NOT exist after the failed register —
        // the orphan directory should have been cleaned up.
        assertFalse(Files.exists(tableDir),
                "register() must clean up the table directory when writeMetadata fails — "
                        + "orphan directory left on disk");

        catalog.close();
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Bug: HandleTracker.release() removes a registration from tracking maps but does not
    // call registration.invalidate(), leaving the registration in a "valid" state.
    // Any code holding a reference can continue to pass isInvalidated() checks.
    // Correct behavior: After release(), the registration should be invalidated so that
    // isInvalidated() returns true.
    // Fix location: HandleTracker.release() — add registration.invalidate() call
    // Regression watch: Ensure release() remains idempotent for already-invalidated registrations
    // @spec F05.R82 — release immediately invalidates the registration
    @Test
    void test_HandleTracker_release_doesNotInvalidateRegistration() {
        final HandleTracker tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(10)
                .maxHandlesPerTable(100).maxTotalHandles(1000).build();

        final HandleRegistration registration = tracker.register("table1", "source1");

        // Precondition: registration is valid before release
        assertFalse(registration.isInvalidated(), "registration should be valid before release");

        // Act: release the registration
        tracker.release(registration);

        // Assert: registration must be invalidated after release
        assertTrue(registration.isInvalidated(),
                "registration must be invalidated after release — released handles "
                        + "should not pass validity checks");
    }
}

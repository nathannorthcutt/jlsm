package jlsm.engine.internal;

import jlsm.engine.AllocationTracking;
import jlsm.engine.HandleEvictedException;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for internal engine types.
 *
 * <p>
 * Targets findings F2 (HandleRegistration mutable array), F4 (checkValid hardcoded reason), F5
 * (HandleTracker.Builder assert-only), and F6 (LocalEngine.Builder assert-only).
 */
class EngineInternalAdversarialTest {

    // ---- F2: HandleRegistration.allocationSite() mutable array ----

    /** F2 — Constructor stores allocationSite by reference without cloning. */
    @Test
    void registrationConstructorShouldCloneAllocationSite() {
        final StackTraceElement[] site = Thread.currentThread().getStackTrace();
        final var original0 = site[0];
        final var reg = new HandleRegistration("t", "s", site);

        // Mutate the original array after construction
        site[0] = new StackTraceElement("Evil", "method", "Evil.java", 1);

        assertEquals(original0, reg.allocationSite()[0], "Constructor should clone allocationSite; "
                + "mutation of original should not affect registration");
    }

    /** F2 — Getter returns allocationSite by reference without cloning. */
    @Test
    void registrationGetterShouldReturnDefensiveCopy() {
        final StackTraceElement[] site = Thread.currentThread().getStackTrace();
        final var original0 = site[0];
        final var reg = new HandleRegistration("t", "s", site.clone());

        // Mutate the returned array
        final StackTraceElement[] returned = reg.allocationSite();
        returned[0] = new StackTraceElement("Evil", "method", "Evil.java", 1);

        assertEquals(original0, reg.allocationSite()[0], "Getter should return defensive copy; "
                + "mutation should not affect registration");
    }

    // ---- F4: CatalogTable.checkValid() hardcodes EVICTION reason ----

    /**
     * F4 — When handle is invalidated due to TABLE_DROPPED, the exception still reports EVICTION
     * instead of the actual reason.
     */
    @Test
    void checkValidShouldReportTableDroppedReason() {
        final var schema = JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .build();
        final var metadata = new TableMetadata("test_table", schema, Instant.now(),
                TableMetadata.TableState.READY);
        final var tracker = HandleTracker.builder().allocationTracking(AllocationTracking.OFF)
                .build();
        final var stub = new StubStringKeyedTable();

        final HandleRegistration reg = tracker.register("test_table", "test-source");
        final var table = new CatalogTable(stub, reg, tracker, metadata);

        // Invalidate via TABLE_DROPPED
        tracker.invalidateTable("test_table", HandleEvictedException.Reason.TABLE_DROPPED);

        // Bug: exception reason should be TABLE_DROPPED but is hardcoded EVICTION
        final var ex = assertThrows(HandleEvictedException.class, () -> table.get("k1"));
        assertEquals(HandleEvictedException.Reason.TABLE_DROPPED, ex.reason(),
                "Exception should carry TABLE_DROPPED, not hardcoded EVICTION");
    }

    /**
     * F4 — When handle is invalidated due to ENGINE_SHUTDOWN, the exception still reports EVICTION
     * instead of the actual reason.
     */
    @Test
    void checkValidShouldReportEngineShutdownReason() {
        final var schema = JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .build();
        final var metadata = new TableMetadata("test_table", schema, Instant.now(),
                TableMetadata.TableState.READY);
        final var tracker = HandleTracker.builder().allocationTracking(AllocationTracking.OFF)
                .build();
        final var stub = new StubStringKeyedTable();

        final HandleRegistration reg = tracker.register("test_table", "test-source");
        final var table = new CatalogTable(stub, reg, tracker, metadata);

        // Invalidate via ENGINE_SHUTDOWN
        tracker.invalidateAll(HandleEvictedException.Reason.ENGINE_SHUTDOWN);

        final var ex = assertThrows(HandleEvictedException.class, () -> table.get("k1"));
        assertEquals(HandleEvictedException.Reason.ENGINE_SHUTDOWN, ex.reason(),
                "Exception should carry ENGINE_SHUTDOWN, not hardcoded EVICTION");
    }

    // ---- F5: HandleTracker.Builder assert-only validation ----

    /** F5 — Builder should throw IAE for zero maxHandlesPerSourcePerTable. */
    // @spec engine.in-process-database-engine.R71 — handle-limit builder rejects non-positive with
    // IAE
    @Test
    void handleTrackerBuilderRejectsZeroMaxPerSourcePerTable() {
        assertThrows(IllegalArgumentException.class,
                () -> HandleTracker.builder().maxHandlesPerSourcePerTable(0),
                "Builder should throw IAE for zero, not AssertionError");
    }

    /** F5 — Builder should throw IAE for negative maxHandlesPerTable. */
    @Test
    void handleTrackerBuilderRejectsNegativeMaxPerTable() {
        assertThrows(IllegalArgumentException.class,
                () -> HandleTracker.builder().maxHandlesPerTable(-1),
                "Builder should throw IAE for negative, not AssertionError");
    }

    /** F5 — Builder should throw IAE for zero maxTotalHandles. */
    @Test
    void handleTrackerBuilderRejectsZeroMaxTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> HandleTracker.builder().maxTotalHandles(0),
                "Builder should throw IAE for zero, not AssertionError");
    }

    // ---- F6: LocalEngine.Builder assert-only validation ----

    /** F6 — Builder should throw IAE for zero maxHandlesPerSourcePerTable. */
    // @spec engine.in-process-database-engine.R71,R90 — LocalEngine.Builder rejects non-positive
    // handle limits with IAE
    @Test
    void localEngineBuilderRejectsZeroMaxPerSourcePerTable() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalEngine.builder().maxHandlesPerSourcePerTable(0),
                "Builder should throw IAE, not AssertionError");
    }

    /** F6 — Builder should throw IAE for negative maxHandlesPerTable. */
    @Test
    void localEngineBuilderRejectsNegativeMaxPerTable() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalEngine.builder().maxHandlesPerTable(-1),
                "Builder should throw IAE, not AssertionError");
    }

    /** F6 — Builder should throw IAE for zero maxTotalHandles. */
    @Test
    void localEngineBuilderRejectsZeroMaxTotal() {
        assertThrows(IllegalArgumentException.class, () -> LocalEngine.builder().maxTotalHandles(0),
                "Builder should throw IAE, not AssertionError");
    }

    /** F6 — Builder should throw IAE for zero memTableFlushThresholdBytes. */
    @Test
    void localEngineBuilderRejectsZeroFlushThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalEngine.builder().memTableFlushThresholdBytes(0),
                "Builder should throw IAE, not AssertionError");
    }

    /** F6 — Builder should throw IAE for negative memTableFlushThresholdBytes. */
    @Test
    void localEngineBuilderRejectsNegativeFlushThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalEngine.builder().memTableFlushThresholdBytes(-1),
                "Builder should throw IAE, not AssertionError");
    }

    // ---- Stub JlsmTable.StringKeyed ----

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
            return java.util.Collections.emptyIterator();
        }

        @Override
        public void close() throws IOException {
        }
    }
}

package jlsm.engine.internal;

import jlsm.engine.AllocationTracking;
import jlsm.engine.EngineMetrics;
import jlsm.engine.HandleEvictedException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandleTrackerTest {

    // ---- Lifecycle: register / release ----

    // @spec engine.in-process-database-engine.R40,R49 — register produces a tracked registration
    // with visible invalidation flag
    @Test
    void registerReturnsNonNullRegistration() {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            assertNotNull(reg);
            assertEquals("users", reg.tableName());
            assertEquals("src-1", reg.sourceId());
            assertFalse(reg.isInvalidated());
        } catch (IOException e) {
            fail(e);
        }
    }

    // @spec engine.in-process-database-engine.R77 — release decrements the handle count
    @Test
    void releaseDecrementsTotalHandles() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            assertEquals(1, tracker.snapshot().totalOpenHandles());

            tracker.release(reg);
            assertEquals(0, tracker.snapshot().totalOpenHandles());
        }
    }

    // @spec engine.in-process-database-engine.R50 — invalidate is idempotent, and release of an
    // invalid registration is a no-op
    @Test
    void releaseOfAlreadyInvalidatedRegistrationIsNoOp() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            reg.invalidate();
            // Should not throw
            tracker.release(reg);
            assertEquals(0, tracker.snapshot().totalOpenHandles());
        }
    }

    @Test
    void releaseOfAlreadyReleasedRegistrationIsNoOp() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            tracker.release(reg);
            // Double release should be safe
            tracker.release(reg);
            assertEquals(0, tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- Allocation tracking ----

    // @spec engine.in-process-database-engine.R43 — OFF mode captures no allocation site
    @Test
    void allocationTrackingOffSetsNullAllocationSite() throws IOException {
        try (var tracker = HandleTracker.builder().allocationTracking(AllocationTracking.OFF)
                .build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            assertNull(reg.allocationSite());
        }
    }

    // @spec engine.in-process-database-engine.R43 — CALLER_TAG mode currently captures no
    // allocation site (reserved)
    @Test
    void allocationTrackingCallerTagSetsNullAllocationSite() throws IOException {
        try (var tracker = HandleTracker.builder().allocationTracking(AllocationTracking.CALLER_TAG)
                .build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            assertNull(reg.allocationSite());
        }
    }

    // @spec engine.in-process-database-engine.R43 — FULL_STACK captures a full stack trace
    @Test
    void allocationTrackingFullStackCapturesStackTrace() throws IOException {
        try (var tracker = HandleTracker.builder().allocationTracking(AllocationTracking.FULL_STACK)
                .build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            assertNotNull(reg.allocationSite());
            assertTrue(reg.allocationSite().length > 0);
        }
    }

    // ---- Eviction: per-source-per-table limit ----

    // @spec engine.in-process-database-engine.R42,R44,R80 — per-source-per-table limit triggers
    // eviction
    @Test
    void evictIfNeededTriggersWhenPerSourcePerTableLimitExceeded() throws IOException {
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(2)
                .maxHandlesPerTable(100).maxTotalHandles(100).build()) {

            HandleRegistration r1 = tracker.register("users", "src-1");
            HandleRegistration r2 = tracker.register("users", "src-1");
            HandleRegistration r3 = tracker.register("users", "src-1");

            tracker.evictIfNeeded("users");

            // At least one registration from src-1 should be invalidated to get back under limit
            long invalidated = List.of(r1, r2, r3).stream()
                    .filter(HandleRegistration::isInvalidated).count();
            assertTrue(invalidated >= 1, "Expected at least 1 eviction, got " + invalidated);
            assertTrue(tracker.snapshot().totalOpenHandles() <= 2);
        }
    }

    // ---- Eviction: per-table limit ----

    // Updated by audit F-R1.cb.1.5: maxHandlesPerSourcePerTable > maxHandlesPerTable was a bug, now
    // correctly rejected by hierarchy validation
    // @spec engine.in-process-database-engine.R41,R45,R80 — per-table limit triggers eviction
    @Test
    void evictIfNeededTriggersWhenPerTableLimitExceeded() throws IOException {
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(3)
                .maxHandlesPerTable(3).maxTotalHandles(100).build()) {

            // Register from multiple sources to exceed per-table limit
            tracker.register("users", "src-1");
            tracker.register("users", "src-1");
            tracker.register("users", "src-2");
            tracker.register("users", "src-2");

            tracker.evictIfNeeded("users");

            assertTrue(tracker.snapshot().totalOpenHandles() <= 3,
                    "Expected at most 3 handles, got " + tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- Eviction: total limit ----

    // Updated by audit F-R1.cb.1.5: maxHandlesPerSourcePerTable > maxHandlesPerTable was a bug, now
    // correctly rejected by hierarchy validation
    // @spec engine.in-process-database-engine.R40,R46 — global handle-budget limit triggers
    // eviction
    @Test
    void evictIfNeededTriggersWhenTotalLimitExceeded() throws IOException {
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(3)
                .maxHandlesPerTable(3).maxTotalHandles(3).build()) {

            tracker.register("users", "src-1");
            tracker.register("users", "src-2");
            tracker.register("orders", "src-1");
            tracker.register("orders", "src-2");

            // Evict on any table should check total limit
            tracker.evictIfNeeded("users");

            assertTrue(tracker.snapshot().totalOpenHandles() <= 3,
                    "Expected at most 3 handles, got " + tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- Eviction: greedy-source-first ----

    // Updated by audit F-R1.cb.1.5: maxHandlesPerSourcePerTable > maxHandlesPerTable was a bug, now
    // correctly rejected by hierarchy validation
    @Test
    void greedySourceFirstEvictsSourceWithMostHandles() throws IOException {
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(4)
                .maxHandlesPerTable(4).maxTotalHandles(100).build()) {

            // src-greedy has 4 handles, src-modest has 1
            HandleRegistration g1 = tracker.register("users", "src-greedy");
            HandleRegistration g2 = tracker.register("users", "src-greedy");
            HandleRegistration g3 = tracker.register("users", "src-greedy");
            HandleRegistration g4 = tracker.register("users", "src-greedy");
            HandleRegistration m1 = tracker.register("users", "src-modest");

            tracker.evictIfNeeded("users");

            // The greedy source should have handles evicted, the modest source should be untouched
            assertFalse(m1.isInvalidated(), "Modest source should not be evicted");
            long greedyEvicted = List.of(g1, g2, g3, g4).stream()
                    .filter(HandleRegistration::isInvalidated).count();
            assertTrue(greedyEvicted >= 1, "Greedy source should have at least 1 eviction");
        }
    }

    // ---- Eviction: oldest first within a source ----

    // @spec engine.in-process-database-engine.R51 — oldest handle (insertion-order) evicted first
    // within a source
    @Test
    void oldestHandlesEvictedFirstWithinSource() throws IOException {
        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(2)
                .maxHandlesPerTable(100).maxTotalHandles(100).build()) {

            HandleRegistration oldest = tracker.register("users", "src-1");
            HandleRegistration middle = tracker.register("users", "src-1");
            HandleRegistration newest = tracker.register("users", "src-1");

            tracker.evictIfNeeded("users");

            // Oldest should be evicted first
            assertTrue(oldest.isInvalidated(), "Oldest handle should be evicted first");
            assertFalse(newest.isInvalidated(), "Newest handle should survive");
        }
    }

    // ---- invalidateAll ----

    @Test
    void invalidateAllMarksAllRegistrationsAsInvalidated() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration r1 = tracker.register("users", "src-1");
            HandleRegistration r2 = tracker.register("orders", "src-2");

            tracker.invalidateAll(HandleEvictedException.Reason.ENGINE_SHUTDOWN);

            assertTrue(r1.isInvalidated());
            assertTrue(r2.isInvalidated());
            assertEquals(0, tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- invalidateTable ----

    @Test
    void invalidateTableMarksOnlyThatTablesRegistrations() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration r1 = tracker.register("users", "src-1");
            HandleRegistration r2 = tracker.register("orders", "src-1");

            tracker.invalidateTable("users", HandleEvictedException.Reason.TABLE_DROPPED);

            assertTrue(r1.isInvalidated());
            assertFalse(r2.isInvalidated());
            assertEquals(1, tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- snapshot ----

    // @spec engine.in-process-database-engine.R62,R63,R64 — metrics expose live counts across
    // sources/tables safely
    @Test
    void snapshotReturnsCorrectCounts() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            tracker.register("users", "src-1");
            tracker.register("users", "src-1");
            tracker.register("users", "src-2");
            tracker.register("orders", "src-1");

            EngineMetrics metrics = tracker.snapshot();

            assertEquals(2, metrics.tableCount());
            assertEquals(4, metrics.totalOpenHandles());
            assertEquals(3, metrics.handlesPerTable().get("users"));
            assertEquals(1, metrics.handlesPerTable().get("orders"));
            assertEquals(2, metrics.handlesPerSourcePerTable().get("users").get("src-1"));
            assertEquals(1, metrics.handlesPerSourcePerTable().get("users").get("src-2"));
            assertEquals(1, metrics.handlesPerSourcePerTable().get("orders").get("src-1"));
        }
    }

    @Test
    void snapshotAfterReleaseReflectsDecreasedCounts() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            HandleRegistration reg = tracker.register("users", "src-1");
            tracker.register("users", "src-2");

            tracker.release(reg);

            EngineMetrics metrics = tracker.snapshot();
            assertEquals(1, metrics.tableCount());
            assertEquals(1, metrics.totalOpenHandles());
            assertEquals(1, metrics.handlesPerTable().get("users"));
        }
    }

    @Test
    void snapshotEmptyTrackerReturnsZeros() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            EngineMetrics metrics = tracker.snapshot();
            assertEquals(0, metrics.tableCount());
            assertEquals(0, metrics.totalOpenHandles());
            assertTrue(metrics.handlesPerTable().isEmpty());
            assertTrue(metrics.handlesPerSourcePerTable().isEmpty());
        }
    }

    // ---- close ----

    @Test
    void closeCallsInvalidateAllWithEngineShutdown() throws IOException {
        HandleRegistration reg;
        try (var tracker = HandleTracker.builder().build()) {
            reg = tracker.register("users", "src-1");
        }
        assertTrue(reg.isInvalidated());
    }

    // ---- Null argument validation ----

    @Test
    void registerNullTableNameThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.register(null, "src-1"));
        }
    }

    @Test
    void registerNullSourceIdThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.register("users", null));
        }
    }

    @Test
    void releaseNullThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.release(null));
        }
    }

    @Test
    void evictIfNeededNullThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.evictIfNeeded(null));
        }
    }

    @Test
    void invalidateAllNullReasonThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.invalidateAll(null));
        }
    }

    @Test
    void invalidateTableNullTableNameThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.invalidateTable(null,
                    HandleEvictedException.Reason.TABLE_DROPPED));
        }
    }

    @Test
    void invalidateTableNullReasonThrows() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertThrows(NullPointerException.class, () -> tracker.invalidateTable("users", null));
        }
    }

    // ---- Builder defaults ----

    @Test
    void builderDefaultsProduceValidTracker() throws IOException {
        try (var tracker = HandleTracker.builder().build()) {
            assertNotNull(tracker);
            assertEquals(0, tracker.snapshot().totalOpenHandles());
        }
    }

    // ---- Thread-safety: concurrent register/release ----

    // @spec engine.in-process-database-engine.R65,R69 — concurrent register/release must not
    // corrupt state
    @Test
    void concurrentRegisterAndReleaseDoNotCorruptState() throws Exception {
        final int threadCount = 8;
        final int opsPerThread = 100;

        try (var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(10000)
                .maxHandlesPerTable(10000).maxTotalHandles(10000).build()) {

            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final CopyOnWriteArrayList<HandleRegistration> allRegs = new CopyOnWriteArrayList<>();
            final AtomicInteger errors = new AtomicInteger(0);

            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final String sourceId = "thread-" + t;
                Thread thread = Thread.ofPlatform().start(() -> {
                    try {
                        barrier.await();
                        List<HandleRegistration> localRegs = new ArrayList<>();
                        for (int i = 0; i < opsPerThread; i++) {
                            HandleRegistration reg = tracker.register("users", sourceId);
                            localRegs.add(reg);
                        }
                        allRegs.addAll(localRegs);
                        // Release half
                        for (int i = 0; i < opsPerThread / 2; i++) {
                            tracker.release(localRegs.get(i));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
                threads.add(thread);
            }

            for (Thread thread : threads) {
                thread.join(10_000);
            }

            assertEquals(0, errors.get(), "No errors should occur during concurrent ops");

            EngineMetrics metrics = tracker.snapshot();
            int expectedRemaining = threadCount * (opsPerThread - opsPerThread / 2);
            assertEquals(expectedRemaining, metrics.totalOpenHandles(),
                    "Total handles should match remaining after partial release");
        }
    }
}

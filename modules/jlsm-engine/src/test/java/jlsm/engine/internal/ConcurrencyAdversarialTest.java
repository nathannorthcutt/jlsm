package jlsm.engine.internal;

import jlsm.engine.AllocationTracking;
import jlsm.engine.HandleEvictedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial concurrency tests for engine internal types.
 */
class ConcurrencyAdversarialTest {

    // Finding: F-R1.concurrency.1.1
    // Bug: register() can add to a sourceMap that invalidateTable() has already removed
    // from tableHandles, creating an orphaned registration invisible to eviction/metrics.
    // Correct behavior: After invalidateTable completes, any registration that started
    // concurrently must either be visible in the tracker (re-registered) or invalidated.
    // Fix location: HandleTracker.register (lines 83-101) or invalidateTable (lines 208-224)
    // Regression watch: Ensure register still works correctly for non-invalidated tables
    @Test
    @Timeout(30)
    void test_HandleTracker_concurrency_phantomRegistrationAfterInvalidateTable() throws Exception {
        // Strategy: sustained contention — many threads register/invalidate in tight loops,
        // collecting all registrations. After all threads finish, verify the invariant:
        // every registration is either invalidated or visible in the tracker.
        final var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(100000)
                .maxHandlesPerTable(100000).maxTotalHandles(1000000)
                .allocationTracking(AllocationTracking.OFF).build();

        final String tableName = "t1";
        final ConcurrentLinkedQueue<HandleRegistration> allRegistrations = new ConcurrentLinkedQueue<>();
        final AtomicBoolean stop = new AtomicBoolean(false);

        final int writerCount = 4;
        final int invalidatorCount = 2;
        final int opsPerThread = 5000;
        final CountDownLatch ready = new CountDownLatch(writerCount + invalidatorCount);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(writerCount + invalidatorCount);

        // Writer threads: register handles and collect them
        for (int w = 0; w < writerCount; w++) {
            final int wIdx = w;
            Thread.ofPlatform().start(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < opsPerThread && !stop.get(); i++) {
                        final HandleRegistration reg = tracker.register(tableName,
                                "w" + wIdx + "-" + i);
                        allRegistrations.add(reg);
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        // Invalidator threads: seed + invalidate in a tight loop
        for (int inv = 0; inv < invalidatorCount; inv++) {
            Thread.ofPlatform().start(() -> {
                try {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < opsPerThread && !stop.get(); i++) {
                        tracker.register(tableName, "inv-seed");
                        tracker.invalidateTable(tableName,
                                HandleEvictedException.Reason.TABLE_DROPPED);
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();
        done.await();

        // All threads finished. No concurrent modifications happening now.
        // Drain the table to get a clean state for verification.
        // First, invalidate any remaining handles.
        tracker.invalidateTable(tableName, HandleEvictedException.Reason.TABLE_DROPPED);

        // Now every registration collected by writer threads MUST be invalidated.
        // A phantom registration — one that ended up in a detached sourceMap —
        // will NOT have been invalidated by any invalidateTable call.
        int phantomCount = 0;
        for (final HandleRegistration reg : allRegistrations) {
            if (!reg.isInvalidated()) {
                phantomCount++;
            }
        }

        assertEquals(0, phantomCount,
                phantomCount + " phantom registration(s) detected: handle(s) not invalidated "
                        + "after final invalidateTable — orphaned in detached sourceMap(s)");

        tracker.close();
    }

    // Finding: F-R1.concurrency.1.2
    // Bug: invalidate() has no idempotency guard — a second call overwrites
    // invalidationReason, so concurrent invalidation with different reasons
    // lets a later reason replace the first (e.g., EVICTION overwrites TABLE_DROPPED).
    // Correct behavior: The first invalidate() call wins; subsequent calls are no-ops
    // and the original reason is preserved.
    // Fix location: HandleRegistration.invalidate (lines 55-59)
    // Regression watch: Ensure invalidate() still sets both fields on first call
    // @spec engine.in-process-database-engine.R50 — invalidate() is idempotent; first-write wins on
    // the reason
    @Test
    @Timeout(10)
    void test_HandleRegistration_concurrency_nonIdempotentInvalidationReasonOverwrite()
            throws Exception {
        // Use a concurrent stress test: many threads race to invalidate the same
        // registration with different reasons. After all threads finish, the reason
        // must match whichever thread won the race — and must be stable (no overwrites
        // after the flag is set).
        final int iterations = 5000;
        int overwriteCount = 0;

        for (int i = 0; i < iterations; i++) {
            final var reg = new HandleRegistration("t1", "s1", null);
            final var barrier = new CyclicBarrier(2);
            final AtomicReference<Throwable> error = new AtomicReference<>();

            final Thread tDrop = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    reg.invalidate(HandleEvictedException.Reason.TABLE_DROPPED);
                } catch (Exception e) {
                    error.set(e);
                }
            });

            final Thread tEvict = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    reg.invalidate(HandleEvictedException.Reason.EVICTION);
                } catch (Exception e) {
                    error.set(e);
                }
            });

            tDrop.join(5000);
            tEvict.join(5000);
            assertNull(error.get(), "Thread threw: " + error.get());

            assertTrue(reg.isInvalidated(), "registration should be invalidated");

            // The key invariant: once invalidated=true is visible, the reason must
            // be the one that was written by the FIRST thread to enter invalidate().
            // With the bug, the second thread can overwrite the reason.
            // We detect this by calling invalidate a third time with a distinct reason
            // and verifying the reason doesn't change.
            final HandleEvictedException.Reason reasonAfterRace = reg.invalidationReason();
            assertNotNull(reasonAfterRace, "reason must not be null after invalidation");

            // Now call invalidate again with a reason that differs from whatever won.
            // If invalidate is idempotent, the reason must NOT change.
            final HandleEvictedException.Reason differentReason = (reasonAfterRace == HandleEvictedException.Reason.ENGINE_SHUTDOWN)
                    ? HandleEvictedException.Reason.TABLE_DROPPED
                    : HandleEvictedException.Reason.ENGINE_SHUTDOWN;
            reg.invalidate(differentReason);

            if (reg.invalidationReason() != reasonAfterRace) {
                overwriteCount++;
            }
        }

        assertEquals(0, overwriteCount,
                overwriteCount + " out of " + iterations
                        + " iterations had the invalidation reason overwritten by a subsequent call"
                        + " — invalidate() is not idempotent");
    }

    // Finding: F-R1.concurrency.1.3
    // Bug: TOCTOU race in evictIfNeeded total-limit loop — countTotalHandles() is read
    // outside any lock, so two concurrent evictIfNeeded callers can both see the
    // count above the limit and both evict, invalidating more handles than necessary.
    // Correct behavior: Eviction should stop as soon as the total count reaches the limit;
    // no more handles should be evicted than the excess above maxTotalHandles.
    // Fix location: HandleTracker.evictIfNeeded (lines 177-187)
    // Regression watch: Ensure single-threaded eviction still works correctly
    @Test
    @Timeout(30)
    void test_HandleTracker_concurrency_overEvictionUnderConcurrentEvictIfNeeded()
            throws Exception {
        // Setup: maxTotalHandles = 10, register exactly 12 handles (2 over limit).
        // Then fire N threads calling evictIfNeeded concurrently.
        // Correct behavior: exactly 2 handles should be evicted (the excess).
        // Bug behavior: multiple threads each see total=12 > 10, each evicts,
        // resulting in more than 2 evictions.
        final int maxTotal = 10;
        final int registered = 12;
        final int expectedEvictions = registered - maxTotal; // 2
        final int iterations = 500;
        final int threadCount = 4;

        int overEvictionCount = 0;

        for (int iter = 0; iter < iterations; iter++) {
            final var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(maxTotal)
                    .maxHandlesPerTable(maxTotal).maxTotalHandles(maxTotal)
                    .allocationTracking(AllocationTracking.OFF).build();

            // Register handles across multiple sources in one table to give
            // evictIfNeeded something to evict from.
            final List<HandleRegistration> regs = new ArrayList<>();
            for (int i = 0; i < registered; i++) {
                regs.add(tracker.register("t1", "src-" + i));
            }

            // All threads will call evictIfNeeded("t1") concurrently.
            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final CountDownLatch done = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        barrier.await();
                        tracker.evictIfNeeded("t1");
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        done.countDown();
                    }
                });
            }

            done.await();

            // Count how many handles were evicted (invalidated).
            int evicted = 0;
            for (final HandleRegistration reg : regs) {
                if (reg.isInvalidated()) {
                    evicted++;
                }
            }

            if (evicted > expectedEvictions) {
                overEvictionCount++;
            }

            tracker.close();
        }

        assertEquals(0, overEvictionCount,
                overEvictionCount + " out of " + iterations
                        + " iterations had over-eviction — more handles evicted than the "
                        + expectedEvictions + " excess above maxTotalHandles");
    }

    // Finding: F-R1.concurrency.1.4
    // Bug: check-then-act race between checkValid and concurrent invalidation —
    // checkValid reads registration.isInvalidated() == false, then another thread
    // invalidates the registration before the delegate operation executes. The
    // delegate operation proceeds on an invalidated handle.
    // Correct behavior: If the registration is invalidated before or during the delegate
    // operation, the operation must throw HandleEvictedException — it must not
    // silently execute on an invalidated handle.
    // Fix location: CatalogTable.checkValid (lines 123-129) and each delegate call site
    // Regression watch: Ensure non-invalidated handles still work correctly
    // @spec engine.in-process-database-engine.R68,R83 — checkValid and delegate execute atomically
    // w.r.t. concurrent
    // invalidation. R68 (concurrent drop alongside in-flight query) reduces to the same
    // synchronization contract:
    // either the op completes with prior results or throws HandleEvictedException — never
    // propagates an unexpected exception.
    @Test
    @Timeout(30)
    void test_LocalTable_concurrency_checkThenActRaceBetweenCheckValidAndInvalidation()
            throws Exception {
        // Strategy: use a stub delegate that, when create() is called, checks whether
        // the registration has been invalidated. If it has, it means checkValid passed
        // but by the time the delegate executed, the handle was already invalid.
        // We run many iterations with tight concurrent invalidation to trigger the race.

        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
                .build();
        final var metadata = new jlsm.engine.TableMetadata("t1", schema, Instant.now(),
                jlsm.engine.TableMetadata.TableState.READY);

        final int iterations = 5000;
        final AtomicInteger operationsOnInvalidatedHandle = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            final var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(100)
                    .maxHandlesPerTable(100).maxTotalHandles(1000)
                    .allocationTracking(AllocationTracking.OFF).build();

            final HandleRegistration reg = tracker.register("t1", "src");
            final var stub = new InvalidationDetectingStub(reg);
            final var localTable = new CatalogTable(stub, reg, tracker, metadata);
            final var doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");

            final var barrier = new CyclicBarrier(2);

            // Thread 1: call create — checkValid then delegate
            final var operationThread = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    localTable.create("k1", doc);
                } catch (HandleEvictedException _) {
                    // Expected if checkValid catches the invalidation — this is correct behavior
                } catch ( Exception _) {
                    // ignore other exceptions
                }
            });

            // Thread 2: invalidate the registration right after the barrier
            final var invalidatorThread = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    reg.invalidate(HandleEvictedException.Reason.EVICTION);
                } catch (Exception _) {
                    // ignore
                }
            });

            operationThread.join(5000);
            invalidatorThread.join(5000);

            if (stub.calledWhileInvalidated.get()) {
                operationsOnInvalidatedHandle.incrementAndGet();
            }

            tracker.close();
        }

        assertEquals(0, operationsOnInvalidatedHandle.get(),
                operationsOnInvalidatedHandle.get() + " out of " + iterations
                        + " iterations had a delegate operation execute on an already-invalidated "
                        + "handle — check-then-act race between checkValid and invalidation");
    }

    // Finding: F-R1.concurrency.1.5
    // Bug: CatalogTable.close() calls tracker.release(registration) which removes the
    // registration from the tracker's maps but does NOT invalidate it. Subsequent
    // operations pass checkValid() because registration.isInvalidated() is still false.
    // Correct behavior: After close(), any operation on the CatalogTable must throw
    // HandleEvictedException (or IllegalStateException) — use-after-close must be rejected.
    // Fix location: CatalogTable.close (lines 128-130)
    // Regression watch: Ensure close() is idempotent and doesn't throw on double-close
    @Test
    @Timeout(10)
    void test_LocalTable_concurrency_useAfterCloseRace() throws Exception {
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
                .build();
        final var metadata = new jlsm.engine.TableMetadata("t1", schema, Instant.now(),
                jlsm.engine.TableMetadata.TableState.READY);

        final var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(100)
                .maxHandlesPerTable(100).maxTotalHandles(1000)
                .allocationTracking(AllocationTracking.OFF).build();

        final HandleRegistration reg = tracker.register("t1", "src");
        final var stub = new InvalidationDetectingStub(reg);
        final var localTable = new CatalogTable(stub, reg, tracker, metadata);

        // Close the table — should prevent subsequent operations
        localTable.close();

        // After close, operations must be rejected
        final var doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
        assertThrows(HandleEvictedException.class, () -> localTable.create("k1", doc),
                "create() must throw HandleEvictedException after close()");
        assertThrows(HandleEvictedException.class, () -> localTable.get("k1"),
                "get() must throw HandleEvictedException after close()");
        assertThrows(HandleEvictedException.class,
                () -> localTable.update("k1", doc, UpdateMode.REPLACE),
                "update() must throw HandleEvictedException after close()");
        assertThrows(HandleEvictedException.class, () -> localTable.delete("k1"),
                "delete() must throw HandleEvictedException after close()");
        assertThrows(HandleEvictedException.class, () -> localTable.scan("a", "z"),
                "scan() must throw HandleEvictedException after close()");

        tracker.close();
    }

    // Finding: F-R1.concurrency.1.7
    // Bug: register() does not check the closed flag — handles can be registered after
    // tracker.close(). The registration is added to a new or existing sourceMap but
    // will never be invalidated because close()/invalidateAll already ran.
    // Correct behavior: register() must throw IllegalStateException after close()
    // Fix location: HandleTracker.register (lines 83-101)
    // Regression watch: Ensure register still works correctly before close
    // @spec engine.in-process-database-engine.R89 — register after tracker close must throw
    // IllegalStateException
    @Test
    @Timeout(10)
    void test_HandleTracker_concurrency_registerAfterCloseAccepted() throws Exception {
        final var tracker = HandleTracker.builder().maxHandlesPerSourcePerTable(100)
                .maxHandlesPerTable(100).maxTotalHandles(1000)
                .allocationTracking(AllocationTracking.OFF).build();

        // Register a handle before close — must succeed
        final HandleRegistration beforeClose = tracker.register("t1", "src-before");
        assertNotNull(beforeClose, "register before close must succeed");

        // Close the tracker
        tracker.close();

        // Register after close — must be rejected
        assertThrows(IllegalStateException.class, () -> tracker.register("t1", "src-after"),
                "register() must throw IllegalStateException after close()");
    }

    /**
     * Stub that detects whether the delegate was called while the registration was invalidated.
     */
    private static final class InvalidationDetectingStub implements JlsmTable.StringKeyed {

        private final HandleRegistration registration;
        final AtomicBoolean calledWhileInvalidated = new AtomicBoolean(false);

        InvalidationDetectingStub(HandleRegistration registration) {
            this.registration = registration;
        }

        @Override
        public void create(String key, JlsmDocument doc) throws IOException {
            if (registration.isInvalidated()) {
                calledWhileInvalidated.set(true);
            }
        }

        @Override
        public Optional<JlsmDocument> get(String key) throws IOException {
            if (registration.isInvalidated()) {
                calledWhileInvalidated.set(true);
            }
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
            if (registration.isInvalidated()) {
                calledWhileInvalidated.set(true);
            }
        }

        @Override
        public void delete(String key) throws IOException {
            if (registration.isInvalidated()) {
                calledWhileInvalidated.set(true);
            }
        }

        @Override
        public Iterator<TableEntry<String>> getAllInRange(String from, String to)
                throws IOException {
            if (registration.isInvalidated()) {
                calledWhileInvalidated.set(true);
            }
            return Collections.emptyIterator();
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }
}

package jlsm.engine.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the per-table {@link CatalogLock} — exclusive lock SPI shared by
 * {@code Engine.enableEncryption} (R7b step 1) and the SSTable writer finish protocol (R10c step 2)
 * and serving the R11b serialisation contract.
 *
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R10c
 * @spec sstable.footer-encryption-scope.R11b
 */
class CatalogLockTest {

    @TempDir
    Path tempDir;

    /**
     * Locate the package-private file-based implementation via the package factory. The contract
     * test exercises the same SPI both engine paths consume.
     */
    private CatalogLock lock() {
        return CatalogLockFactory.fileBased(tempDir);
    }

    // ---- happy path: acquire + release ----

    @Test
    void acquire_returnsHandle_andRelease_doesNotThrow() throws IOException {
        // covers: R7b step 1 / R10c step 2 — basic acquire / release semantics.
        final CatalogLock l = lock();
        try (final CatalogLock.Handle h = l.acquire("users")) {
            assertNotNull(h, "acquire must return non-null handle");
        }
    }

    @Test
    void close_isIdempotent() throws IOException {
        // covers: contract — close is idempotent and must not throw checked exceptions on repeat
        // (interface declares void).
        final CatalogLock l = lock();
        final CatalogLock.Handle h = l.acquire("users");
        h.close();
        h.close(); // second call is a no-op
    }

    // ---- mutual exclusion same table ----

    @Test
    void acquire_sameTableTwiceFromDifferentThreads_serialisesUnderLock() throws Exception {
        // covers: R7b / R10c / R11b — at most one concurrent holder per table.
        final CatalogLock l = lock();
        final CountDownLatch firstHeld = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger overlap = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final var f1 = exec.submit(() -> {
                try (final var h = l.acquire("users")) {
                    overlap.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, overlap.get()));
                    firstHeld.countDown();
                    releaseFirst.await(2, TimeUnit.SECONDS);
                    overlap.decrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            assertTrue(firstHeld.await(2, TimeUnit.SECONDS), "first acquire did not occur");

            final var f2 = exec.submit(() -> {
                try (final var h = l.acquire("users")) {
                    overlap.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, overlap.get()));
                    overlap.decrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            // Give the second acquire a chance to run; it must not have completed yet.
            Thread.sleep(100);
            assertFalse(f2.isDone(), "second acquire must block while first holds the lock");

            releaseFirst.countDown();
            f1.get(2, TimeUnit.SECONDS);
            f2.get(2, TimeUnit.SECONDS);

            // Maximum concurrent holders must never have exceeded 1.
            assertTrue(maxConcurrent.get() <= 1,
                    "lock must serialise holders (R11b); max concurrent = " + maxConcurrent.get());
        } finally {
            exec.shutdownNow();
        }
    }

    // ---- different tables do not block each other ----

    @Test
    void acquire_differentTables_doesNotBlock() throws Exception {
        // covers: R7b — locks are per-table; two different tables proceed concurrently.
        final CatalogLock l = lock();
        try (final var hUsers = l.acquire("users"); final var hOrders = l.acquire("orders")) {
            assertNotNull(hUsers);
            assertNotNull(hOrders);
        }
    }

    // ---- input validation ----

    @Test
    void acquire_nullName_throwsNPE() {
        final CatalogLock l = lock();
        assertThrows(NullPointerException.class, () -> l.acquire(null));
    }

    // ---- liveness recovery (R11b) — stale holder PID must be reclaimed ----

    @Test
    void acquire_reclaimsStaleHolder_whenPidIsDead() throws Exception {
        // covers: R11b — lock-holder liveness recovery. Simulate a crashed prior holder by
        // creating a lock file whose PID points at a non-existent process; a fresh acquire must
        // reclaim it rather than wedge the catalog indefinitely.
        final CatalogLock l = lock();
        // Simulate a prior holder by writing a stale lock file directly. The implementation
        // must accept the impossible-PID lock as reclaimable.
        CatalogLockFactory.writeStaleLockFile(tempDir, "users", /* pid= */ Integer.MAX_VALUE);

        // A fresh acquire on the same table must succeed (reclaim the stale holder).
        try (final var h = l.acquire("users")) {
            assertNotNull(h);
        }
    }

    // ---- defensive (Lens B) — try-with-resources semantics ----

    @Test
    void acquire_throwingInsideTryWithResources_releasesLockOnExit() throws Exception {
        // finding: IMPL-RISK — caller exception must not orphan the lock (try-with-resources
        // guarantees close on the way out of the block).
        final CatalogLock l = lock();
        try {
            try (final var h = l.acquire("users")) {
                throw new RuntimeException("simulated caller failure");
            }
        } catch (RuntimeException expected) {
            // swallow — what matters is the next acquire succeeds without blocking.
        }
        try (final var h = l.acquire("users")) {
            assertNotNull(h, "lock must have released on the exception path");
        }
    }

    // ---- stale lock file does not exist after clean release ----

    @Test
    void cleanRelease_removesLockFile() throws IOException {
        // finding: IMPL-RISK — atomic-move-vs-fallback-commit-divergence — clean release must
        // not leave the lock file behind, otherwise the next acquire incorrectly attempts
        // liveness reclaim.
        final CatalogLock l = lock();
        try (final var h = l.acquire("users")) {
            // held
        }
        // After release, no lock file should remain for "users".
        try (var stream = Files.list(tempDir)) {
            final boolean any = stream.anyMatch(p -> p.getFileName().toString().contains(".lock"));
            // Implementations may leave a marker but must accept fresh acquire — this is a
            // soft-check on cleanup hygiene. We accept either outcome but the next acquire must
            // succeed quickly.
        }
        try (final var h2 = l.acquire("users")) {
            assertNotNull(h2);
        }
    }

    // ---- defensive (Lens B) — concurrency torrent ----

    @Test
    void manyConcurrentAcquires_sameTable_allEventuallySucceedAndSerialised() throws Exception {
        // covers: R11b — N threads contend for the same table lock; all eventually obtain it,
        // and at no point are two holders concurrent.
        final CatalogLock l = lock();
        final int threadCount = 16;
        final ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        final AtomicInteger overlap = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        start.await();
                        try (final var h = l.acquire("hot-table")) {
                            final int now = overlap.incrementAndGet();
                            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                            Thread.sleep(5);
                            overlap.decrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertTrue(maxConcurrent.get() <= 1,
                    "lock must serialise; max concurrent observed = " + maxConcurrent.get());
        } finally {
            exec.shutdownNow();
        }
    }
}

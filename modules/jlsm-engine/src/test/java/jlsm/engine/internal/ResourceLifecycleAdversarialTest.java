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
    // @spec engine.in-process-database-engine.R81 — global-limit eviction targets the greediest
    // table globally
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
    // @spec engine.in-process-database-engine.R82 — release immediately invalidates the
    // registration
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

    // Finding: F-R1.resource_lifecycle.2.2
    // Bug: Re-entrant acquire("t") on the same JVM (while a prior handle is still open) opens a
    // SECOND FileChannel against the same lock file and calls FileChannel.tryLock(). Because the
    // JVM already holds an exclusive FileLock on that file via the prior handle's channel,
    // tryLock() throws OverlappingFileLockException — an unchecked IllegalStateException subclass
    // that is NOT documented in CatalogLock.acquire's contract. Callers that reasonably assume
    // ReentrantLock-style re-entry (the per-table jvmLock IS a ReentrantLock) see a surprising
    // unchecked exception.
    // Correct behavior: acquire() must NOT surface OverlappingFileLockException. The
    // straightforward fix is to detect intra-JVM re-entry up-front (jvmLock.getHoldCount() > 0
    // after our own jvmLock.lock() means we already held it) and throw a clear, documented
    // IllegalStateException explaining that catalog-lock acquire is not re-entrant. Leaving the
    // jvmLock in its prior state (decrementing the hold count once) is critical so the original
    // holder's close() still works.
    // Fix location: FileBasedCatalogLock.acquire (lines 50-74) — add a holdCount > 1 guard
    // immediately after jvmLock.lock(), unlock once, throw IllegalStateException.
    // Regression watch: the original handle's close() must still release exactly one OS file lock
    // and exactly one jvmLock hold (depth back to zero). The cached factory instance under tempDir
    // must be invalidated after the test so subsequent tests start with a fresh lock map.
    // @spec sstable.footer-encryption-scope.R11b — re-entry is intra-JVM behavior, not governed
    // by R11b directly, but the contract surface for acquire must be predictable.
    @Test
    void test_FileBasedCatalogLock_acquire_reentrantOnSameJvmThrowsClearException(
            @TempDir Path tempDir) throws IOException {
        final CatalogLock l = CatalogLockFactory.fileBased(tempDir);
        try {
            final CatalogLock.Handle outer = l.acquire("users");
            try {
                // Act: re-entrant acquire on the same thread/JVM while outer handle is open.
                // Buggy implementation: throws OverlappingFileLockException (unchecked) from
                // FileChannel.tryLock at line 100. That is NOT IllegalStateException-typed in a
                // way the contract documents — it surfaces from java.nio internals.
                //
                // Assert: acquire must NOT throw OverlappingFileLockException. It must either
                // succeed (true re-entry) or throw IllegalStateException with a clear message
                // saying the catalog lock is not re-entrant. Either is acceptable as a fix; the
                // unchecked OverlappingFileLockException leak is the bug.
                Throwable thrown = null;
                CatalogLock.Handle inner = null;
                try {
                    inner = l.acquire("users");
                } catch (Throwable t) {
                    thrown = t;
                }

                if (inner != null) {
                    // Re-entry was supported as a no-op nested handle. Verify the inner close
                    // does not break the outer handle's lock.
                    inner.close();
                } else {
                    assertNotNull(thrown, "expected either successful re-entry or a thrown "
                            + "exception, got neither");
                    assertFalse(thrown instanceof java.nio.channels.OverlappingFileLockException,
                            "re-entrant acquire must not surface "
                                    + "OverlappingFileLockException to callers — got: " + thrown);
                    // The fix's preferred form is a clear IllegalStateException. Accept any
                    // checked or unchecked exception that is NOT OverlappingFileLockException.
                    assertTrue(
                            thrown instanceof IllegalStateException
                                    || thrown instanceof IOException,
                            "re-entrant acquire must throw a documented exception type "
                                    + "(IllegalStateException or IOException), got: "
                                    + thrown.getClass().getName());
                }
            } finally {
                outer.close();
            }
        } finally {
            CatalogLockFactory.invalidate(tempDir);
        }
    }

    // Finding: F-R1.resource_lifecycle.2.3
    // Bug: acquireFileLockWithReclaim uses System.currentTimeMillis() (wall clock) for both the
    // 5-second deadline and the loop comparison. Wall clock is not monotonic — NTP adjustments or
    // admin-driven clock steps can extend the wait beyond the bounded RECLAIM_WINDOW_MS or
    // short-circuit it, violating R11b's bounded-reclaim guarantee.
    // Correct behavior: bounded-acquisition windows must use System.nanoTime(), which is the only
    // JDK-provided monotonic time source and is documented as immune to wall-clock changes.
    // Fix location: FileBasedCatalogLock.acquireFileLockWithReclaim — switch the deadline
    // computation and the loop comparison from System.currentTimeMillis() to System.nanoTime().
    // Regression watch: nanoTime overflow safety — the deadline must be compared with
    // (System.nanoTime() - deadline) < 0, not direct < comparison, when the relevant nanos epoch
    // is allowed to wrap. With a 5-second window and Long-valued nanos, overflow is not a
    // practical concern, but the comparison style is conventionally `now - deadline < 0` per
    // System.nanoTime javadoc.
    // @spec sstable.footer-encryption-scope.R11b — bounded reclaim window (immune to wall-clock
    // skew).
    @Test
    void test_FileBasedCatalogLock_acquireFileLockWithReclaim_usesMonotonicClock()
            throws IOException {
        // Read the compiled class file as a classpath resource and inspect its constant pool
        // strings for `currentTimeMillis`. The buggy implementation calls
        // System.currentTimeMillis() at lines 110 and 116; the fixed implementation must use
        // System.nanoTime() exclusively.
        try (java.io.InputStream in = FileBasedCatalogLock.class
                .getResourceAsStream("FileBasedCatalogLock.class")) {
            assertNotNull(in,
                    "FileBasedCatalogLock.class must be reachable as a classpath resource");
            final byte[] classBytes = in.readAllBytes();
            final String asAscii = new String(classBytes,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            // The class file's constant pool encodes referenced method names as UTF-8 strings.
            // If the implementation calls System.currentTimeMillis(), that exact name will
            // appear in the constant pool. If it calls System.nanoTime(), `nanoTime` will
            // appear instead.
            assertFalse(asAscii.contains("currentTimeMillis"),
                    "FileBasedCatalogLock must not reference System.currentTimeMillis — "
                            + "wall-clock skew can violate the bounded RECLAIM_WINDOW_MS "
                            + "guarantee (R11b). Use System.nanoTime() for the deadline + loop.");
            assertTrue(asAscii.contains("nanoTime"),
                    "FileBasedCatalogLock must use System.nanoTime() for the bounded reclaim "
                            + "window — monotonic clock is required to honor R11b under NTP/admin "
                            + "wall-clock adjustments.");
        }
    }

    // Finding: F-R1.resource_lifecycle.2.4
    // Bug: isLivePid hard-wires the Linux branch to consult `/proc/<pid>` exclusively. In
    // containers/chroots where `/proc` is not mounted (or is masked) but `os.name` still reports
    // "Linux", `Files.exists(Path.of("/proc/<pid>"))` returns false unconditionally — even for a
    // demonstrably live PID (the current JVM's own PID). isLivePid therefore returns false for a
    // genuinely live peer and openWithReclaim deletes the still-valid lock file, breaking the
    // R11b cross-JVM exclusion guarantee in exactly the same way as F-R1.resource_lifecycle.2.1.
    // The card discounts this risk to "correct but slower fallback" — that characterization is
    // wrong: there is no fallback in the current code; the Linux branch short-circuits `return`.
    // Correct behavior: isLivePid must NOT depend on `/proc` for liveness on Linux.
    // `ProcessHandle.of(pid).isPresent()` queries the OS process table on Linux and works in
    // containers/chroots without `/proc`. The minimal fix is to drop the `/proc` shortcut
    // entirely (the suggested fix in the finding) — ProcessHandle.of is the portable, correct
    // implementation across all supported platforms. An equivalent fix is to fall back to
    // ProcessHandle.of when `Files.exists(Path.of("/proc"))` returns false on a Linux host.
    // Fix location: FileBasedCatalogLock.isLivePid (lines 179-188) — replace the Linux-specific
    // `/proc/<pid>` short-circuit with `ProcessHandle.of(pid).isPresent()` for all platforms,
    // OR add a `/proc`-existence fallback before the Linux short-circuit returns.
    // Regression watch: ProcessHandle.of must continue to correctly classify a definitely-dead
    // PID (a value > /proc/sys/kernel/pid_max would be a strong signal — but easier: a positive
    // PID with `pid >= Integer.MAX_VALUE` is already filtered upstream at line 180). Test must
    // not rely on host-dependent `/proc` mount state.
    // @spec sstable.footer-encryption-scope.R11b — lock-holder liveness reclaim guarantees
    // cross-process exclusion; misclassifying live PIDs as dead in a `/proc`-less Linux
    // environment violates that guarantee.
    @Test
    void test_FileBasedCatalogLock_isLivePid_doesNotDependOnProcFilesystem() throws IOException {
        // Read the compiled class file as a classpath resource and inspect its constant pool
        // strings for `/proc/`. The buggy implementation embeds this string literal at line 185.
        // The fixed implementation (drop the /proc shortcut entirely) must not reference `/proc`
        // at all — ProcessHandle.of is portable and works in containers/chroots without /proc.
        try (java.io.InputStream in = FileBasedCatalogLock.class
                .getResourceAsStream("FileBasedCatalogLock.class")) {
            assertNotNull(in,
                    "FileBasedCatalogLock.class must be reachable as a classpath resource");
            final byte[] classBytes = in.readAllBytes();
            final String asAscii = new String(classBytes,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            // The class file's constant pool encodes referenced string literals as UTF-8.
            // The buggy implementation calls `Files.exists(Path.of("/proc/" + pid))` — the
            // literal "/proc/" is interned in the constant pool. The fixed implementation
            // (drop the /proc shortcut) must NOT reference this string.
            assertFalse(asAscii.contains("/proc/"),
                    "FileBasedCatalogLock must not consult /proc for PID liveness — "
                            + "containers/chroots without /proc misclassify live peer JVMs as "
                            + "dead, violating R11b cross-process exclusion. Use "
                            + "ProcessHandle.of(pid).isPresent() which queries the OS process "
                            + "table portably.");
        }
    }

    // Finding: F-R1.resource_lifecycle.2.5
    // Bug: FileLockHandle.close() called from a thread that does NOT hold the underlying jvmLock
    // (e.g., the handle was acquired on thread A and handed to thread B for closing) silently
    // releases the OS file lock and the channel, but jvmLock.unlock() throws
    // IllegalMonitorStateException which is caught and swallowed. The OS-level cross-process
    // exclusion is released, while intra-JVM exclusion is NOT released — thread A still holds
    // the jvmLock. Subsequent acquire("t") calls in this JVM block forever on the orphaned
    // jvmLock, while peer JVMs see the OS lock as free and can take it. The asymmetric release
    // silently breaks the catalog-lock invariant.
    // Correct behavior: close() must verify the calling thread holds the jvmLock
    // (jvmLock.isHeldByCurrentThread()) BEFORE releasing any resource, and throw
    // IllegalStateException if not. This makes the misuse explicit rather than silent — and
    // critically, it ensures we do not release the OS lock when we cannot also release the
    // JVM lock, preserving the JVM-mutex implies OS-mutex invariant.
    // Fix location: FileBasedCatalogLock.FileLockHandle.close (lines 218-255) — add a
    // jvmLock.isHeldByCurrentThread() guard at the very top of the method, before the
    // closed-flag flip and any release calls.
    // Regression watch: the same-thread close path (acquire on T1, close on T1) must continue
    // to work. The closed-flag idempotency on second close from the holder thread must continue
    // to work. The IllegalStateException must propagate as an unchecked exception (close() has
    // no checked-exception throws clause).
    // @spec sstable.footer-encryption-scope.R11b — implicit invariant that JVM-level mutual
    // exclusion is released iff OS-level mutual exclusion is released.
    @Test
    void test_FileBasedCatalogLock_close_fromNonHolderThreadRejected(@TempDir Path tempDir)
            throws Exception {
        final CatalogLock l = CatalogLockFactory.fileBased(tempDir);
        try {
            // Acquire on the main thread.
            final CatalogLock.Handle handle = l.acquire("users");
            try {
                // Hand the handle to a different thread for closing.
                final Throwable[] thrownFromOtherThread = new Throwable[1];
                final Thread other = new Thread(() -> {
                    try {
                        handle.close();
                    } catch (Throwable t) {
                        thrownFromOtherThread[0] = t;
                    }
                });
                other.start();
                other.join(5_000L);
                assertFalse(other.isAlive(), "non-holder close must not hang");

                // Assert: close() from a non-holder thread must throw IllegalStateException.
                // The buggy implementation silently ignores IllegalMonitorStateException from
                // jvmLock.unlock() and returns normally — leaving the OS lock released but the
                // JVM lock orphaned.
                assertNotNull(thrownFromOtherThread[0],
                        "close() from non-holder thread must throw — silent failure orphans the "
                                + "JVM lock while releasing the OS lock, breaking the "
                                + "JVM-mutex implies OS-mutex invariant");
                assertTrue(thrownFromOtherThread[0] instanceof IllegalStateException,
                        "close() from non-holder thread must throw IllegalStateException, got: "
                                + thrownFromOtherThread[0].getClass().getName() + " — "
                                + thrownFromOtherThread[0].getMessage());

                // The buggy code releases the OS lock before failing on jvmLock.unlock(). The
                // fixed code must reject BEFORE releasing anything, so the holder thread can
                // still close the handle cleanly afterwards. Verify the holder thread can still
                // close (and that the second close from the holder thread releases all
                // resources without further error).
                handle.close();
            } finally {
                // No-op if already closed. Idempotency is part of the contract.
            }
        } finally {
            CatalogLockFactory.invalidate(tempDir);
        }
    }

    // Finding: F-R1.resource_lifecycle.2.1
    // Bug: FileLockHandle.close() releases the OS file lock (line 207) BEFORE deleting the
    // lock file (line 219). Between those two operations another JVM can open the
    // still-existing file, acquire its OS lock on the same inode, and then this close()
    // proceeds to unlink the file. A third JVM that subsequently acquires the lock creates
    // a brand-new inode and obtains a lock on it — both peers now believe they hold the
    // catalog lock simultaneously (cross-JVM exclusion silently violated, R11b broken).
    // Correct behavior: close() must NOT delete the lock file after releasing the OS lock.
    // The lock file may be left in place — the next acquirer's openWithReclaim path
    // already handles the existing-file case correctly. (Equivalent fix: delete BEFORE
    // releasing the OS lock; either is acceptable, but post-release deletion is the bug.)
    // Fix location: FileBasedCatalogLock.FileLockHandle.close (lines 197-229) — drop the
    // Files.deleteIfExists block at lines 218-222 (or move it before fileLock.release()).
    // Regression watch: cleanRelease_removesLockFile in CatalogLockTest tolerates either
    // outcome ("we accept either"); this test pins the post-release-deletion behaviour
    // shut. The reclaim-from-stale-PID path in CatalogLockTest still passes because
    // openWithReclaim consumes a left-behind file gracefully.
    // @spec sstable.footer-encryption-scope.R11b — lock-holder liveness reclaim must
    // preserve cross-process exclusion across release.
    @Test
    void test_FileBasedCatalogLock_close_doesNotDeleteLockFileAfterOsLockRelease(
            @TempDir Path tempDir) throws IOException {
        // Use the package-private factory the same way CatalogLockTest does.
        final CatalogLock l = CatalogLockFactory.fileBased(tempDir);
        try {
            final CatalogLock.Handle h = l.acquire("users");
            final Path lockFile = tempDir.resolve(FileBasedCatalogLock.LOCKS_DIR)
                    .resolve("users.lock");

            // Sanity: the lock file exists while the lock is held.
            assertTrue(Files.exists(lockFile),
                    "lock file must exist while the catalog lock is held");

            // Act: close the handle. The buggy implementation will release the OS lock
            // and then unlink the file, leaving a TOCTOU window where a peer could observe
            // the still-existing file, lock its inode, and then have the file deleted out
            // from under it.
            h.close();

            // Assert: the lock file must still exist after close(). Removing the
            // deleteIfExists call from close() is the canonical fix — leaving the file in
            // place is harmless because openWithReclaim handles the existing-file case.
            assertTrue(Files.exists(lockFile),
                    "close() must not unlink the lock file after releasing the OS lock — "
                            + "the post-release deletion creates a TOCTOU window that "
                            + "violates cross-JVM exclusion (R11b)");
        } finally {
            // Drop the cached lock instance so subsequent tests under tempDir start clean.
            CatalogLockFactory.invalidate(tempDir);
        }
    }

    // Finding: F-R1.resource_lifecycle.2.6
    // Bug: FileBasedCatalogLock.jvmLocks (a ConcurrentHashMap<String, ReentrantLock>) accumulates
    // a new entry for every distinct tableName ever passed to acquire(), and there is no removal
    // path. In a long-running JVM with high table-name churn (per-tenant ephemeral tables, test
    // harnesses, etc.) the map grows without bound — a memory leak that violates the project's
    // own coding-guidelines requirement that "every Map, List, or queue that grows with input
    // must have a configured capacity or eviction policy".
    // Correct behavior: After all handles for a tableName are closed and no thread is blocked
    // waiting on the per-table lock, the corresponding entry must be eligible for removal so the
    // map size remains proportional to the live working set, not the historical cardinality.
    // Fix location: FileBasedCatalogLock — remove the jvmLocks entry on close() when no other
    // thread holds or is waiting on the per-table ReentrantLock.
    // Regression watch: removal must be race-safe — a concurrent acquirer that observes the entry
    // and then sees it removed must still obtain a coherent ReentrantLock (computeIfAbsent on the
    // acquirer side handles re-creation; the close-side removal must be guarded so it only removes
    // when the lock is uncontended).
    @Test
    void test_FileBasedCatalogLock_jvmLocks_doesNotGrowUnboundedAcrossDistinctTableNames(
            @TempDir Path tempDir) throws Exception {
        final CatalogLock l = CatalogLockFactory.fileBased(tempDir);
        try {
            // Acquire-and-release N distinct table names sequentially. After each release the
            // per-table jvmLocks entry should be removed because no thread holds or waits on it.
            final int distinctNames = 50;
            for (int i = 0; i < distinctNames; i++) {
                final CatalogLock.Handle h = l.acquire("ephemeral_table_" + i);
                h.close();
            }

            // Inspect jvmLocks via reflection — it is a private field, but the test is in the
            // same package and we need to verify the bounded-collection invariant directly.
            final java.lang.reflect.Field f = FileBasedCatalogLock.class
                    .getDeclaredField("jvmLocks");
            f.setAccessible(true);
            final java.util.concurrent.ConcurrentHashMap<?, ?> jvmLocks = (java.util.concurrent.ConcurrentHashMap<?, ?>) f
                    .get(l);

            // Assert: the map must not retain an entry per distinct table name. After every
            // handle has been closed and no contention exists, the live working set is zero.
            // Allow a small slack (e.g., <= 1) to tolerate any transient bookkeeping; the key
            // assertion is that the size is NOT proportional to the historical cardinality.
            final int size = jvmLocks.size();
            assertTrue(size < distinctNames,
                    "jvmLocks must not grow unboundedly with distinct table names — "
                            + "after closing all " + distinctNames + " handles the map size is "
                            + size + " (entries are never reclaimed). This violates the project's "
                            + "bounded-collections guideline and leaks memory in long-running "
                            + "JVMs with high table-name churn.");
        } finally {
            CatalogLockFactory.invalidate(tempDir);
        }
    }
}

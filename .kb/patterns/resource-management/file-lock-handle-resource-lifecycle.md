---
title: "File-Lock Handle — Composite OS-Lock-plus-JVM-Lock Resource Lifecycle"
aliases:
  - "FileLock + ReentrantLock composite handle"
  - "process-and-thread mutex"
  - "lockfile lifecycle contract"
  - "per-name JVM lock map"
type: adversarial-finding
topic: "patterns"
category: "resource-management"
tags:
  - "FileLock"
  - "ReentrantLock"
  - "lockfile"
  - "TOCTOU"
  - "re-entrancy"
  - "holder-thread"
  - "refcount"
  - "bounded-map"
  - "two-layer-lock"
research_status: "active"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FileBasedCatalogLock.java"
related:
  - "patterns/resource-management/non-idempotent-close.md"
  - "patterns/resource-management/multi-resource-close-ordering.md"
  - "patterns/resource-management/unbounded-collection-growth.md"
  - "patterns/resource-management/iterator-without-close-holds-coordination.md"
  - "patterns/concurrency/check-then-act-across-paired-acquire-release.md"
  - "patterns/concurrency/non-atomic-lifecycle-flags.md"
  - "systems/database-engines/handle-lifecycle-patterns.md"
decision_refs: []
source_audit: "implement-encryption-lifecycle--wd-02"
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html"
    title: "java.nio.channels.FileLock — semantics and JVM-internal vs OS-level scope"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html"
    title: "java.util.concurrent.locks.ReentrantLock — re-entrancy and holder-thread semantics"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://cwe.mitre.org/data/definitions/367.html"
    title: "CWE-367 — Time-of-check Time-of-use (TOCTOU) Race Condition"
    accessed: "2026-04-25"
    type: "docs"
  - title: "F-R1.resource_lifecycle.2.1 — FileLockHandle.close TOCTOU on lock-file deletion"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.resource_lifecycle.2.2 — FileBasedCatalogLock.acquire re-entrant detection"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.resource_lifecycle.2.5 — FileLockHandle.close non-holder thread guard"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.resource_lifecycle.2.6 — FileBasedCatalogLock bounded jvmLocks map via refcounted entries"
    accessed: "2026-04-25"
    type: "audit-finding"
---

# File-Lock Handle — Composite OS-Lock-plus-JVM-Lock Resource Lifecycle

## summary

An OS-level file lock (`java.nio.channels.FileLock`) combined with an
in-process JVM-level lock (`ReentrantLock`) carries a non-trivial composite
resource lifecycle. Treating the OS lock and the JVM lock as independent
concerns — rather than as a single composite handle whose contract spans
both — produces a cluster of latent bugs that all share the same root
cause. The composite handle needs a unified contract covering: close
ordering (lock-file deletion vs OS-lock release), re-entrancy (JVM lock
permits same-thread re-entry but OS file lock does not), holder-thread
invariant (only the acquiring thread may release), and bounded-map cleanup
(per-name JVM-lock maps must reference-count entries to avoid permanent
leaks for distinct names).

## problem

A typical implementation looks like:

```java
public final class FileBasedCatalogLock {
    private final ConcurrentHashMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    public FileLockHandle acquire(String name) throws IOException {
        ReentrantLock lock = jvmLocks.computeIfAbsent(name, n -> new ReentrantLock());
        lock.lock();
        Path file = lockfilePath(name);
        FileChannel ch = FileChannel.open(file, CREATE, WRITE);
        FileLock os = ch.lock();
        return new FileLockHandle(file, ch, os, lock);
    }

    static final class FileLockHandle implements AutoCloseable {
        @Override public void close() throws IOException {
            os.release();
            ch.close();
            Files.deleteIfExists(file);   // delete AFTER release
            lock.unlock();
        }
    }
}
```

This implementation has four latent bugs, each surfacing only under a
specific stress:

1. **Close-ordering TOCTOU on lock-file deletion.** `os.release()` runs
   first, then `Files.deleteIfExists(file)`. A peer JVM blocked in
   `ch.lock()` may acquire the OS lock the moment `os.release()` returns,
   then the original holder's `Files.deleteIfExists` deletes the file out
   from under the peer. The peer holds an OS-level lock on a deleted
   file — its file descriptor still works, but a *third* JVM that arrives
   next will create a new file under the same path and acquire its own
   non-conflicting OS lock.
2. **Re-entrancy mismatch.** A same-thread re-entrant `acquire(name)` call
   reaches `lock.lock()` (which permits re-entry — the JVM lock is
   reentrant) and then proceeds to `ch.lock()`. The second OS-level
   `FileLock` attempt on the same channel from the same JVM is permitted
   on most OSes (Linux returns the same lock, Windows is documented to
   deadlock), but a `FileLock` reference is overlapping — and on close,
   the second handle's `os.release()` releases the OS lock that the first
   handle still depends on. The JVM lock is reentrant; the OS lock is
   not. The composite must short-circuit re-entry via the JVM lock before
   any second OS-level lock attempt.
3. **Holder-thread invariant violated by close.** `close()` is invoked
   from a thread that did not call `acquire`. The OS-level
   `FileLock.release` succeeds (the OS does not enforce a holder-thread
   identity), but the JVM `ReentrantLock.unlock` throws
   `IllegalMonitorStateException` because the calling thread is not the
   owner. The OS lock has been released; the JVM lock remains held; no
   subsequent `close()` will recover. Subsequent acquires on the same
   name from any thread block forever.
4. **Unbounded `jvmLocks` map growth.** `computeIfAbsent` populates the
   map lazily but never removes entries. For a workload that acquires and
   releases locks for distinct table names — a typical
   per-table-checkpoint pattern — the map grows linearly with the
   workload's name cardinality, never shrinking. After a long uptime the
   map dominates working-set memory.

These bugs cluster on the same construct because they are all consequences
of the same misconception: the OS lock and the JVM lock are *two* locks,
each with its own correctness story. The construct's actual contract is
that they are *one* composite — and a single coherent contract covers all
four.

## symptoms

- **(1) close-ordering TOCTOU.** Two-process integration test where P2 is
  blocked in `ch.lock()` while P1 holds the lock. P1 closes; P2 unblocks
  and acquires. Assert that immediately after P2 acquires, `lockfilePath`
  exists. Naive impl fails: P1's `deleteIfExists` runs after P1 released,
  meaning the file gets deleted while P2 holds the OS lock on its old
  inode.
- **(2) re-entrant mismatch.** Same thread calls `acquire("alpha")` twice.
  Assert: the second acquire succeeds (JVM lock re-entry) and does *not*
  attempt a second OS-level `ch.lock()`. Naive impl deadlocks on Windows
  or releases too eagerly on Linux.
- **(3) holder-thread.** Thread A acquires; thread B closes the handle.
  Assert: the `close` rejects with a clear exception ("only the acquiring
  thread may release"). Naive impl partially releases OS state and
  corrupts in-process state.
- **(4) bounded-map.** Loop: acquire+release for 1000 distinct table
  names. Assert `jvmLocks.size() == 0` after the loop. Naive impl shows
  `size() == 1000`.

## root-cause

The shared misconception: the OS file lock and the JVM `ReentrantLock`
serve different scopes (cross-process vs in-process), so they need
independent management. Under that view, four implementation choices look
locally correct: release-then-delete (intuitive cleanup order), let the JVM
`ReentrantLock` handle re-entry on its own, let any thread call close
because both locks are independently releasable, and use
`computeIfAbsent` because that is the canonical lazy-init.

The composite view: one `FileLockHandle` is a single resource whose
contract has four obligations. Any thread other than the acquirer cannot
release. Any second acquisition by the same thread short-circuits in the
JVM layer. The lock-file's lifecycle is part of the OS-lock's contract
(delete must happen *before* release, never after). The map of JVM locks
is part of the composite's storage and needs the same lifecycle as the
locks it stores.

## fix

A single composite contract:

```java
public final class FileBasedCatalogLock {

    record JvmLockEntry(ReentrantLock lock, AtomicInteger refCount) {}
    private final ConcurrentHashMap<String, JvmLockEntry> jvmLocks = new ConcurrentHashMap<>();

    public FileLockHandle acquire(String name) throws IOException {
        // ── (4) refcounted entry — never leak distinct names ────────────────
        JvmLockEntry entry = jvmLocks.compute(name, (n, existing) -> {
            JvmLockEntry e = (existing == null) ? new JvmLockEntry(new ReentrantLock(), new AtomicInteger(0)) : existing;
            e.refCount.incrementAndGet();
            return e;
        });
        try {
            // ── (2) JVM-lock first; reentrant counter detects re-entry ─────
            entry.lock.lock();
            if (entry.lock.getHoldCount() > 1) {
                // Same thread, second-or-deeper acquire — short-circuit
                return new ReentrantHandle(name, entry);
            }
            // First-time acquire on this thread: take OS-level lock
            Path file = lockfilePath(name);
            FileChannel ch = FileChannel.open(file, CREATE, WRITE);
            FileLock os;
            try {
                os = ch.lock();
            } catch (IOException e) {
                ch.close();
                throw e;
            }
            Thread holder = Thread.currentThread();
            return new FileLockHandle(name, file, ch, os, entry, holder);
        } catch (Throwable t) {
            // Release JVM lock and decrement refcount on failed acquire
            if (entry.lock.isHeldByCurrentThread()) entry.lock.unlock();
            decrementAndMaybeRemove(name, entry);
            throw t;
        }
    }

    private void decrementAndMaybeRemove(String name, JvmLockEntry entry) {
        // Atomic remove-when-zero so a racing acquire cannot observe a stale entry
        jvmLocks.compute(name, (n, e) -> {
            if (e != entry) return e;             // someone re-created it; keep theirs
            int after = entry.refCount.decrementAndGet();
            return after == 0 ? null : entry;     // remove iff we drained the entry
        });
    }

    final class FileLockHandle implements AutoCloseable {
        // ...
        @Override public void close() throws IOException {
            // ── (3) holder-thread invariant — only the acquirer releases ────
            if (Thread.currentThread() != holder) {
                throw new IllegalStateException("close() must be called by the acquiring thread");
            }
            // ── (1) close ordering — delete BEFORE release ─────────────────
            // While we still hold the OS lock, no peer can rebind file→inode
            try {
                Files.deleteIfExists(file);   // delete first — peer cannot replace yet
                os.release();                 // release second — peer can now acquire
            } finally {
                try { ch.close(); } finally {
                    entry.lock.unlock();
                    decrementAndMaybeRemove(name, entry);
                }
            }
        }
    }
}
```

Rules for the fix:

1. **Delete before release.** While the holder still holds the OS lock,
   no peer can have an open lock on the inode. Deleting the lock-file at
   that moment is safe — it removes the directory entry without
   disturbing any open file descriptor (POSIX). Then release. A peer who
   was blocked in `ch.lock()` on the deleted file will see its open
   succeed against the inode, but the next peer to arrive will create a
   fresh lock-file under the same path. (On Windows, the delete may fail
   while the file is open; in that case, accept the file remaining and
   release first — but document the platform-specific weakening.)
2. **Re-entrancy via JVM-layer counter.** `ReentrantLock.getHoldCount()`
   reports `> 1` for same-thread re-entry. Short-circuit: return a
   handle that releases the JVM lock on `close()` but does not touch the
   OS lock (which is still held by the outer acquire). The OS file lock
   is acquired exactly once per (process, thread, name).
3. **Holder-thread invariant.** Capture
   `Thread.currentThread()` at acquire time; reject `close` calls from
   any other thread with a clear exception. The OS lock release is
   skipped; the JVM lock remains held; the caller learns of the misuse
   immediately rather than discovering corrupted state later.
4. **Refcounted entries with atomic removal.** `compute` (not
   `computeIfAbsent` + later `remove`) gives atomic
   increment-or-create-and-decrement-or-remove. Without atomicity, a
   pattern like `if (refCount.get() == 0) jvmLocks.remove(name)` races
   with a concurrent acquire that is incrementing the counter, producing
   either a leaked entry or a wrongly removed entry mid-acquire.

## verification

Four adversarial tests, each one specifically targeting one of the
sub-bugs:

1. **Close ordering** — two-process test (or two FileChannel instances
   from one process — POSIX permits this on the same inode for testing):
   P2 blocks in `ch.lock()` while P1 holds. P1 closes. Within a short
   window of P2 unblocking, assert `Files.exists(lockfilePath)`.
2. **Re-entrant** — same thread calls `acquire("a")` twice; assert the
   second `acquire` does not increment a "ch.lock invocations" counter
   exposed for testing; the OS-level lock count must remain 1.
3. **Holder-thread** — thread A acquires; thread B calls
   `handle.close()`; assert `IllegalStateException` and the OS lock is
   still held (a third thread that acquires sees blocking behavior).
4. **Bounded-map** — acquire+release loop over 10k distinct names;
   assert `jvmLocks.size() == 0` at the end. Run a parallel variant —
   acquire and release on different threads simultaneously across
   distinct names — and assert the final size is 0.

## tradeoffs

**Strengths.** A single coherent contract eliminates four latent bug
classes simultaneously. Composite handle reads as one resource at the
call site, which matches operator intuition. Refcounted map keeps memory
bounded for any workload with eventually-reused or eventually-released
names.

**Weaknesses.** Refcount race window: between `compute(decrement)` and the
removal, a racing acquirer may see a `null` entry and create a fresh
JvmLockEntry with `refCount == 1` — the previous entry's lock object
goes out of scope. This is correct (the new lock is on a clean object)
but means a handle held by the original acquire still references an entry
with `refCount == 0` that has been removed; the holder-thread check still
catches misuse, but the entry no longer participates in the map. Document
this. Re-entrancy adds a code path to test (single-acquire vs reentrant);
the alternative — forbidding re-entry entirely with an
`IllegalStateException` — is simpler but pushes the constraint onto the
caller.

## when-to-apply

Any handle that combines a cross-process OS-level coordination primitive
(file lock, named pipe, advisory directory lock) with an in-process JVM
coordination primitive (ReentrantLock, semaphore, counter). Specifically:

- Per-table catalog locks (named files in a shared directory).
- Database engine startup-mutex patterns where one JVM must claim
  ownership of a data directory.
- Per-tenant or per-namespace locks that must coordinate within and
  across JVMs.

**When not to apply.** Single-process locks (a `ReentrantLock` alone is
sufficient — adding a file is overhead). Handles where the OS-level
primitive already enforces holder-thread semantics (rare; most do not).

## reference-implementation

`modules/jlsm-table/src/main/java/jlsm/table/internal/FileBasedCatalogLock.java`
— composite-handle implementation following the four rules above.

Audit findings that surfaced the pattern (4 findings on the same
construct, each on a distinct sub-bug):

- `F-R1.resource_lifecycle.2.1` — close-ordering TOCTOU on lock-file
  deletion.
- `F-R1.resource_lifecycle.2.2` — re-entrant detection (JVM lock vs OS
  lock).
- `F-R1.resource_lifecycle.2.5` — non-holder thread close guard.
- `F-R1.resource_lifecycle.2.6` — bounded `jvmLocks` map via refcounted
  entries.

WD-02 ciphertext-format audit, 2026-04-25.

## sources

1. [java.nio.channels.FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html) — JVM-wide vs OS-wide semantics; the documented note that OS file locks "are JVM-wide" is the structural reason re-entrancy needs the JVM-layer counter.
2. [java.util.concurrent.locks.ReentrantLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html) — `getHoldCount` and `isHeldByCurrentThread` document the holder-thread and re-entrancy primitives used in the fix.
3. [CWE-367 — TOCTOU](https://cwe.mitre.org/data/definitions/367.html) — the close-ordering window is a TOCTOU.

---
*Researched: 2026-04-25 | Next review: 2026-07-25*

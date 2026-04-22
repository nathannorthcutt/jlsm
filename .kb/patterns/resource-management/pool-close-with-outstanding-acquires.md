---
title: "Pool close() With Outstanding Acquires"
aliases: ["close while acquired", "live-acquire invalidation", "pool quiesce missing", "silent segment invalidation"]
topic: "patterns"
category: "resource-management"
tags: ["adversarial-finding", "resource-management", "pool", "lifecycle", "acquire-release", "off-heap", "Panama"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java"
related:
  - "non-idempotent-close"
  - "partial-init-no-rollback"
  - "../concurrency/non-atomic-lifecycle-flags"
decision_refs: []
sources:
  - "audit run-001 implement-sstable-enhancements--wd-02"
---

# Pool close() With Outstanding Acquires

## Summary

A resource pool with an explicit `acquire()` / `release()` protocol allows
`close()` to tear down the backing resource while acquired handles are
still live. The teardown silently invalidates the segments those handles
reference; the failure surfaces from unrelated code paths that subsequently
touch the segment — deep inside the Panama runtime (`IllegalStateException`
from an already-closed `MemorySegment`), far from the `close()` call that
caused it.

This is distinct from `non-idempotent-close`: the bug is not double-close,
it is close-while-live-acquires. `close()` must be a **refuseable**
operation — the pool must detect outstanding acquires and fail fast with a
diagnostic error, leaving the backing resource intact so the caller can
release and retry.

## Problem

```java
public final class ArenaBufferPool implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final LinkedBlockingQueue<MemorySegment> queue;
    // ... acquire()/release() use the queue for liveness tracking ...

    @Override
    public void close() {
        // BUG: no check for outstanding acquires. arena.close() revokes
        // every segment allocated from the shared arena — including ones
        // the caller still holds.
        if (closing.compareAndSet(false, true)) {
            try { arena.close(); }
            finally { closed = true; }
        }
    }
}
```

Caller sequence that exposes the bug:

```java
MemorySegment slab = pool.acquire();       // slab is live
pool.close();                               // silently invalidates slab

// Hundreds of lines of unrelated code ...
slab.set(ValueLayout.JAVA_BYTE, 0, (byte) 42);
// throws IllegalStateException: "Already closed" deep inside
// MemorySegment, far from the pool.close() that caused it.
```

## Symptoms

- `IllegalStateException: Already closed` from `MemorySegment.set/get`
  calls in code that has no direct reference to the pool.
- Stack traces point inside `jdk.internal.foreign` with no line linking to
  the `pool.close()` call that invalidated the segment.
- Flaky under concurrency: a background `close()` can race any acquired
  read; the problem reproduces deterministically on a single-threaded
  `acquire() → close()` sequence (the defect is structural, not race-dependent).
- Tests that cover `close()` on a quiescent pool pass, while tests that
  cover the caller's release protocol fail unrelated assertions.

## Root Cause

The pool's `close()` method assumes all acquired segments have been
released. When they have not, `Arena.close()` on a JDK shared Arena
revokes every segment it handed out — the caller's references survive, but
any read or write on them throws from the Panama runtime. There is no
quiesce mechanism between `acquire()` and `close()` and no liveness check
at the `close()` call site.

Two fixes exist:

1. **Fail-fast (chosen here).** Refuse `close()` while any acquired
   resource is unreleased. The caller is told their protocol is wrong
   **before** the invalidation happens, and the error carries a descriptive
   message naming the acquire-release protocol misuse.
2. **Liveness-counted close that blocks** until all acquires release.
   Valid design but introduces an unbounded wait; rejected here because
   the pool has no fair-queueing or deadline mechanism, and a misbehaving
   caller that forgets to release would block close indefinitely.

## Fix Pattern

Capture `poolSize` as a `final` field at construction; in `close()`,
compare `queue.size()` against `poolSize` before claiming the close
transition. If `outstanding > 0`, throw `IllegalStateException` with a
diagnostic naming the protocol misuse and leaving the pool intact.

```java
private final int poolSize;                    // captured at ctor
private final LinkedBlockingQueue<MemorySegment> queue;
private final AtomicBoolean closing = new AtomicBoolean(false);
private volatile boolean closed = false;

@Override
public void close() {
    if (closed) {
        return;                                 // idempotent fast path
    }
    int outstanding = poolSize - queue.size();
    if (outstanding > 0) {
        throw new IllegalStateException("close() refused: " + outstanding
                + " outstanding acquired segment(s) not yet released —"
                + " every acquire() must be matched by release() before"
                + " closing the pool");
    }
    if (closing.compareAndSet(false, true)) {
        try { arena.close(); }
        finally { closed = true; }
    } else {
        while (!closed) { Thread.onSpinWait(); }
    }
}
```

Three discipline points:

1. The `closed == true` fast path still returns idempotently on an
   already-closed pool — the liveness check is not meaningful post-close.
2. The `queue.size()` check costs one relaxed read plus an integer
   subtraction; no new tracking structure is required when the pool already
   backs acquire/release with a bounded queue.
3. Diagnostic message names the protocol misuse ("every acquire() must be
   matched by release() before closing the pool") so the caller receives an
   actionable error at the `close()` call site rather than a Panama
   runtime exception in an unrelated hot path.

## Detection

- **Concurrency lens**: identify every edge from `close()` to a teardown
  of the shared backing resource; ask "does the teardown revoke handles
  that live outside the pool?" If yes, `close()` must refuse or block on
  liveness.
- **Adversarial test**: acquire a segment on one thread, invoke `close()`
  on another, and assert either (a) close is refused with a diagnostic
  ISE (fail-fast design), or (b) close blocks until release returns the
  segment (quiesce design).
- **Static scan**: look for `close()` methods in classes named `*Pool` or
  `*BufferPool`/`*Resource*` that invoke an underlying `close()` on a
  resource whose own lifecycle propagates to acquired handles (shared
  `Arena`, direct `ByteBuffer` allocator, `MemoryMappedFile`).

## Audit Findings

Identified in `implement-sstable-enhancements--wd-02` audit run-001:

- **F-R1.concurrency.1.2** — `ArenaBufferPool.close()` silently invoked
  `arena.close()` even when outstanding acquires existed, revoking
  caller-held `MemorySegment`s without notification. The fix adds a
  `poolSize - queue.size() > 0` liveness check that throws a descriptive
  `IllegalStateException` before the arena is touched, so the caller can
  release segments and retry `close()`.

The adversarial test reproduces the bug via a single-threaded
`acquire() → close()` (no release) sequence — the defect is structural;
concurrency adds timing nondeterminism but is not required to expose it.

## Codebase Applicability

The jlsm codebase has at least two other acquire/release pool patterns
where the same failure mode is reachable and should be audited:
`ArenaBufferPool` (fixed by this finding) and the buffer staging in
`StripedBlockCache`. Any future pool that wraps a JDK shared `Arena` or
any other "close-revokes-children" resource should apply the fail-fast
liveness check by default.

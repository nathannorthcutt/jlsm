---
title: "Non-Atomic Lifecycle Flags"
aliases: ["check-then-act lifecycle", "volatile boolean race"]
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "lifecycle", "atomicity", "check-then-act"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RapidMembership.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/ClusteredEngine.java"
  - "modules/jlsm-engine/src/test/java/jlsm/engine/cluster/InJvmTransport.java"
related:
  - "lock-held-side-effects"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html"
    title: "JLS Chapter 17 — Threads and Locks"
    accessed: "2026-04-06"
    type: "docs"
---

# Non-Atomic Lifecycle Flags

## Summary

Lifecycle state flags (`started`, `closed`) implemented as `volatile boolean`
with check-then-act patterns race under concurrent access. Two threads can both
pass the guard and execute initialization or cleanup logic simultaneously,
causing leaked resources (duplicate executors, double shutdown) or inconsistent
state. The fix is replacing `volatile boolean` with `AtomicBoolean` and using
`compareAndSet`.

## Problem

A common pattern for guarding one-time lifecycle transitions:

```java
private volatile boolean closed = false;

public void close() {
    if (closed) return;   // guard
    closed = true;        // mark
    // ... cleanup ...
}
```

Between the `if (closed)` read and the `closed = true` write, another thread
can also read `false`, pass the guard, and enter the cleanup block. This is
the classic check-then-act race.

## Symptoms

- Duplicate resource allocation (two executors created on concurrent `start()`)
- Double shutdown (two threads both run cleanup in `close()`)
- Leaked resources when the second entry partially completes
- Intermittent failures under load that disappear when adding synchronization

## Root Cause

`volatile` guarantees visibility but not atomicity of compound operations.
A read-then-write sequence on a volatile field is not atomic — another thread
can interleave between the read and the write.

## Fix Pattern

Replace `volatile boolean` with `AtomicBoolean` and use `compareAndSet`:

```java
private final AtomicBoolean closed = new AtomicBoolean(false);

public void close() {
    if (!closed.compareAndSet(false, true)) return;
    // ... cleanup — guaranteed single entry ...
}
```

`compareAndSet` is a single atomic operation that reads the current value,
compares it to the expected value, and sets the new value if they match. Only
one thread can succeed; all others get `false` and return immediately.

## Detection

- Look for `volatile boolean` fields named `started`, `closed`, `initialized`,
  `running`, `shutdown`
- Check whether the field is read and then written in a non-atomic sequence
- Concurrency lens: CountDownLatch barriers forcing concurrent entry into
  guarded blocks reliably expose the race

## Audit Findings

Identified in engine-clustering audit run-001:
- `RapidMembership.start` — concurrent start created duplicate executors
- `RapidMembership.close` — concurrent close ran shutdown twice
- `ClusteredEngine.close` — concurrent close leaked resources
- `InJvmTransport.close` — concurrent close corrupted registry state

## Updates 2026-04-22

Confirmed again in `implement-sstable-enhancements--wd-02` audit run-001 on
`ArenaBufferPool.close()`. The original code used a `volatile boolean closed`
check-then-act identical to the canonical anti-pattern above; under 50,000
independent two-thread-pair races the losing thread observed the guard as
`false` and re-entered `arena.close()`, which in the JDK shared-Arena case
is **non-idempotent** (`Arena.ofShared().close()` throws
`IllegalStateException: Already closed` on its second invocation). So in
this codebase the bug is not merely "cleanup runs twice" — it escapes as a
crash to the caller.

Nuance added to the pattern: when the underlying resource is itself
non-idempotent (cross-reference `patterns/resource-management/non-idempotent-close.md`),
the flag must be set **after** teardown completes, not before. A single
`AtomicBoolean` claim is not sufficient — observers of `isClosed() == true`
can otherwise see the flag flipped while the underlying resource is mid-close.
The full fix requires two flags:

- `AtomicBoolean closing` — the CAS claim that elects exactly one closer.
- `volatile boolean closed` — the visibility flag, written only **after**
  the teardown call returns, so `isClosed() == true` carries a
  happens-before edge to teardown completion.

Losing CAS threads spin on `closed` so every `close()` caller still returns
with `isClosed()` observable as `true`.

Applies-to extended: `modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java`
(close path). Findings: F-R001.shared_state.2.1 (atomicity),
F-R001.shared_state.2.2 (visibility ordering).

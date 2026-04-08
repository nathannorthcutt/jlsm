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

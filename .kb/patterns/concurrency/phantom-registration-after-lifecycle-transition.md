---
title: "Phantom Registration After Lifecycle Transition"
aliases: ["post-close registration", "orphaned entity", "close-register race"]
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "lifecycle", "registration", "orphan", "close-guard"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/table/HandleTracker.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/LocalEngine.java"
related:
  - "non-atomic-lifecycle-flags"
  - "read-method-missing-close-guard"
decision_refs: []
sources: []
---

# Phantom Registration After Lifecycle Transition

## Summary

Registration or creation operations that succeed after a lifecycle transition
(close, invalidate, drop) has logically completed but has not yet blocked the
registration path. The registered entity becomes orphaned — tracked by nobody,
cleaned up by nobody. This differs from a simple missing close guard because
the transition has started (and may have finished its cleanup), but a concurrent
registration slips through.

## Problem

A component manages a registry of child entities (handles, tables, connections).
When `close()` or `invalidate()` is called, it iterates and cleans up existing
entries. But a concurrent `register()` call that passes the closed-check before
the flag is set (or that targets a sub-registry not yet cleaned) inserts a new
entry after cleanup has finished.

```java
// Thread A: close()
closed = true;
for (var handle : registry.values()) {
    handle.release();
}
registry.clear();

// Thread B: register() — reads closed=false before Thread A's write
if (closed) throw new IllegalStateException("closed");
registry.put(id, newHandle);  // orphaned — no one will release this
```

## Symptoms

- Resource leaks (file handles, memory segments) that accumulate after close
- "Already closed" exceptions on subsequent operations against the orphan
- Leaked entries visible in heap dumps but unreachable from any live root
- Intermittent — only surfaces under concurrent close + register load

## Root Cause

The lifecycle transition and the registration path are not mutually exclusive.
The closed flag check and the registry insertion are not atomic with respect to
the cleanup iteration. Even if the flag is `AtomicBoolean`, the sequence
"check flag → insert into registry" is still a compound operation that races
with "set flag → drain registry."

A subtler variant: `invalidateTable()` detaches one sub-map from a parent map,
but a concurrent register targets the detached sub-map reference that another
thread still holds. The registration succeeds into the detached map, invisible
to any future cleanup.

## Fix Pattern

1. **Lock-based mutual exclusion.** Use a read-write lock where close acquires
   the write lock and register acquires the read lock. Close blocks until all
   in-flight registrations complete, and no new registrations can start after
   close acquires the lock.

2. **Atomic drain pattern.** Replace the registry with an atomically swappable
   reference. Close atomically swaps the registry to a sentinel (e.g., an
   unmodifiable empty map). Register checks for the sentinel after insertion
   and self-cleans if it detects it registered into a dead registry.

3. **Post-registration validation.** After inserting into the registry, re-check
   the closed flag. If closed, remove the just-inserted entry and release it.
   This is simpler than locking but requires that the cleanup path is idempotent.

```java
private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

public Handle register(String id) {
    lifecycleLock.readLock().lock();
    try {
        if (closed.get()) throw new IllegalStateException("closed");
        var handle = createHandle(id);
        registry.put(id, handle);
        return handle;
    } finally {
        lifecycleLock.readLock().unlock();
    }
}

public void close() {
    lifecycleLock.writeLock().lock();
    try {
        if (!closed.compareAndSet(false, true)) return;
        for (var handle : registry.values()) handle.release();
        registry.clear();
    } finally {
        lifecycleLock.writeLock().unlock();
    }
}
```

## Detection

- Concurrency lens: interleave close/register calls with CountDownLatch barriers
  and check for orphaned entries after close completes
- Shared state lens: post-close operation sequence tests — call register after
  close and verify it throws or cleans up
- Look for `register()` / `put()` methods that check a closed flag but do not
  hold a lock that is also acquired by `close()`

## Audit Findings

Identified in in-process-database-engine audit run-001:
- `HandleTracker.register()` — registration after close created orphaned handles
  (concurrency.1.7)
- `HandleTracker.register()` — registration into detached sourceMap after
  invalidateTable (concurrency.1.1)
- `LocalEngine.getTable()` — created new table stack after close
  (shared_state.2.2)

---
title: "Read Method Missing Close Guard"
aliases: ["TOCTOU close race", "unguarded read path"]
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "close", "TOCTOU", "read-lock", "use-after-free"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/IndexRegistry.java"
related:
  - "non-atomic-lifecycle-flags"
  - "lock-held-side-effects"
decision_refs: []
sources: []
---

# Read Method Missing Close Guard

## Summary

Read-only methods on closeable resources check the closed flag and access
internal state without holding the read lock, creating a TOCTOU race with
concurrent `close()`. Between the closed check and the data access, another
thread can close the resource, releasing arena-backed memory and causing
use-after-free. The fix is to acquire the read lock (matching write-path lock
protocol) around the closed check and data access as an atomic unit.

## Problem

A read method checks the closed flag without locking:

```java
public List<Entry> allEntries() {
    if (closed) throw new IllegalStateException("closed");
    return List.copyOf(entries);  // entries may be nulled by close()
}
```

Between the `closed` check and the `entries` access, `close()` on another
thread can set `closed = true` and release resources. The read method then
accesses freed memory or null references.

## Symptoms

- Sporadic `NullPointerException` or `IllegalStateException` in read paths
- Use-after-free crashes when arena-backed memory is accessed after close
- Failures that only appear under concurrent load, never in single-threaded tests
- Write paths work correctly because they acquire the lock

## Root Cause

The write path (mutations, close) acquires a write lock, but read methods skip
lock acquisition. This creates an asymmetric protocol where reads and writes
are not mutually exclusive, allowing `close()` to interleave with reads.

## Fix Pattern

Acquire the read lock around both the closed check and the data access:

```java
public List<Entry> allEntries() {
    lock.readLock().lock();
    try {
        if (closed) throw new IllegalStateException("closed");
        return List.copyOf(entries);
    } finally {
        lock.readLock().unlock();
    }
}
```

This ensures the closed check and data access are atomic with respect to
`close()`, which acquires the write lock.

## Detection

- Compare lock acquisition patterns between read and write methods on the
  same class
- Look for closed-flag checks without surrounding lock acquisition
- concurrency lens: verify lock protocol consistency across all methods

## Audit Findings

Identified in vector-field-type audit run-001:
- `IndexRegistry.findIndex` — no read lock
- `IndexRegistry.isEmpty` — no read lock
- `IndexRegistry.resolveEntry` — no read lock
- `IndexRegistry.allEntries` — no read lock
- `IndexRegistry.schema` — no read lock

---
title: "Fan-Out Iterator Leak"
aliases: ["scatter-gather leak", "partial-failure iterator leak", "abandoned merge iterator"]
topic: "patterns"
category: "resource-management"
tags: ["iterator", "resource-leak", "fan-out", "scatter-gather", "partial-failure"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/partition/PartitionedTable.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/partition/ResultMerger.java"
related:
  - "partial-init-no-rollback"
  - "mutation-outside-rollback-scope"
decision_refs: []
sources: []
---

# Fan-Out Iterator Leak

## Summary

A coordinator dispatches a range or scatter-gather query to multiple partitions
and collects iterators from each. If partition N+1 fails, already-collected
iterators from partitions 0..N are leaked because there is no cleanup in the
error path. The same leak occurs when a merged iterator is abandoned mid-
iteration without being closed — source iterators remain open.

## Problem

Fan-out queries collect one iterator per partition in a loop. Two failure modes:

**Partial failure during collection:** The collection loop opens iterators
sequentially. If the Kth partition throws, iterators 0..K-1 are already open
but no code closes them — the exception propagates without cleanup.

```java
List<Iterator<Entry>> sources = new ArrayList<>();
for (PartitionClient client : clients) {
    // If this throws on client[2], sources[0] and sources[1] leak
    sources.add(client.getRange(from, to));
}
return new MergingIterator(sources);
```

**Abandoned merged iterator:** The caller opens a MergingIterator backed by
N source iterators, reads a few entries, and discards the iterator without
calling close(). If MergingIterator does not implement AutoCloseable or does
not propagate close to all source iterators, resources leak.

## Symptoms

- Open file handles accumulate during range queries under error conditions
- Resource exhaustion (too many open files) after repeated partial failures
- Memory growth from unclosed iterators holding references to SSTable blocks
- Intermittent failures on high-partition-count tables under concurrent load

## Root Cause

The collection loop lacks a try-catch that closes already-collected iterators
on failure. The merging iterator either does not implement AutoCloseable or
does not close all source iterators in its own close() method.

## Fix Pattern

1. **Wrap the collection loop in try-catch with cleanup.** On any exception,
   close all already-collected iterators before re-throwing.

```java
List<CloseableIterator<Entry>> sources = new ArrayList<>();
try {
    for (PartitionClient client : clients) {
        sources.add(client.getRange(from, to));
    }
    return new MergingIterator(sources); // MergingIterator owns sources now
} catch (Exception e) {
    // Close all successfully collected iterators
    for (CloseableIterator<Entry> it : sources) {
        try { it.close(); } catch (Exception suppressed) {
            e.addSuppressed(suppressed);
        }
    }
    throw e;
}
```

2. **Make the merged iterator AutoCloseable.** The MergingIterator must
   implement AutoCloseable and close all source iterators in its close()
   method, accumulating exceptions with the deferred-close pattern.

3. **Document ownership transfer.** The API contract must state that the
   returned iterator owns the underlying resources and the caller must
   close it (preferably via try-with-resources).

## Detection

- Resource lifecycle lens: open a range query across N partitions, inject a
  failure on partition N/2, and verify that all iterators from partitions
  0..(N/2-1) are closed
- Resource lifecycle lens: open a MergingIterator, read one entry, abandon
  it without close(), and verify source iterators are closed (via
  finalization guard or leak detector)
- Look for collection loops that accumulate Closeable resources without a
  surrounding try-catch

## Audit Findings

Identified in table-partitioning audit run-001:
- `PartitionedTable.getRange` — dispatch loop collected iterators without
  cleanup on partial failure (dispatch_routing.1.4)
- `ResultMerger.MergingIterator` — source iterators leaked on abandoned
  iteration (resource_lifecycle.2.1)
- `ResultMerger.MergingIterator` — source iterators lost on exception during
  merge (resource_lifecycle.2.2)

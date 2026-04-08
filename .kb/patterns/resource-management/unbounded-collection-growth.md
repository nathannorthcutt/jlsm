---
title: "Unbounded Collection Growth"
aliases: ["memory leak via unbounded map", "missing eviction"]
topic: "patterns"
category: "resource-management"
tags: ["memory", "resource-management", "collections", "eviction"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RendezvousOwnership.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/GracePeriodManager.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/PhiAccrualFailureDetector.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RapidMembership.java"
related:
  - "partial-init-no-rollback"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html"
    title: "LinkedHashMap — access-order eviction"
    accessed: "2026-04-06"
    type: "docs"
---

# Unbounded Collection Growth

## Summary

Internal maps and caches that grow proportionally to input (epochs seen,
members departed, heartbeats recorded) without any eviction or size bound. In
long-running systems these accumulate indefinitely and eventually exhaust heap
memory. Every collection that grows with input must have a configured capacity
or eviction policy.

## Problem

A map is created to cache or track operational state:

```java
private final Map<Long, OwnershipSnapshot> cache = new ConcurrentHashMap<>();

public OwnershipSnapshot getForEpoch(long epoch) {
    return cache.computeIfAbsent(epoch, this::compute);
}
```

Entries are added on every new epoch but never removed. Over hours or days of
operation, the map grows without bound.

## Symptoms

- Slow, steady increase in heap usage over time (sawtooth GC pattern with
  rising baseline)
- `OutOfMemoryError` after extended operation under churn
- GC pause times increasing as live set grows
- Performance degradation as map operations slow on large tables

## Root Cause

Collections with `put`/`computeIfAbsent` operations but no corresponding
`remove`/`evict`/size-bound operations. The code path that adds entries exists
but the code path that removes stale entries does not.

## Fix Pattern

Choose the appropriate bounding strategy:

1. **Size-bounded LRU** — `LinkedHashMap` with `removeEldestEntry` override
   or a bounded `ConcurrentHashMap` with explicit eviction:

   ```java
   private static final int MAX_CACHE = 64;
   // On insert, if size > MAX_CACHE, remove oldest
   ```

2. **Time-bounded expiry** — track insertion time per entry, evict entries
   older than a threshold on access or via periodic sweep.

3. **Epoch/generation scoping** — retain only the current and previous
   generation; discard all older entries on generation advance.

4. **State-based cleanup** — remove entries when the tracked entity reaches
   a terminal state (e.g., remove DEAD members from the membership set).

## Detection

- Grep for `Map` fields with `put`/`computeIfAbsent` calls — check whether
  a corresponding `remove` or size check exists
- Resource lifecycle lens: trace collection add operations and verify matching
  remove operations
- High-churn test scenarios asserting collection sizes remain bounded

## Audit Findings

Identified in engine-clustering audit run-001:
- `RendezvousOwnership.cache` — per-epoch entries never evicted
- `GracePeriodManager.departures` — departed member entries accumulated
- `PhiAccrualFailureDetector.heartbeatHistory` — heartbeat windows grew unbounded
- `RapidMembership` member set — DEAD entries retained indefinitely

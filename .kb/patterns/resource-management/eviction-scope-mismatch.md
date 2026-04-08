---
title: "Eviction Scope Mismatch"
aliases: ["wrong eviction target", "stale eviction state", "unfair eviction"]
topic: "patterns"
category: "resource-management"
tags: ["eviction", "resource-management", "fairness", "stale-state", "TOCTOU"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/table/HandleTracker.java"
related:
  - "unbounded-collection-growth"
  - "phantom-registration-after-lifecycle-transition"
decision_refs: []
sources: []
---

# Eviction Scope Mismatch

## Summary

Eviction or cleanup logic that operates on the wrong scope — punishing the
wrong entity, evicting from the triggering component rather than the globally
greediest, or using a stale snapshot of the state to decide what to evict. The
eviction succeeds mechanically but violates fairness or correctness.

## Problem

A bounded-resource pool (handles, cache entries, connections) enforces a global
limit. When the limit is hit, the eviction logic must choose which entity to
evict. Two failure modes:

**Wrong target:** The eviction selects the entity that triggered the limit
breach (the requestor) rather than the entity consuming the most resources
(the greediest). This penalizes the newest arrival and rewards the heaviest
consumer.

```java
void onLimitReached(String triggeringTable) {
    // WRONG: evicts from the table that just registered, not the greediest
    evictFrom(triggeringTable);
}
```

**Stale state:** The eviction reads the count of each entity's resources,
selects the greediest, and then evicts. But between the read and the eviction,
concurrent releases change the counts. The eviction removes more entries than
necessary (over-eviction) or targets an entity that is no longer the greediest.

## Symptoms

- Unfair resource distribution: small consumers lose resources while large
  consumers are untouched
- Over-eviction: more entries evicted than necessary, reducing effective pool
  utilization
- Performance cliff for the triggering entity: every limit breach evicts from
  it, regardless of actual usage
- Non-deterministic eviction counts under concurrent load

## Root Cause

**Wrong target** is a logic error: the eviction policy indexes by "who triggered"
rather than "who has the most." This is often an accidental shortcut — the
triggering table's reference is readily available, while finding the greediest
requires iterating all entries.

**Stale state** is a TOCTOU race: the snapshot of resource counts used for the
eviction decision is outdated by the time eviction executes. Under concurrency,
counts change between the scan and the eviction, leading to decisions based on
stale data.

## Fix Pattern

1. **Global greedy scan.** When eviction is triggered, scan all entities and
   select the one with the highest resource count. Do not use the triggering
   entity as a shortcut.

2. **Atomic count-and-evict.** Hold the lock (or use a compare-and-set loop)
   across both the count read and the eviction. This eliminates the TOCTOU
   window.

3. **Evict-one-at-a-time.** Instead of computing "evict N from entity X,"
   evict one entry, re-check the limit, and repeat. This naturally corrects
   for concurrent changes between rounds.

```java
void onLimitReached() {
    synchronized (registry) {
        // Find the greediest entity
        String greediest = registry.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().size()))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (greediest != null) {
            evictOldestFrom(greediest);
        }
    }
}
```

## Detection

- Resource lifecycle lens: apply global pressure and verify which entity loses
  resources — it should be the greediest, not the triggering entity
- Concurrency lens: concurrent eviction stress test with multiple threads
  releasing and registering simultaneously, checking for excess evictions
- Look for eviction methods that accept the triggering entity as a parameter
  and use it directly as the eviction target

## Audit Findings

Identified in in-process-database-engine audit run-001:
- `HandleTracker` — total-limit eviction targeted triggering table instead of
  greediest (resource_lifecycle.1.8)
- `HandleTracker` — TOCTOU race caused over-eviction from stale count
  (concurrency.1.3)

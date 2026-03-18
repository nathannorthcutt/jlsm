---
problem: "Cross-stripe eviction strategy for StripedBlockCache"
slug: "cross-stripe-eviction"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — cross-stripe-eviction

## Problem Statement
Choose how `evict(sstableId)` traverses all stripes in `StripedBlockCache` to remove cached blocks belonging to a compacted SSTable. The existing `LruBlockCache.evict()` uses `map.keySet().removeIf()` under a single lock; the striped version must visit N independent stripes.

## Constraints

### Scale
2–16 stripes. Evict is called once per SSTable removal during compaction — orders of magnitude less frequent than `get()`/`put()`. Typically a handful of evictions per compaction cycle.

### Resources
Pure Java 25. No external dependencies. The library does not own a thread pool or managed executor — it cannot assume one exists. Must not create threads internally.

### Complexity Budget
Minimal. Today's evict is a single `removeIf` call. The striped version should be comparably simple — a loop over stripes calling `evict()` on each.

### Accuracy / Correctness
Must remove ALL entries for the given `sstableId` across all stripes. No stale blocks may survive — serving a stale block after compaction is a correctness bug. However, it is acceptable for concurrent `get()` calls to see an entry in stripe N while stripe M has already been evicted (momentary inconsistency during the eviction sweep).

### Operational Requirements
Latency is not critical — evict runs on the compaction background path. However, evicting from one stripe must not block `get()`/`put()` on other stripes. Each stripe's lock should be held only for the duration of that stripe's `removeIf`, then released before moving to the next.

### Fit
Each stripe is a full `LruBlockCache` with its own `ReentrantLock`. The `evict(long sstableId)` method on `LruBlockCache` already exists and handles locking internally. The striped version can delegate to it.

## Key Constraints (most narrowing)
1. **No thread pool available** — eliminates parallel/async approaches that require an executor
2. **Must not block other stripes** — eliminates acquiring all stripe locks simultaneously
3. **Correctness: all entries removed** — eliminates any approach that skips stripes or short-circuits

## Unknown / Not Specified
None — full profile captured.

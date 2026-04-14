---
problem: "atomic-cross-stripe-eviction"
date: "2026-04-13"
version: 1
status: "closed"
---

# Atomic Cross-Stripe Eviction — Closed (Non-Issue)

## Problem
Atomic eviction across all stripes — concurrent get() might briefly see an entry in an un-evicted stripe during sequential eviction.

## Decision
**Will not pursue.** The brief inconsistency window is harmless.

## Reason
The cache holds block DATA, not file references. A stale cached block from a compacted SSTable returns the same key-value data that was valid moments ago — it wastes a few bytes of cache space until the sequential loop reaches that stripe. This is not a correctness issue: SSTables are immutable, so the cached block contains valid data for that key at that version. The compaction result has the same or newer data, and the stale entry will be evicted within microseconds as the loop progresses.

KB research at `.kb/data-structures/caching/concurrent-cache-eviction-strategies.md` confirms that sampling-based global eviction and TinyLFU admission are the real performance levers — atomic cross-stripe eviction does not appear as a concern in any production cache implementation reviewed (RocksDB LRUCache, Caffeine, Linux page cache).

## Context
Deferred during `cross-stripe-eviction` (2026-03-17). The sequential loop design is correct and the inconsistency window is benign.

## Conditions for Reopening
- A correctness bug is found where stale cached blocks cause incorrect query results (would require a fundamental change in how cache entries reference data)

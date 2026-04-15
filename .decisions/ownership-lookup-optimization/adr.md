---
problem: "ownership-lookup-optimization"
date: "2026-04-13"
version: 1
status: "closed"
---

# Ownership Lookup Optimization — Closed (Won't Pursue)

## Problem
Hot-path lookup optimization for partition→node ownership mapping beyond simple
epoch-keyed caching.

## Decision
**Will not pursue.** This topic is explicitly ruled out and should not be raised again.

## Reason
Premature optimization. The parent ADR (partition-to-node-ownership) specifies epoch-keyed
caching: the HRW result is cached and invalidated only when the membership view epoch
changes. Cache hits are O(1). Cache misses (O(N) HRW recomputation where N = number of
nodes) take ~10µs at 1000 nodes with splitmix64 hashing and occur only on membership view
changes — rare events (node join/leave). There is no evidence of a performance bottleneck.

## Context
- Parent ADR: `.decisions/partition-to-node-ownership/adr.md` — HRW with epoch-keyed caching
- HRW computation: O(N) hash comparisons per partition on cache miss
- At 1000 nodes: ~1000 × 10ns = ~10µs per cache miss
- Cache miss frequency: only on membership epoch change (rare — node join/leave events)

## Conditions for Reopening
If profiling shows the O(N) cache miss path is a measurable bottleneck (e.g., during
rapid cluster scaling with frequent membership changes causing high epoch churn), reopen
with `/architect "ownership-lookup-optimization"` and evaluate precomputed ownership tables,
consistent hashing rings, or incremental HRW updates.

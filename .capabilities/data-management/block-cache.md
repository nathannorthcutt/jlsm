---
title: "Block Cache"
slug: block-cache
domain: data-management
status: active
type: refinement
tags: ["cache", "lru", "striped", "concurrency", "performance"]
features:
  - slug: striped-block-cache
    role: quality
    description: "Striped/sharded LruBlockCache eliminating single-lock contention for multi-threaded read performance"
composes: []
spec_refs: ["F09"]
decision_refs: ["cross-stripe-eviction", "stripe-hash-function"]
kb_refs: ["data-structures/caching"]
depends_on: []
enables: []
---

# Block Cache

In-memory block cache for decompressed SSTable blocks. The striped
implementation partitions the key space across N independent LRU shards,
each with its own lock, eliminating the single-lock contention bottleneck
that caused 75% throughput drop at 2 concurrent threads.

## What it does

StripedBlockCache implements BlockCache by distributing cached blocks
across independent LruBlockCache stripes. Stripe selection hashes
(sstableId, blockOffset) using splitmix64. Each stripe has its own LRU
eviction, capacity budget, and lock. The existing single-stripe
LruBlockCache remains available for single-threaded workloads.

## Features

**Quality:**
- **striped-block-cache** — striped/sharded LRU eliminating single-lock contention under concurrent access

## Key behaviors

- Factory methods on LruBlockCache: `getMultiThreaded()` → StripedBlockCache.Builder, `getSingleThreaded()` → LruBlockCache.Builder
- Stripe selection: splitmix64 hash of (sstableId, blockOffset) — zero-allocation, sub-nanosecond
- Capacity divided evenly across stripes (total / stripeCount per stripe)
- Near-linear throughput scaling under concurrent access
- Same BlockCache interface — transparent to callers
- Cross-stripe eviction uses sequential loop (not atomic multi-stripe)

## Related

- **Specs:** F09 (striped block cache)
- **Decisions:** cross-stripe-eviction (sequential loop approach), stripe-hash-function (splitmix64)
- **KB:** data-structures/caching (LRU variants, concurrent cache patterns)
- **Deferred work:** atomic-cross-stripe-eviction, parallel-large-cache-eviction, hash-distribution-uniformity, power-of-two-stripe-optimization

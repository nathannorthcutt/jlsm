---
title: "Concurrent Cache Eviction Strategies"
aliases: ["cache eviction", "TinyLFU", "W-TinyLFU", "CLOCK-Pro", "cross-stripe eviction"]
topic: "data-structures"
category: "caching"
tags: ["cache", "eviction", "tinylfu", "clock", "concurrent", "stripe", "parallel", "admission"]
complexity:
  time_build: "O(1) amortized per operation"
  time_query: "O(1) lookup"
  space: "O(capacity)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "data-structures/caching/striped-block-cache.md"
  - "data-structures/caching/concurrent-lru-caches.md"
decision_refs: ["atomic-cross-stripe-eviction", "parallel-large-cache-eviction"]
sources:
  - url: "https://github.com/ben-manes/caffeine/wiki/Design"
    title: "Caffeine Cache — Design Wiki"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://github.com/facebook/rocksdb/wiki/Block-Cache"
    title: "RocksDB Block Cache Wiki"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://en.wikipedia.org/wiki/Page_replacement_algorithm"
    title: "Page replacement algorithm — Wikipedia"
    accessed: "2026-04-13"
    type: "reference"
---

# Concurrent Cache Eviction Strategies

## Problem Statement

A striped cache partitions keys across N shards, each with its own lock and
eviction list. Two problems emerge at scale: (1) a global capacity limit
requires cross-stripe eviction decisions — per-shard LRU may evict warm entries
from one shard while cold entries survive in another; (2) with >100 GB caches,
single-threaded eviction becomes a bottleneck.

## Cross-Stripe Eviction Coordination

### Per-Stripe LRU with Global Budget (RocksDB model)

Each stripe has its own LRU. A global atomic counter tracks total size. The
inserting thread evicts from its own stripe when the global limit is exceeded.
No cross-stripe communication. Simple but hot stripes bear all eviction cost
while cold stripes hoard capacity. RocksDB uses this — each of up to 64 shards
maintains independent limits with `strict_capacity_limit` enforced per-shard.

### Sampling-Based Global Approximation

Sample K entries from random stripes; evict the one with the oldest access
timestamp. Approximates global LRU without a global list.

```
globalEvict():
  candidates = [randomStripe().peekLRU() for _ in 0..K]  // K=5-8
  victim = min(candidates, by=accessTime)
  victim.stripe.remove(victim.key)
```

At K=5, expected eviction rank is within top ~17% of the true global LRU
victim. Lock hold time is short (peek only). Used by memcached's slab
rebalancer.

### Two-Choice Eviction

Sample two random stripes, compare their LRU victims, evict the colder one.
Power-of-two-choices gives exponential improvement in tail behavior over random
eviction with minimal coordination and no global state.

### Clock-Sweep Across Stripes

A clock hand rotates across all stripes, clearing reference bits on first pass
and evicting on second. Gives true global ordering but the sweep thread
serializes under high stripe counts. Best for <32 stripes.

## Admission Policies

Admission policies reject cold entries before they displace hot ones.

### TinyLFU

A 4-bit CountMinSketch estimates access frequency (~8 bytes per cache entry).
On insertion, compare the new entry's frequency against the eviction victim's.
Reject the new entry if it is colder. Counters periodically halve (aging) to
adapt to workload shifts.

```
admit(newKey, victimKey):
  return sketch.estimate(newKey) > sketch.estimate(victimKey)
```

### W-TinyLFU (Caffeine)

Extends TinyLFU with a small admission window (~1% of capacity, LRU). New
entries enter the window first. When the window evicts, its victim competes
against the main space's LRU victim via TinyLFU. The window absorbs bursts
without polluting the frequency-filtered main space. Window-to-main ratio
adapts via hill climbing on hit rate.

### Striped Cache Applicability

A shared CountMinSketch works across stripes — atomic increments on 4-bit
counters have low contention because updates distribute across the array.
Per-stripe eviction + global sketch gives TinyLFU-grade hit rates without
coupling eviction logic across stripes.

## Clock Algorithms — Scan-Resistant Variants

**CLOCK-Pro**: Three page categories — hot, cold, test (ghost entries for
recently evicted cold pages). Cold pages promoted on re-reference become hot.
Ghost hits trigger adaptive hot/cold boundary resizing. Scan-resistant because
one-time-access pages stay cold and are evicted first.

**CAR (Clock with Adaptive Replacement)**: Two clock lists — recency (T1) and
frequency (T2) — with adaptive target sizing from ARC. Self-tuning, no magic
parameters. O(1) per operation with no list restructuring on hit. Performance
comparable to ARC.

**Linux page cache**: Active/inactive two-list clock. New pages enter inactive;
repeated access promotes to active. Kernel rebalances so active < inactive.
Pages falling off inactive are reclaimed. Effectively a simplified CLOCK-Pro.

## Parallel Eviction

### Stripe-Affine Eviction Threads

Assign each eviction thread a subset of stripes. Use high/low watermarks:
evict down to low watermark when high is breached, amortizing cost.

```
evictionWorker(myStripes):
  while running:
    awaitHighWatermark()
    for stripe in myStripes:
      stripe.lock()
      while stripe.size() > stripe.lowTarget:
        stripe.evictLRU()
      stripe.unlock()
```

### Lock-Free Eviction Queues (Caffeine model)

Producers (put threads) append events to striped MPSC ring buffers. A single
maintenance thread drains buffers and applies eviction decisions in batch. The
drain thread is the sole writer to eviction structures, eliminating concurrent
modification. Caffeine achieves ~55M ops/s at 8 threads with this model.

### Cooperative Batch Eviction

No dedicated thread. The thread that triggers the high watermark performs a
batch eviction of B entries (B=64-256). Other threads skip via try-lock,
avoiding pile-up.

```
afterInsert():
  if globalSize.get() > highWatermark:
    if evictionLock.tryLock():
      try: evictBatch(B)
      finally: evictionLock.unlock()
```

## Production Systems

| System | Strategy | Coordination | Admission |
|--------|----------|-------------|-----------|
| RocksDB LRUCache | Per-shard LRU (up to 64) | None — per-shard | None |
| RocksDB HyperClockCache | Lock-free clock per shard | None — per-shard | None |
| Caffeine | ConcurrentHashMap + MPSC buffers | Single maintenance thread | W-TinyLFU |
| Linux page cache | Two-list clock | Global kernel reclaim | Inactive-list filter |
| memcached | Per-slab-class LRU | Sampling rebalancer | None |

## Recommendations for jlsm

**Cross-stripe eviction**: Sampling (K=5-8) or two-choice eviction. Both need
minimal coordination, no global lock, and degrade gracefully. Avoid clock sweep
at high stripe counts.

**Admission**: Shared CountMinSketch + per-stripe eviction for TinyLFU-grade
hit rates. ~8 bytes/entry overhead.

**Parallel eviction**: Start with cooperative batch eviction (try-lock). Add
stripe-affine eviction threads if profiling shows latency at >100 GB.

**mmap consideration**: Evicting mmap'd pages means `madvise(DONTNEED)`, not
heap deallocation. The OS page cache provides a second-chance layer (evicted
pages may still be resident), so eviction accuracy matters less.

## Sources

1. [Caffeine Design Wiki](https://github.com/ben-manes/caffeine/wiki/Design) — W-TinyLFU, buffer-based maintenance
2. [RocksDB Block Cache](https://github.com/facebook/rocksdb/wiki/Block-Cache) — per-shard LRU, strict_capacity_limit
3. [Page replacement algorithm — Wikipedia](https://en.wikipedia.org/wiki/Page_replacement_algorithm) — CLOCK-Pro, CAR, Linux two-list clock
---
*Researched: 2026-04-13 | Next review: 2026-10-13*

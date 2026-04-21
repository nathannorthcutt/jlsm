---
title: "Byte-budget cache for variable-size entries"
aliases: ["weighted cache", "byte-weighted LRU", "weigher-based eviction", "charge-based cache"]
topic: "data-structures"
category: "caching"
tags: ["cache", "byte-budget", "weigher", "variable-size", "admission", "pinning", "reference-count", "block-cache", "concurrent"]
complexity:
  time_build: "O(1) amortized per put (plus O(k) evictions when admitting a heavy entry)"
  time_query: "O(1) per get"
  space: "O(bytes_in_use + per-entry metadata)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/cache/"
related:
  - "data-structures/caching/concurrent-lru-caches.md"
  - "data-structures/caching/concurrent-cache-eviction-strategies.md"
  - "data-structures/caching/striped-block-cache.md"
  - "data-structures/caching/capacity-truncation-on-sharding.md"
decision_refs: []
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/Block-Cache"
    title: "RocksDB Block Cache Wiki"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://github.com/ben-manes/caffeine/wiki/Eviction"
    title: "Caffeine — Eviction Wiki"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://smalldatum.blogspot.com/2022/05/rocksdb-internals-lru.html"
    title: "RocksDB internals: LRU (Mark Callaghan)"
    accessed: "2026-04-20"
    type: "blog"
---

# Byte-budget cache for variable-size entries

## summary

A byte-budget cache bounds its capacity in bytes rather than entry count, so
variable-size payloads (SSTable data blocks, index blocks, compressed vs
uncompressed) share a single pool fairly. The core design questions — admission
under pressure, pin/handoff semantics for in-flight readers, and accounting
concurrency — are distinct from the count-based LRU design described in
[concurrent-lru-caches.md](concurrent-lru-caches.md). This article focuses on
those weight-specific concerns.

## how-it-works

Every cached entry carries a weight (byte count). The cache maintains a running
`bytesInUse` against a configured `maxBytes`. On insert, if
`bytesInUse + entry.weight > maxBytes`, eviction runs until the new entry fits
or admission fails. Unlike a count-based cache, one insert may evict many
smaller entries, or one eviction may free enough room for many subsequent
inserts.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| `maxBytes` | Global byte budget | 32 MiB – 16 GiB | Hit rate vs memory pressure |
| `weigher` | `(K,V) → long` — bytes charged | block size + metadata | Accounting accuracy |
| `strictCapacityLimit` | Reject insert when full vs over-evict | bool | Hard bound vs best-effort |
| `singleEntryFraction` | Max fraction of budget one entry may consume | 0.1–0.25 | Caps worst-case eviction fan-out |
| `highPriFraction` | Reserved fraction for hot entries (index/filter blocks) | 0.10–0.25 | Scan resistance |
| `pinOverrunAllowed` | Can live handles temporarily push use > budget | bool | Read correctness vs strictness |

## algorithm-steps

1. **Admission check** — compute entry `w = weigher(k,v)`. If
   `w > singleEntryFraction * maxBytes`, reject (oversized entry should not
   poison the cache).
2. **Reserve** — atomic CAS on `bytesInUse`: `bytesInUse += w`. If result
   exceeds `maxBytes`, go to step 3; otherwise install entry and return.
3. **Evict-to-fit** — while `bytesInUse > maxBytes`, pop LRU victim; if victim
   has pin-count 0, free its weight from the counter and unlink. If victim is
   pinned, skip it (remove from LRU tail, but defer memory free until
   `release()`).
4. **Admission-vs-strict decision** — if after draining all unpinned entries
   `bytesInUse` still exceeds `maxBytes`:
   - `strictCapacityLimit = true`: back out the reservation, return failure;
     the caller reads uncached.
   - `strictCapacityLimit = false`: accept over-budget, record the overage.
5. **Install** — link into hash table and LRU head. The returned handle has
   `refcount = 1`.
6. **get(k)** — hash lookup; if hit, increment refcount and move to LRU head
   (or mark with a touch bit for amortized reorder).
7. **release(handle)** — decrement refcount. If refcount hits zero AND the
   entry was evicted while pinned, free its bytes now. If still live, the
   handle is simply unpinned and remains evictable.

## implementation-notes

### data-structure-requirements

- Hash table mapping key → `Entry{value, weight, refcount, state, lruNode}`
- Doubly-linked LRU list of `refcount == 0` entries (referenced entries live
  outside the list so they cannot be selected as victims)
- Byte counter: `AtomicLong bytesInUse` (single shard) or a per-shard counter
  plus a loosely-synchronized aggregate
- Three-state handle machine (RocksDB model): `(in_cache, refs)` pair —
  `(true, 0)` = in LRU, `(true, ≥1)` = pinned & live, `(false, ≥1)` = evicted
  but outstanding, `(false, 0)` = free

### edge-cases-and-gotchas

- **Single entry > shard capacity** — if sharded by key, a single block larger
  than `maxBytes / shards` must either be rejected or routed to a fallback
  path. Never allow silent drop.
- **Weight mutation after insert** — weights are computed at insert and MUST
  be frozen (Caffeine explicitly states "weights are computed at entry
  creation and update time, are static thereafter"). Re-reading a changed
  value size would require re-admission, not in-place adjustment.
- **Pin-count overrun** — when readers pin blocks faster than they release,
  the cache can exceed its budget (RocksDB: "cache in use > capacity" is
  permitted with `strictCapacityLimit = false`). Document this contract.
- **Over-eviction from one heavy insert** — admitting a 4 MiB block may evict
  dozens of 4 KiB blocks. The eviction loop must be bounded (by iteration
  count, not just condition) to avoid starvation under pathological weights.
- **Counter-vs-map race** — reserving bytes before installing risks leaking
  budget on crash between step 2 and step 5. Use a try/catch that releases
  the reservation on any failure before return.
- **Sharded counter truncation** — giving each shard `maxBytes / N` floors
  the budget; one hot shard cannot borrow from a cold one. See
  [capacity-truncation-on-sharding.md](capacity-truncation-on-sharding.md).

## complexity-analysis

- `put`: O(1) amortized admission + O(k) evictions, where k ≤ `ceil(w / min_entry_weight)`.
- `get`: O(1) hash lookup + O(1) LRU touch (or enqueued for amortized reorder).
- Per-entry overhead ≈ 64–80 B (hash-node + LRU links + refcount + weight +
  state flags), independent of value size. Fixed: one `AtomicLong` per shard.

## tradeoffs

### strengths
- Bounds actual memory, not entry count — behaves predictably under mixed
  block sizes where entry count is a poor proxy.
- Integrates cleanly with pinning: pin-counts let in-flight readers safely
  outlive eviction without copying blocks.
- Admission control with `strictCapacityLimit = true` gives a hard ceiling
  suitable for container memory budgets.

### weaknesses
- Admitting a heavy entry can burst-evict many lighter ones, causing latency
  spikes in the inserter.
- Pin-count overrun breaks the byte ceiling unless strict mode is enabled;
  strict mode hurts hit rate during bursts.
- W-TinyLFU admission operates on frequency, not weight — a heavy hot entry
  may repeatedly displace many warm small entries, which can be worse for
  aggregate hit rate than a count-based policy would suggest.

### compared-to-alternatives

- **Count-based LRU** ([concurrent-lru-caches.md](concurrent-lru-caches.md))
  — simpler accounting, but wastes budget when block sizes vary by 10x or
  more (common: 4 KiB data blocks vs 256 KiB large-value blocks).
- **Per-class pools (memcached slabs)** — separate cache per size class.
  Eliminates the heavy-evicts-many problem at the cost of cross-class
  imbalance and calcification.
- **OS page cache (mmap)** — delegate byte budgeting to the kernel. Loses
  priority/pinning control but eliminates the problem entirely; often the
  right answer for read-heavy stores on local disk.

## reference-implementations

| Library | Language | URL | Notes |
|---------|----------|-----|-------|
| RocksDB `LRUCache` | C++ | [rocksdb/wiki/Block-Cache](https://github.com/facebook/rocksdb/wiki/Block-Cache) | Byte-capacity, per-shard, ref-counted handles, `strict_capacity_limit`, high/low-pri pools |
| RocksDB `HyperClockCache` | C++ | [rocksdb/include/rocksdb/cache.h](https://github.com/facebook/rocksdb/blob/main/include/rocksdb/cache.h) | Lock-free clock per shard, byte-weighted |
| Caffeine (`maximumWeight` + `Weigher`) | Java | [caffeine/wiki/Eviction](https://github.com/ben-manes/caffeine/wiki/Eviction) | Static weights; W-TinyLFU admission frequency-only |
| Apache Cassandra `ChunkCache` | Java | `org.apache.cassandra.cache.ChunkCache` | Caffeine-backed, sized in MiB |

## code-skeleton

@./byte-budget-cache-variable-size-entries-detail.md

## practical-usage

### when-to-use
- Block cache in front of SSTable reader where block sizes range (e.g.,
  4 KiB to 256 KiB). Count-based caps either waste memory or OOM.
- Any cache whose failure mode on overrun is OOM — a byte budget is a real
  ceiling, an entry count is a proxy.

### when-not-to-use
- Uniform entry sizes (page cache where every page is 4 KiB) — byte and
  count are equivalent and the weigher is a tax.
- No pinning requirement and the OS page cache covers the workload — mmap
  and let the kernel handle it.

## sources
1. [RocksDB Block Cache Wiki](https://github.com/facebook/rocksdb/wiki/Block-Cache) — byte capacity, strict_capacity_limit, priority pools, pin overrun
2. [Caffeine Eviction Wiki](https://github.com/ben-manes/caffeine/wiki/Eviction) — maximumWeight + Weigher semantics; weights static after insert
3. [RocksDB internals: LRU — Mark Callaghan](https://smalldatum.blogspot.com/2022/05/rocksdb-internals-lru.html) — three-state handle machine, Lookup/Release ref-count handoff

---
*Researched: 2026-04-20 | Next review: 2026-07-20*

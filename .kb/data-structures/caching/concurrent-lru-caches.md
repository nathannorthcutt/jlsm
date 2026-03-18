---
title: "Concurrent LRU Cache Implementations"
aliases: ["concurrent cache", "thread-safe LRU", "sharded LRU"]
topic: "data-structures"
category: "caching"
tags: ["concurrency", "lru", "cache", "lock-striping", "sharding", "w-tinylfu"]
complexity:
  time_build: "O(1) per put"
  time_query: "O(1) per get"
  space: "O(n) entries + per-strategy overhead"
research_status: "mature"
last_researched: "2026-03-17"
sources:
  - url: "https://github.com/ben-manes/caffeine/wiki/Design"
    title: "Caffeine Cache — Design Wiki"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://highscalability.com/design-of-a-modern-cache/"
    title: "Design of a Modern Cache — High Scalability"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://innovation.ebayinc.com/tech/engineering/high-throughput-thread-safe-lru-caching/"
    title: "High-Throughput, Thread-Safe, LRU Caching — eBay Tech"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://github.com/ben-manes/caffeine/wiki/Benchmarks"
    title: "Caffeine Benchmarks Wiki"
    accessed: "2026-03-17"
    type: "benchmark"
  - url: "https://github.com/ben-manes/concurrentlinkedhashmap"
    title: "ConcurrentLinkedHashMap — Google/Ben Manes"
    accessed: "2026-03-17"
    type: "repo"
  - url: "https://www.baeldung.com/java-lru-cache"
    title: "How to Implement LRU Cache in Java — Baeldung"
    accessed: "2026-03-17"
    type: "blog"
---

# Concurrent LRU Cache Implementations

## summary

Concurrent LRU caches solve the problem of providing bounded, recency-ordered
caching under multi-threaded access. The fundamental tension is that LRU
ordering requires structural modification on every access (even reads), which
conflicts with concurrent access. Solutions range from simple (single lock,
striped/sharded locks) to sophisticated (buffered logging with amortized
eviction, as in Caffeine). The choice depends on contention level, hit-rate
requirements, and acceptable complexity.

## how-it-works

All concurrent LRU designs share the same core challenge: a `get()` in an
access-ordered structure is a write operation because it must move the accessed
entry to the most-recently-used position. This means `ReadWriteLock` cannot
help — every operation is a write.

There are four main strategies, ordered by increasing complexity and
concurrency:

### strategy-1-single-lock

The simplest approach. A single `ReentrantLock` (or `synchronized`) guards a
`LinkedHashMap(accessOrder=true)`.

- **Pros**: Correct, simple, zero external dependencies
- **Cons**: All operations serialize; throughput collapses under concurrency
- **Throughput**: ~30M ops/s single-threaded, drops 75%+ at 2 threads
- **Use when**: Single-threaded access or very low contention

This is the current jlsm `LruBlockCache` design.

### strategy-2-striped-sharded

Partition the key space across N independent cache shards, each with its own
lock and LRU list. Route keys to shards via hash (e.g., `key.hashCode() % N`
or for composite keys like `(sstableId, blockOffset)`, use `sstableId % N`).

```
StripedCache<K, V>:
  shards = new LruShard[N]  // N = power of 2, typically 16-64

  get(key):
    shard = shards[hash(key) & (N-1)]
    return shard.get(key)   // shard has its own lock

  put(key, value):
    shard = shards[hash(key) & (N-1)]
    shard.put(key, value)   // lock contention reduced by factor N
```

- **Pros**: Near-linear scaling for independent keys; simple to implement;
  no external dependencies; each shard is a standard LRU
- **Cons**: Per-shard capacity is `totalCapacity / N` — uneven key
  distribution can cause premature eviction in hot shards; global LRU
  ordering is lost (each shard has independent ordering)
- **Throughput**: Approaches N * single-shard throughput when keys distribute
  evenly; ConcurrentHashMap itself achieves ~60M ops/s at 8 threads
- **Use when**: Keys naturally partition (e.g., by sstableId); moderate
  concurrency (2-16 threads); simplicity is valued

### strategy-3-concurrent-linked-hashmap

Replace `LinkedHashMap` + lock with a purpose-built concurrent ordered map.
Google's `ConcurrentLinkedHashMap` (now in Guava as `CacheBuilder`) threads a
doubly-linked list through a `ConcurrentHashMap` and uses lock-free atomic
operations for list manipulation.

- **Pros**: True concurrent access with approximate LRU; no shard imbalance
- **Cons**: More complex; approximate ordering (not strict LRU); cleanup/purge
  operations add variable latency to some operations
- **Throughput**: ~1M+ lookups/s on 12-core system (eBay benchmark);
  degrades beyond 3 threads per core
- **Use when**: Need global LRU ordering; moderate-to-high concurrency;
  can tolerate approximate eviction ordering

### strategy-4-buffered-amortized (caffeine-style)

The most sophisticated approach, used by Caffeine (successor to Guava Cache).
Separates the hash table from the eviction policy entirely:

1. **ConcurrentHashMap** stores entries (thread-safe, lock-striped by JDK)
2. **Striped ring buffers** capture read/write events without locking the
   eviction structure
3. **Amortized maintenance** — a single thread periodically drains buffers
   and applies eviction decisions in batch

```
CaffeineCache<K, V>:
  map = ConcurrentHashMap
  readBuffer = StripedRingBuffer[stripes]  // stripe by thread hash
  writeBuffer = BoundedCircularArray

  get(key):
    value = map.get(key)
    readBuffer[threadStripe()].offer(key)  // non-blocking
    if readBuffer.full():
      tryScheduleMaintenance()             // async drain
    return value

  put(key, value):
    map.put(key, value)
    writeBuffer.offer(PutEvent(key))       // may spin if full
    tryScheduleMaintenance()

  maintenance():                           // runs on executor
    lock.lock()
    drain readBuffer → reorder access list
    drain writeBuffer → update eviction structures
    apply W-TinyLFU eviction decisions
    lock.unlock()
```

**W-TinyLFU admission policy**: Splits entries into an admission window (small)
and main space (large, segmented LRU). A 4-bit CountMinSketch estimates
frequency. On eviction, compares frequency of window victim vs main victim —
retains the higher-frequency entry. Window-to-main ratio adapts via hill
climbing. Requires ~8 bytes per entry for the sketch.

- **Pros**: Near-ConcurrentHashMap read throughput (~55M ops/s at 8 threads);
  near-optimal hit rate; reads scale linearly with CPU count
- **Cons**: Complex implementation (~15K+ LOC in Caffeine); external
  dependency (Caffeine library is 700KB); amortized maintenance adds
  tail latency; overkill for simple use cases
- **Throughput**: Reads at ~33% of raw ConcurrentHashMap throughput;
  writes at ~10% penalty vs unbounded map (contention on map is dominant)
- **Use when**: Maximum throughput required; hit rate matters; can accept
  external dependency or willing to invest in complex implementation

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Shard count (striped) | Number of independent LRU shards | 16-64 (power of 2) | More shards = less contention but worse per-shard LRU |
| Read buffer stripes (buffered) | Striped ring buffer width | Grows with contention | More stripes = less read contention |
| Sketch width (W-TinyLFU) | CountMinSketch size | 8 bytes/entry | Larger = more accurate frequency estimation |
| Window ratio (W-TinyLFU) | Admission window size vs main | Adaptive (hill climbing) | Balances recency vs frequency bias |

## complexity-analysis

### per-operation

| Strategy | get() | put() | evict() | Notes |
|----------|-------|-------|---------|-------|
| Single lock | O(1) amortized | O(1) amortized | O(1) | All serialized |
| Striped | O(1) | O(1) | O(1) per shard | N-way parallel |
| ConcurrentLinkedHashMap | O(1) | O(1) amortized | O(batch) | Cleanup batches |
| Buffered (Caffeine) | O(1) | O(1) amortized | O(batch) | Maintenance async |

### memory-footprint

| Strategy | Overhead per entry | Fixed overhead |
|----------|-------------------|----------------|
| Single lock (LinkedHashMap) | ~48 bytes (Entry node) | Lock + map header |
| Striped | ~48 bytes * N shards | N locks + N map headers |
| ConcurrentLinkedHashMap | ~64 bytes (node + links) | Map + list headers |
| Buffered (Caffeine) | ~80 bytes (generated node) | Buffers + sketch (~8B/entry) |

## tradeoffs

### strengths

- **Striped**: Simple, predictable, no dependencies, near-linear scaling
  when keys partition well
- **Buffered**: Best absolute throughput, near-optimal hit rates, production-
  proven at massive scale (Caffeine powers Spring, Hibernate, etc.)

### weaknesses

- **Striped**: Loses global LRU ordering; uneven key distribution wastes
  capacity; capacity per shard shrinks with more shards
- **Buffered**: Implementation complexity is very high; adds a runtime
  dependency; amortized maintenance means some operations pay more than
  others (tail latency)

### compared-to-alternatives

- **vs single lock**: All concurrent strategies trade simplicity for
  throughput. The crossover point is ~2 concurrent threads.
- **Striped vs Buffered**: Striped is the right choice when keys naturally
  partition (like `sstableId` in a block cache) and implementation simplicity
  matters. Buffered wins when hit rate optimization and maximum throughput
  are critical.
- **vs external library (Caffeine)**: jlsm is a zero-dependency library,
  so pulling in Caffeine contradicts the design principle. A striped
  approach or simplified buffered approach is more appropriate.

## practical-usage

### when-to-use

- **Striped/sharded**: Block caches where keys partition by SSTable ID;
  moderate thread counts (2-16); pure Java library constraints
- **Buffered**: High-fanout read services; distributed caches; applications
  where an external dependency is acceptable

### when-not-to-use

- **Striped**: When keys cluster on a few shards (hot SSTable problem);
  when global LRU ordering is required
- **Buffered**: When simplicity is valued over throughput; when the cache
  is not on the critical path; in libraries with zero-dependency policy

## reference-implementations

| Library | Language | URL | Strategy | Maintenance |
|---------|----------|-----|----------|-------------|
| Caffeine | Java 11+ | [github.com/ben-manes/caffeine](https://github.com/ben-manes/caffeine) | Buffered W-TinyLFU | Active |
| ConcurrentLinkedHashMap | Java 6+ | [github.com/ben-manes/concurrentlinkedhashmap](https://github.com/ben-manes/concurrentlinkedhashmap) | Lock-free linked | Archived (merged into Guava) |
| Guava Cache | Java 8+ | [github.com/google/guava](https://github.com/google/guava) | Segmented LRU | Active |

## code-skeleton

```java
// Strategy 2: Striped/Sharded LRU Cache
public final class StripedBlockCache implements BlockCache {
    private final LruBlockCache[] shards;
    private final int shardMask;

    public StripedBlockCache(long totalCapacity, int shardCount) {
        assert Integer.bitCount(shardCount) == 1 : "shardCount must be power of 2";
        this.shardMask = shardCount - 1;
        this.shards = new LruBlockCache[shardCount];
        long perShard = totalCapacity / shardCount;
        for (int i = 0; i < shardCount; i++) {
            shards[i] = LruBlockCache.builder().capacity(perShard).build();
        }
    }

    public Optional<MemorySegment> get(long sstableId, long blockOffset) {
        return shardFor(sstableId).get(sstableId, blockOffset);
    }

    public void put(long sstableId, long blockOffset, MemorySegment block) {
        shardFor(sstableId).put(sstableId, blockOffset, block);
    }

    public void evict(long sstableId) {
        shardFor(sstableId).evict(sstableId);
    }

    private LruBlockCache shardFor(long sstableId) {
        return shards[(int)(sstableId & shardMask)];
    }
}
```

## sources

1. [Caffeine Design Wiki](https://github.com/ben-manes/caffeine/wiki/Design) — authoritative source on buffered amortized eviction and W-TinyLFU
2. [Design of a Modern Cache](https://highscalability.com/design-of-a-modern-cache/) — survey comparing LRU, LFU, W-TinyLFU with concurrency strategies
3. [High-Throughput Thread-Safe LRU Caching](https://innovation.ebayinc.com/tech/engineering/high-throughput-thread-safe-lru-caching/) — eBay's lock-free LRU with benchmark data on 12-core system
4. [Caffeine Benchmarks](https://github.com/ben-manes/caffeine/wiki/Benchmarks) — throughput numbers: 55M ops/s at 8 threads for bounded cache
5. [ConcurrentLinkedHashMap](https://github.com/ben-manes/concurrentlinkedhashmap) — predecessor to Caffeine, lock-free concurrent LRU
6. [Baeldung: LRU Cache in Java](https://www.baeldung.com/java-lru-cache) — tutorial covering LinkedHashMap and ConcurrentHashMap approaches

---
*Researched: 2026-03-17 | Next review: 2026-09-17*

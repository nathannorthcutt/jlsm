# jlsm Performance Findings

> Append-only log maintained by the perf-review agent.
> Written during `/perf-review --close`.
> Do not delete entries — mark them as Fixed or Won't Fix instead.
> Sustained findings use the extended format with degradation pattern.

---

<!-- Agent appends Finding blocks below this line -->

## Finding: Assert in MemTable apply() doubles put cost

- **Location:** `jlsm.memtable.ConcurrentSkipListMemTable#apply` line 54
- **Layer:** MemTable
- **Run mode:** Snapshot
- **Tier:** Regression (promoted from scratch — `MemTableBenchmark`)
- **Status:** Fixed
- **Hypothesis:** `assert map.containsKey(compositeKey)` after every `map.put()` performs a full O(log n) skip list traversal, nearly doubling per-put CPU cost
- **Evidence:** Profiler: 173 samples in containsKey→doGet vs 240 in doPut (42% overhead). After removal: put@100K improved 254K→349K ops/s (+37%)
- **Fix applied:** Removed the assert — `ConcurrentSkipListMap.put` is a JDK contract guarantee, not an application invariant
- **Impact:** High
- **Benchmark to validate:** `./gradlew :benchmarks:jlsm-tree-benchmarks:jmh "-Pjmh.includes=MemTableBenchmark"`
- **Detected on commit:** 543f0e3

## Finding: MemorySegment.mismatch overhead in KeyComparator

- **Location:** `jlsm.memtable.internal.KeyComparator#compare` → `MemorySegment.mismatch`
- **Layer:** MemTable
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open
- **Hypothesis:** `MemorySegment.mismatch()` on heap-backed segments has FFM scoped memory access overhead (unsafeGetBase, getIntUnaligned) that is disproportionate for short keys (12-16 bytes). `Arrays.mismatch` on the backing byte[] could be faster.
- **Evidence:** 1363 total samples in KeyComparator.compare, 707 in SegmentBulkOperations.mismatch. Leaf frames: unsafeGetBase (72), getIntUnaligned (19) — boundary checking dominates actual comparison.
- **Proposed fix:** For heap-backed segments, extract the backing byte[] and use `Arrays.mismatch`. Needs isolated micro-benchmark to confirm.
- **Impact:** Medium
- **Benchmark to validate:** Isolate KeyComparator in a micro-benchmark comparing MemorySegment.mismatch vs Arrays.mismatch
- **Detected on commit:** 543f0e3

## Finding: Redundant toArray allocations in SSTable write path

- **Location:** `jlsm.sstable.TrieSSTableWriter#append` + `jlsm.sstable.internal.EntryCodec#encode`
- **Layer:** SSTable encoding / GC pressure
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Fixed
- **Hypothesis:** Each entry appended triggers 3+ `MemorySegment.toArray()` calls — key extracted in append(), again in EntryCodec.encode(), then stored as MemorySegment only to be extracted again in writeKeyIndex(). ~19% of total CPU.
- **Evidence:** 347 profiler samples in toArray. Three distinct call sites for the same key bytes.
- **Fix applied:** Extract key bytes once in append(), pass to EntryCodec.encode(entry, keyBytes) overload, store raw byte[] in indexKeys list, use directly in writeKeyIndex()
- **Impact:** Medium — eliminates 2 redundant toArray + MemorySegment allocations per entry
- **Detected on commit:** 543f0e3

## Finding: I/O dominates SSTable write cost

- **Location:** `jlsm.sstable.TrieSSTableWriter#writeBytes` → `FileChannel.write`
- **Layer:** SSTable I/O
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — investigated, expected behavior
- **Hypothesis:** FileChannel.write syscalls consume 35% of total CPU. Inherent to the write path.
- **Evidence:** 650 samples in IOUtil.write chain. 125 ops/s at 1K entries, 45 ops/s at 10K.
- **Impact:** Low — not actionable without buffering redesign
- **Detected on commit:** 543f0e3

## Finding: Bloom filter hash dominates SSTable read path

- **Location:** `jlsm.sstable.TrieSSTableReader#get` → `BlockedBloomFilter#mightContain` → `Murmur3Hash.hash128`
- **Layer:** SSTable / Bloom filter
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — expected behavior
- **Hypothesis:** Bloom filter hash is the dominant cost per point lookup. getMiss (12.85M ops/s) is 12x faster than getHit (1.07M ops/s) because misses exit at the bloom check.
- **Evidence:** 1,184 samples in bloom+hash. KeyIndex.lookup only 10 samples.
- **Impact:** Low — bloom filter is working correctly
- **Detected on commit:** 543f0e3

## Finding: Compaction dominated by eager SSTable open

- **Location:** `jlsm.compaction.SpookyCompactor#compact` → `TrieSSTableReader.open`
- **Layer:** Compaction / SSTable I/O
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Fixed
- **Hypothesis:** Eager open() reads entire data region per source SSTable before merge begins. 868 profiler samples in open() vs 160 in MergeIterator.next().
- **Fix applied:** Changed `TrieSSTableReader.open()` to `openLazy()` — data read deferred to sequential scan in merge loop
- **Impact:** High — reduces compaction memory footprint and startup latency
- **Detected on commit:** 543f0e3

## Finding: WAL append bottlenecked by per-record fsync

- **Location:** `jlsm.wal.local.LocalWriteAheadLog#append` → `MappedByteBuffer.force()`
- **Layer:** WAL / I/O
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — inherent durability cost
- **Hypothesis:** ~100% of append CPU is the force() call. 427 ops/s at 128B = ~2.3ms per fsync.
- **Proposed fix:** Group commit — batch appends before fsync. Changes durability contract.
- **Impact:** High potential but requires design work
- **Detected on commit:** 543f0e3

## Finding: Partition routing layer — no significant cost

- **Location:** `jlsm.table.internal.RangeMap#routeKey`, `RangeMap#overlapping`, `jlsm.table.internal.ResultMerger#mergeOrdered`
- **Layer:** Table / Partitioning
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — investigated, no significant cost found
- **Hypothesis:** Partition routing (binary search + MemorySegment.mismatch), range overlap discovery (linear scan), and N-way merge (PriorityQueue) may introduce measurable overhead as partition count grows.
- **Evidence:** routeKey: 16.8M ops/s @4P → 9.4M ops/s @64P (~106ns/call). overlapping: 9.5M ops/s @4P → 661K ops/s @64P (~1.5μs/call, O(P) confirmed). mergeOrdered: 6.1M entries/s merged @64P (~163ns/entry). Profiler: SegmentBulkOperations.mismatch dominates routeKey (same FFM overhead as MemTable KeyComparator finding). All costs dwarfed by underlying LSM tree I/O.
- **Proposed fix:** overlapping() could use binary search to find start partition (O(log P + K) vs O(P)), but only worthwhile at hundreds of partitions.
- **Impact:** Low — routing overhead is negligible relative to data access
- **Benchmark to validate:** N/A — scratch confirmed no issue
- **Detected on commit:** 1030761

## Finding: LruBlockCache sustained eviction — no degradation

- **Location:** `jlsm.cache.LruBlockCache`
- **Layer:** Resource Growth
- **Run mode:** Sustained
- **Tier:** Scratch
- **Status:** Open — investigated, no degradation found
- **Hypothesis:** Eviction overhead or GC pressure from CacheKey/Entry churn might cause throughput degradation over time under sustained eviction load.
- **Degradation pattern:** None — flat throughput across 30 iterations (150s)
- **Evidence:** putWithEviction: 24.5M ops/s @1K, 23.7M ops/s @10K — flat across all iterations. mixedGetPut: 28.4M ops/s @1K, 28.3M ops/s @10K — flat. Allocation profile: 316K samples in LinkedHashMap$Entry, 251K in CacheKey — both short-lived, no accumulation. No GC drift.
- **Impact:** Low — eviction is O(1) and GC handles the churn cleanly
- **Benchmark to validate:** N/A — sustained scratch confirmed no growth issue
- **Detected on commit:** 1030761

## Finding: LruBlockCache severe lock contention under concurrency

- **Location:** `jlsm.cache.LruBlockCache#get`, `#put` — single `ReentrantLock`
- **Layer:** Cache / Contention
- **Run mode:** Snapshot
- **Tier:** Regression (promoted from scratch — `LruBlockCacheBenchmark`)
- **Status:** Open
- **Hypothesis:** Single `ReentrantLock` serialises all operations. `LinkedHashMap` with `accessOrder=true` makes every `get()` a structural modification (relinks entry to tail), so `ReadWriteLock` cannot be used. Throughput collapses under concurrent access.
- **Evidence:** 1 thread: 30.6M ops/s. 2 threads: 7.6M ops/s (75% drop). 4 threads: 13.9M ops/s (54% drop). 8 threads: 12.7M ops/s (58% drop). Profiler: AQS.acquire (6 samples), pthread_cond_signal (14), Unsafe.park (3) — lock wait dominates multi-threaded stacks. HashMap.getNode (13) and afterNodeAccess (12) are cheap — lock is the bottleneck, not the data structure.
- **Proposed fix:** Striped/sharded cache — partition key space across N independent LruBlockCache instances (e.g., `sstableId % N`), each with its own lock. Near-linear scaling for independent SSTable reads. Alternative: Caffeine-style concurrent LRU, but adds complexity.
- **Impact:** High — 75% throughput loss at 2 threads makes cache a bottleneck for concurrent SSTable readers
- **Benchmark to validate:** `./gradlew :benchmarks:jlsm-tree-benchmarks:jmh "-Pjmh.includes=LruBlockCacheBenchmark"`
- **Detected on commit:** 1030761

## Finding: Block compression write path — expected Deflate CPU cost

- **Location:** `jlsm.sstable.TrieSSTableWriter#flushCurrentBlock` → `DeflateCodec#compress` → native zlib
- **Layer:** SSTable encoding
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — investigated, expected behavior
- **Hypothesis:** Deflate compression adds CPU cost proportional to data volume during SSTable block flush. Level 6 is more expensive than level 1, with the gap widening at higher entry counts.
- **Evidence:** Write throughput: none@1K 129.6 ops/s, deflate1@1K 93.3 (-28%), deflate6@1K 89.8 (-31%), none@10K 44.5 ops/s, deflate1@10K 38.0 (-15%), deflate6@10K 27.7 (-38%). Profiler: 1,044 samples in zlib native deflate (90%+ of write-path CPU). No Java-level overhead surprises — all cost is in native compression.
- **Impact:** Low — expected trade-off; no implementation defect
- **Benchmark to validate:** N/A — scratch confirmed expected behavior
- **Detected on commit:** 1e70573

## Finding: Block compression improves point-get throughput

- **Location:** `jlsm.sstable.TrieSSTableReader#get` → `readAndDecompressBlock`
- **Layer:** SSTable read
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — investigated, positive result
- **Hypothesis:** Compressed SSTables produce smaller files; eager-load reads less data from disk. Single-block decompression cost is negligible relative to I/O savings.
- **Evidence:** getHit: none@1K 1,500 ops/s, deflate1@1K 1,629 (+9%), deflate6@1K 1,647 (+10%), none@10K 197.5 ops/s, deflate1@10K 237.6 (+20%), deflate6@10K 235.8 (+19%). Decompression overhead fully offset by reduced I/O.
- **Impact:** Low — positive finding, no action needed
- **Benchmark to validate:** N/A — scratch confirmed improvement
- **Detected on commit:** 1e70573

## Finding: Block compression scan path — decompressAllBlocks overhead

- **Location:** `jlsm.sstable.TrieSSTableReader#decompressAllBlocks`
- **Layer:** SSTable read / decompression
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Fixed
- **Hypothesis:** Full scan decompresses all blocks upfront and concatenates into a single byte array before iteration. The decompression CPU cost plus extra allocation/copy degrades scan throughput by ~37-39%.
- **Evidence:** scanAll: none@1K 1,429 ops/s, deflate1@1K 888 (-38%), deflate6@1K 899 (-37%), none@10K 169.0 ops/s, deflate1@10K 106.4 (-37%), deflate6@10K 102.4 (-39%). Degradation is consistent across compression levels, confirming decompression volume (not level) is the driver.
- **Fix applied:** Streaming decompression via `CompressedBlockIterator` (lazy block-by-block) + `IndexRangeIterator` block caching. Both bypass BlockCache. `decompressAllBlocks()` removed.
- **Post-fix evidence:** scanAll deflate1@10K 106.4→108.8 (+2%), deflate6@10K 102.4→112.0 (+9%). Throughput gain modest (decompression CPU dominates); primary win is memory: O(total) → O(single block).
- **Impact:** Medium — throughput +2-9%, memory reduction significant
- **Detected on commit:** 1e70573
- **Fixed on commit:** eef5903

## Finding: PartitionedTable routing overhead — negligible

- **Location:** `jlsm.table.PartitionedTable#routeKey`, `RangeMap#routeKey`, `ResultMerger#mergeOrdered`
- **Layer:** Table / Partitioning
- **Run mode:** Snapshot + Sustained
- **Tier:** Scratch
- **Status:** Open — investigated, no significant cost found
- **Hypothesis:** Partition routing, scatter-gather, and result merging may add measurable overhead to PartitionedTable operations.
- **Evidence:** Snapshot (4 partitions, 1K preloaded): create 279 ops/s (WAL-bound), getHit 1.17M ops/s, rangeScan 12.3K ops/s, mixed 1.42K ops/s. Profiler: routeKey 0 samples, getRange 12 samples (< 1%). Sustained (30 iterations, 150s): flat throughput at 276 ops/s, no degradation. Routing layer contributes no measurable overhead — all cost is in WAL fsync (writes) and DocumentSerializer (reads).
- **Impact:** Low — routing is negligible; no action needed
- **Detected on commit:** eef5903

## Finding: PartitionedTable sustained load — no degradation

- **Location:** `jlsm.table.PartitionedTable` (all operations)
- **Layer:** Table / Resource Growth
- **Run mode:** Sustained
- **Tier:** Scratch
- **Status:** Open — investigated, no degradation found
- **Degradation pattern:** None — flat throughput across 30 iterations (150s)
- **Hypothesis:** Growing data volume across partitions (MemTable flushes, SSTable accumulation) might cause throughput decline.
- **Evidence:** mixedCreateAndGet: 276 ops/s ±5 across all 30 iterations. 64KB flush threshold triggered multiple MemTable flushes. No monotonic decline, no step degradation, no cliff. Allocation profile: byte[] (815), HeapMemorySegmentImpl (201), KeyIndex$TrieNode[] (108) — all proportional to data volume, no leak signatures.
- **Impact:** Low — system is stable under sustained load
- **Detected on commit:** eef5903

## Finding: DocumentSerializer deserialization dominates scan path

- **Location:** `jlsm.table.DocumentSerializer$SchemaSerializer#deserialize`, `DocumentSerializer#decodeField`
- **Layer:** Encoding / Allocation
- **Run mode:** Snapshot
- **Tier:** Scratch
- **Status:** Open — optimization candidate
- **Hypothesis:** Document deserialization consumes 34% of scan CPU. Three avoidable costs: (1) `segment.toArray()` copies entire document binary on every deserialize (103 samples / 8%), (2) `countBoolFieldsUpTo()` iterates schema fields per-document instead of precomputing (19 samples / 1.5%), (3) field type dispatch via pattern matching per-field per-document (~20-30 samples). String allocation (106 samples / 8%) is inherent to the document model.
- **Proposed fix:** (a) Use `segment.heapBase()` to avoid toArray copy for heap-backed segments, (b) precompute boolCount/nullMaskBytes/boolMaskBytes and field-is-boolean flags in SchemaSerializer constructor, (c) precompute field decoder dispatch table. Combined ~10-12% scan CPU reduction.
- **Impact:** Medium — 34% of scan CPU; optimizations could recover ~10-12%
- **Benchmark to validate:** Scratch benchmark comparing scan throughput before/after
- **Detected on commit:** eef5903

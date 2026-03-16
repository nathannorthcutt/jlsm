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

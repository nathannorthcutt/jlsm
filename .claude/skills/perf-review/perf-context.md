# jlsm Performance Context

> This file is maintained by the perf-review agent. Updated at the end of
> every session via `/perf-review --close`. Do not edit manually unless
> correcting an error.

---

## Gradle JMH invocations

Plugin: `me.champeau.jmh` v0.7.2, JMH 1.37
Shared config: `benchmarks/jmh-common.gradle` (applied by all benchmark modules)

### Benchmark modules

| Module | Gradle task | Purpose |
|--------|-------------|---------|
| `jlsm-bloom-benchmarks` | `:benchmarks:jlsm-bloom-benchmarks:jmh` | Bloom filter benchmarks |
| `jlsm-tree-benchmarks` | `:benchmarks:jlsm-tree-benchmarks:jmh` | LSM tree benchmarks |
| `jlsm-perf-scratch` | `:benchmarks:jlsm-perf-scratch:jmh` | Scratch benchmarks (sources from `perf-scratch/`) |

### Commands

```bash
# ── Snapshot run (default) ───────────────────────────────────────────────
# fork=2, warmup=3x1s, measure=5x2s
./gradlew :benchmarks:jlsm-perf-scratch:jmh "-Pjmh.includes=<Name>Scratch"

# ── Sustained run ────────────────────────────────────────────────────────
# fork=1, warmup=5x5s, measure=30x5s (state accumulates across iterations)
./gradlew :benchmarks:jlsm-perf-scratch:jmh "-Pjmh.includes=<Name>Scratch" -PrunMode=sustained

# ── With async-profiler (collapsed stacks) ───────────────────────────────
# CPU profile (snapshot):
./gradlew :benchmarks:jlsm-perf-scratch:jmhProfile -Pprofile "-Pjmh.includes=<Name>"
# Allocation profile (sustained):
./gradlew :benchmarks:jlsm-perf-scratch:jmhProfile -Pprofile -PprofileEvent=alloc -PrunMode=sustained "-Pjmh.includes=<Name>"

# ── With JFR recording (sustained only) ─────────────────────────────────
./gradlew :benchmarks:jlsm-perf-scratch:jmh -PrunMode=sustained -Pjfr "-Pjmh.includes=<Name>"

# ── With GC text log (sustained only) ───────────────────────────────────
./gradlew :benchmarks:jlsm-perf-scratch:jmh -PrunMode=sustained -PgcLog "-Pjmh.includes=<Name>"

# ── Full sustained analysis (all profiling enabled) ─────────────────────
./gradlew :benchmarks:jlsm-perf-scratch:jmhProfile \
  -Pprofile -PprofileEvent=alloc -PrunMode=sustained -Pjfr -PgcLog \
  "-Pjmh.includes=<Name>"
```

### Output routing

All results go to `perf-output/<module-name>/`:
- `results.json` — JMH JSON with per-iteration `rawData` arrays
- `profiler.collapsed` — CPU collapsed stacks (snapshot, `-Pprofile`)
- `alloc.collapsed` — allocation collapsed stacks (sustained, `-Pprofile -PprofileEvent=alloc`)
- `recording.jfr` — JFR recording (sustained, `-Pjfr`)
- `gc.log` — GC text log (sustained, `-PgcLog`)

### JVM flags (always applied)

- `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` — line-level profiler accuracy
- `-ea` — assertions enabled in benchmarked code
- `--add-exports` for all internal packages (jlsm-core, jlsm-table)
- `--add-modules jdk.incubator.vector`

### Run mode defaults

| Mode | Fork | Warmup | Measurement |
|------|------|--------|-------------|
| Snapshot (default) | 2 | 3x1s | 5x2s |
| Sustained (`-PrunMode=sustained`) | 1 | 5x5s | 30x5s |
| With profiler (`-Pprofile`) | 1 (override) | — | — |

---

## Output path conventions

```
perf-scratch/
  <Name>Scratch.java                    ← scratch benchmark source, deleted after analysis

perf-output/
  <benchmark-module>/
    results.json                        ← JMH JSON (per-iteration rawData arrays)
    profiler.collapsed                  ← CPU collapsed stacks (snapshot)
    alloc.collapsed                     ← allocation collapsed stacks (sustained)
    recording.jfr                       ← JFR recording (sustained)
    gc.log                              ← GC text log (sustained)
  findings.md                           ← append-only findings log
```

> Note: Gradle overwrites `results.json` on each run. To preserve history,
> the agent should copy/rename before re-running the same module.

---

## Module inventory

| Module | Benchmark module | Description | Snapshot coverage | Sustained coverage |
|--------|-----------------|-------------|------------------|--------------------|
| jlsm-core | jlsm-bloom-benchmarks | Bloom filter (add, mightContain) | ❌ | ❌ |
| jlsm-core | jlsm-tree-benchmarks | LSM tree (mixed put/update/delete) | ❌ | ❌ |
| jlsm-core | jlsm-tree-benchmarks | MemTable (skip list) | ✅ MemTableBenchmark | ❌ |
| jlsm-core | — | SSTable write (TrieSSTableWriter) | 🔍 scratch explored 2026-03-16, compression explored 2026-03-18 | ❌ |
| jlsm-core | — | SSTable read (TrieSSTableReader) | 🔍 scratch explored 2026-03-16, compression explored 2026-03-18 | ❌ |
| jlsm-core | — | SSTable compression (DeflateCodec) | 🔍 scratch explored 2026-03-18 (write/read/scan with deflate1+6) | ❌ |
| jlsm-core | — | Compaction (SpookyCompactor) | 🔍 scratch explored 2026-03-16 | ❌ |
| jlsm-core | — | WAL (LocalWriteAheadLog) | 🔍 scratch explored 2026-03-16 | ❌ |
| jlsm-core | jlsm-tree-benchmarks | Block cache (LRU) | ✅ LruBlockCacheBenchmark | 🔍 scratch explored 2026-03-17 (no degradation) |
| jlsm-indexing | — | Inverted index | ❌ | ❌ |
| jlsm-vector | — | IvfFlat / Hnsw | ❌ | ❌ |
| jlsm-table | — | Document model + secondary indices | ❌ | ❌ |
| jlsm-table | — | Partition routing (RangeMap, ResultMerger) | 🔍 scratch explored 2026-03-17 | ❌ |

> ✅ = benchmarked and baselined  ❌ = not yet covered  ⚠️ = stale (rerun needed)

---

## Known hotpaths (snapshot — per-operation cost)

> Format: `Module#Class#method — layer — confirmed on <commit> — <ops/s or ns/op>`

- `jlsm-core#KeyComparator#compare — MemTable — 543f0e3 — 1363 profiler samples (dominant frame in all MemTable ops)`
- `jlsm-core#ConcurrentSkipListMemTable#apply — MemTable — 543f0e3 — put@100K: 349K ops/s (post-fix baseline)`
- `jlsm-core#BlockedBloomFilter#mightContain → Murmur3Hash — SSTable read — 543f0e3 — 1184 samples (dominates get path)`
- `jlsm-core#TrieSSTableReader#get — SSTable read — 543f0e3 — getHit: 1.07M ops/s, getMiss: 12.85M ops/s`
- `jlsm-core#TrieSSTableWriter#append — SSTable write — 543f0e3 — 125 ops/s @1K entries, 45 ops/s @10K (post-fix)`
- `jlsm-core#MappedByteBuffer#force — WAL — 543f0e3 — ~100% of append CPU; 427 ops/s @128B`
- `jlsm-core#LruBlockCache#get/#put — Cache/Contention — 1030761 — 1T: 30.6M ops/s, 2T: 7.6M ops/s (75% drop), 8T: 12.7M ops/s (58% drop)`
- `jlsm-core#DeflateCodec#compress → zlib — SSTable write — 1e70573 — 90%+ of write-path CPU when compression enabled; write@10K: none 44.5, deflate1 38.0 (-15%), deflate6 27.7 (-38%) ops/s`
- `jlsm-core#TrieSSTableReader#decompressAllBlocks — SSTable scan — 1e70573 — scan@10K: none 169, deflate1 106 (-37%), deflate6 102 (-39%) ops/s`

---

## Known growth problems (sustained — accumulation over time)

> Format: `Module#Class#structure — layer — pattern — confirmed on <commit>`
> Patterns: Monotonic | Step | Flat-then-cliff | GC drift

*(none yet)*

---

## Regression benchmark baselines

> Updated when a regression benchmark is promoted or re-run.
> Format: class — run mode — metric — baseline value — threshold — commit

- `MemTableBenchmark` — snapshot — put@1K: 171K ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — put@10K: 227K ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — put@100K: 349K ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — getHit@1K: 2.14M ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — getHit@10K: 1.52M ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — getHit@100K: 1.39M ops/s — 10% threshold — 543f0e3
- `MemTableBenchmark` — snapshot — scan: 4.60M ops/s — 10% threshold — 543f0e3
- `LruBlockCacheBenchmark` — snapshot — putWithEviction@1K: 23.2M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — putWithEviction@10K: 22.8M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — mixedGetPut@1K: 32.8M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — mixedGetPut@10K: 28.2M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — contention_t1@1K: 30.6M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — contention_t2@1K: 7.6M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — contention_t4@1K: 13.9M ops/s — 10% threshold — 1030761
- `LruBlockCacheBenchmark` — snapshot — contention_t8@1K: 12.7M ops/s — 10% threshold — 1030761

---

## Benchmark gaps

> Components identified as performance-sensitive but not yet benchmarked.
> Agent populates this during exploration. Cleared when filled.

Explored but no regression benchmark yet (scratch-only, 2026-03-16):
- TrieSSTableWriter — SSTable write path (toArray fix applied; I/O dominates remaining cost)
- TrieSSTableReader — SSTable read path (bloom hash dominates; KeyIndex fast)
- SpookyCompactor — compaction merge (lazy open fix applied; MergeIterator is efficient)
- LocalWriteAheadLog — WAL (fsync dominates; group commit needed for throughput)

Explored but no regression benchmark yet (scratch-only, 2026-03-17):
- RangeMap + ResultMerger — partition routing layer (no significant cost found; routing ~106ns, overlap ~1.5μs @64P)

Not yet explored:
- BlockedBloomFilter — bloom filter standalone (hash + double-hash loop)
- LsmVectorIndex — SIMD distance computation (snapshot candidate)
- DocumentSerializer — SIMD byte-swap encoding (snapshot candidate)
- FieldIndex — secondary index maintenance (sustained candidate)
- SqlLexer + SqlParser — SQL parsing overhead (snapshot candidate)

---

## Sustained run candidates

> Structures that grow with dataset size and need sustained run coverage.
> These are the highest-value targets for leak and degradation detection.

Examples to evaluate on first exploration:
- MemTable skip list — does search cost grow with entry count?
- Bloom filter sizing — does memory grow proportionally or leak?
- ~~Block cache — does eviction keep memory bounded under sustained load?~~ ✅ confirmed bounded, no drift (1030761)
- Compaction merge iterator — do file handles accumulate between compactions?

*(agent populates confirmed candidates here)*

---

## JFR analysis commands

When a `recording.jfr` is produced by a sustained run, use these to extract
events without an external GUI:

```bash
# GC pauses and heap summary
jfr print --events jdk.GarbageCollection,jdk.HeapSummary recording.jfr

# Allocation top sites
jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB recording.jfr

# All events summary
jfr summary recording.jfr
```

---

## Module encapsulation notes

> Any `--add-opens` requirements or profiling limitations discovered
> during sessions.

*(none yet)*

---

## Closed / won't-fix

> Findings resolved or ruled out. Never deleted — moved here for reference.

- **I/O dominates SSTable write** — 35% of CPU in FileChannel.write; inherent to write path (543f0e3)
- **Bloom hash dominates SSTable read** — expected; getMiss 12x faster than getHit proves bloom is working (543f0e3)
- **Partition routing overhead** — routeKey ~106ns, overlapping ~1.5μs, mergeOrdered ~163ns/entry @64P; all negligible vs data I/O (1030761)
- **Block compression write cost** — Deflate CPU is the expected trade-off; 90%+ in native zlib, no Java-level overhead (1e70573)
- **Block compression point-get improvement** — smaller files offset decompression cost; +9-20% getHit throughput (1e70573)

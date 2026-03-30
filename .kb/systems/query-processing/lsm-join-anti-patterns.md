---
title: "LSM-Tree Join Anti-Patterns — Common Mistakes and Optimization Techniques"
aliases: ["join-anti-patterns", "lsm-join-mistakes", "join-optimization"]
topic: "systems"
category: "query-processing"
tags: ["join", "lsm-tree", "anti-pattern", "optimization", "multiget", "bloom-filter"]
complexity:
  time_build: "N/A"
  time_query: "varies — anti-patterns can cause 10-100x slowdown"
  space: "varies"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql"
  - "modules/jlsm-table/src/main/java/jlsm/table"
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/MultiGet-Performance"
    title: "MultiGet Performance — RocksDB Wiki"
    accessed: "2026-03-30"
    type: "docs"
  - url: "https://github.com/facebook/rocksdb/wiki/RocksDB-Bloom-Filter"
    title: "RocksDB Bloom Filter — RocksDB Wiki"
    accessed: "2026-03-30"
    type: "docs"
  - url: "https://github.com/facebook/rocksdb/wiki/Prefix-Seek"
    title: "Prefix Seek — RocksDB Wiki"
    accessed: "2026-03-30"
    type: "docs"
  - url: "https://disc-projects.bu.edu/lethe/"
    title: "Lethe: Enabling Efficient Deletes in LSMs"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2501.16759"
    title: "Are Joins over LSM-trees Ready? (VLDB 2025)"
    accessed: "2026-03-30"
    type: "paper"
---

# LSM-Tree Join Anti-Patterns

## summary

Joins over LSM-tree storage have seven major anti-patterns that cause 10-100x performance
degradation compared to optimal execution. The most common: naive nested loop causing
O(n*m) multi-level point lookups, and range joins that bypass bloom filters entirely.
Corresponding optimizations exist for each: MultiGet batching, prefix bloom filters,
lazy materialization, and join indexes. This file catalogs what to avoid and what to do
instead, specifically for LSM-backed storage.

## how-it-works

### anti-patterns

**1. Naive nested loop → O(n*m) multi-level point lookups**

Each Get() in an LSM tree probes L levels with ~6 cache misses per bloom filter check.
For an outer table of N rows, this means ~6N cache misses just for bloom probing, before
any data block reads. The I/O pattern is random across SSTable levels, defeating read-ahead.

**Fix:** Batch keys via MultiGet (128-1024 per batch). Three benefits:
- Block cache lock accessed once per batch per SSTable (not once per key)
- Bloom filter cache-line accesses pipelined, hiding latency
- Parallel I/O for data blocks in same SSTable (io_uring on Linux 5.1+)

**2. Join on non-prefix keys → full table scan**

Prefix bloom filters only help when join key matches the configured `prefix_extractor`.
Joining on a suffix or embedded field within a composite key forces total-order iteration
across every SSTable at every level. Prefix seek can skip entire SST files — but only if
the join key IS the prefix.

**Fix:** Design composite keys with join key as prefix: `[join_key][sort_key]`. Or
maintain a secondary index with the join key as its primary key. Use `auto_prefix_mode`
to get prefix optimization without correctness risks.

**3. Large intermediate result materialization → memory exhaustion**

Hash join build tables, merge join cross-product buffers, and materialized intermediate
results compete for memory with block cache, bloom filters, and memtables. LSM engines
already have tight memory budgets across these internal structures.

**Fix:** Spill-to-disk hash join with partitioned spilling. Or prefer sort-merge join
(O(1) streaming memory) when both sides are sorted. Set explicit memory limits for join
operators with graceful degradation to external sort.

**4. Ignoring bloom filter FPR in join planning**

At 5% FPR with 4 LSM levels, a 100K-row probe side generates 20K unnecessary block reads
(f × P × L). Prefix bloom filters have higher FPR than whole-key filters because there
are fewer unique prefixes.

**Fix:** Use 10 bits/key minimum (1% FPR). Consider Ribbon filters (30% less space at
cost of 3-4x more CPU — worthwhile when filters are built during background compaction).
Factor FPR into join cost model when choosing between INLJ and sort-merge.

**5. Range joins bypass bloom filters entirely**

Predicates like `a.price BETWEEN b.low AND b.high` cannot use bloom filters. Every SSTable
at every level must be examined via range scan. Accumulated tombstones further pollute
bloom filters, compounding the problem (Lethe research).

**Fix:** Use sort-merge join for range predicates (sequential scan, no bloom dependence).
If one side is small, consider hash join with range-bucketing. For repeated range join
patterns, build a dedicated sorted index on the range attribute.

**6. Mismatched compaction strategies across joined tables**

Leveled compaction (1 sorted run/level, read-optimized) vs size-tiered (multiple runs/level,
write-optimized) produce different scan characteristics. Sort-merge join expecting symmetric
scan throughput will be bottlenecked by the size-tiered side (10x more runs to merge).

**Fix:** Use consistent compaction strategies for tables that are frequently joined. Or
account for asymmetry in the join cost model. If one table is write-heavy and size-tiered,
prefer it as the probe side of a hash join (single sequential scan).

**7. Long-running joins blocking compaction**

Joins hold snapshots that pin SSTable files, preventing compaction from:
- Garbage-collecting old MVCC versions
- Dropping tombstones below the snapshot's sequence number
- Merging levels

This causes space amplification and tombstone accumulation that degrades the scans the
join itself depends on — a feedback loop.

**Fix:** Use time-bounded snapshots with explicit TTL. Break large joins into batched
chunks (join 10K rows, release snapshot, re-acquire). Use READ COMMITTED isolation with
per-statement snapshots for long-running analytics (CockroachDB pattern).

### optimization-techniques

**MultiGet batching (the single most impactful optimization):**
- Collect 128-1024 probe keys before issuing lookup
- Single block cache lock per batch per SSTable
- Pipelined bloom filter cache-line access
- Parallel I/O via io_uring for data blocks in same SSTable
- Transforms O(N) serial random reads into O(N/batch) batched operations

**Lazy materialization:**
- Read only join key column first
- Determine matches before reading remaining columns
- Avoids I/O for wide rows that fail the join predicate
- StarRocks: 8.44% overall improvement on TPC-H 1TB, up to 27% on Q11

**Join index (composite key for frequent patterns):**
- Key: `[join_key][table_id][row_key]` co-locates all matching rows
- Transforms multi-table join into single prefix scan
- Tradeoff: write amplification (every insert updates the join index)
- Best for read-heavy workloads with stable join patterns

**Denormalization (join avoidance):**
- Co-locate related data under shared key prefix
- `[entity_type][join_key][sort_key]` → "join" becomes two prefix scans
- Dominant pattern in Cassandra, HBase, Bigtable
- Tradeoff: update complexity, data duplication

**Merge iterator composition:**
- Each table exposes sorted iterator (LSM merge iterator)
- Cross-table merge via min-heap of current keys
- Natural building block for sort-merge joins
- Handles tombstones via snapshot visibility check

## implementation-notes

### cost-model-for-join-planning

```
Point lookup cost:   Θ((log²(N/B)) / log(k))  per key
                     Practical: 3-5 I/Os per lookup in 5-7 level LSM
Sequential scan:     O(N/B)  total — sequential, benefits from read-ahead
Crossover point:     When probe keys > N/(B*L), full scan beats point lookups
                     10GB table, 4KB blocks, 5 levels → crossover at ~500K keys
```

**Decision rules:**
- Probe keys < crossover → INLJ (with MultiGet batching)
- Probe keys > crossover, both sides sorted → sort-merge
- Probe keys > crossover, one side unsorted → hash join
- Range predicate → sort-merge (bloom filters useless)

### edge-cases-and-gotchas
- MultiGet returns results in arbitrary order — must re-associate with probe keys
- Prefix bloom filters require consistent prefix length across all keys in the table
- Join indexes have non-trivial consistency requirements (must be updated atomically
  with base table writes)
- Tombstone-heavy tables degrade both INLJ (bloom filter pollution) and sort-merge
  (wasted scan I/O on dead rows) — compact before joining if tombstone ratio > 10%
- Compaction running concurrently with a sort-merge join can cause iterator
  invalidation in engines without snapshot-pinned iterators

### kv-store-join-patterns (no SQL layer)
When implementing joins over raw key-value LSM (relevant for jlsm):
1. **Sort-merge:** open merge iterators on both tables, advance in lockstep
2. **INLJ + MultiGet:** iterate outer, batch-collect keys, multi-get from inner
3. **Composite key co-location:** design keys to pre-join at write time
4. **Secondary index hop:** scan secondary index → batch primary key lookup
   (dual-lookup problem: two LSM traversals per row)

## tradeoffs

### strengths
- Each anti-pattern has a known, well-tested fix
- MultiGet batching alone can improve INLJ by 5-10x
- Sort-merge join is nearly free when both sides match LSM sort order
- Bloom filters provide 99% rejection of non-matching probes at 10 bits/key

### weaknesses
- CPU read amplification (decompression, bloom checks) is hard to eliminate
- Range joins have no bloom filter shortcut — always expensive
- Join indexes add write amplification and consistency complexity
- Snapshot pinning during joins is inherent — can only be mitigated, not eliminated

## practical-usage

### checklist-before-implementing-lsm-joins
1. Does the join key match the LSM sort order (primary key prefix)?
2. What is the expected join selectivity (match rate)?
3. Are bloom filters configured with ≥10 bits/key?
4. Is the outer side small enough for INLJ, or should we sort-merge?
5. Is the predicate a point or range join?
6. Do both tables use the same compaction strategy?
7. How long will the join run? (snapshot pinning impact)
8. What is the tombstone ratio in both tables? (compact first if >10%)

## sources

1. [RocksDB MultiGet Performance](https://github.com/facebook/rocksdb/wiki/MultiGet-Performance) — batching mechanics
2. [RocksDB Bloom Filter](https://github.com/facebook/rocksdb/wiki/RocksDB-Bloom-Filter) — FPR analysis
3. [Prefix Seek](https://github.com/facebook/rocksdb/wiki/Prefix-Seek) — prefix bloom optimization
4. [Lethe](https://disc-projects.bu.edu/lethe/) — tombstone impact on reads
5. [VLDB 2025 LSM Joins](https://arxiv.org/html/2501.16759) — comprehensive benchmark

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

---
title: "Join Algorithms for LSM-Tree Storage — Selection, Cost Models, and Production Patterns"
aliases: ["lsm-join", "sort-merge-join-lsm", "hash-join-lsm", "index-nested-loop-lsm"]
topic: "systems"
category: "query-processing"
tags: ["join", "lsm-tree", "sort-merge", "hash-join", "cost-model", "query-execution"]
complexity:
  time_build: "N/A"
  time_query: "O(N+M) sort-merge; O(N*lookup) INLJ; O(N+M) hash join"
  space: "O(1) sort-merge; O(min(N,M)) hash join"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql"
  - "modules/jlsm-table/src/main/java/jlsm/table"
sources:
  - url: "https://arxiv.org/html/2501.16759"
    title: "Are Joins over LSM-trees Ready? (VLDB 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p1077-luo.pdf"
    title: "Are Joins over LSM-trees Ready? (VLDB PDF)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://www.cockroachlabs.com/blog/vectorizing-the-merge-joiner-in-cockroachdb/"
    title: "Vectorizing the Merge Joiner in CockroachDB"
    accessed: "2026-03-30"
    type: "blog"
  - url: "https://docs.pingcap.com/tidb/stable/explain-joins/"
    title: "TiDB Explain Statements That Use Joins"
    accessed: "2026-03-30"
    type: "docs"
  - url: "https://smalldatum.blogspot.com/2024/07/myrocks-vs-innodb-on-cached-sysbench.html"
    title: "MyRocks vs InnoDB on Cached Sysbench (2024)"
    accessed: "2026-03-30"
    type: "benchmark"
---

# Join Algorithms for LSM-Tree Storage

## summary

Joins over LSM-tree-backed tables have fundamentally different cost characteristics than
joins over B-tree storage. LSM data is sorted within runs (enabling free sort-merge joins
on primary keys), but point lookups are expensive (multi-level probing). The VLDB 2025
paper "Are Joins over LSM-trees Ready?" benchmarks 29 join methods and finds: sort-merge
wins for repeated joins at high frequency, hash join is the robust default, and index
nested loop is optimal for selective joins with good bloom filters. The choice depends on
join selectivity, entry size, and whether join keys match the LSM sort order.

## how-it-works

### algorithm-selection-matrix

| Condition | Best Algorithm | Why |
|-----------|---------------|-----|
| Both sides sorted on join key | Sort-merge | Sort phase is free; streaming O(N+M) |
| One side small, fits in memory | Hash join | Single scan of large side |
| Outer side very small + inner has bloom filter | Index nested loop | ~6% of probes trigger I/O |
| Join key not a prefix of stored key | Hash join | Cannot exploit LSM sort order |
| Large entries (>1KB) | Index nested loop | Hash join 6x slower; INLJ only 50% slower |
| High join frequency (repeated) | Sort-merge with secondary index | Amortizes index maintenance |
| Range join predicate | Sort-merge | Bloom filters cannot help range predicates |

### key-parameters

| Parameter | Description | Impact |
|-----------|-------------|--------|
| Bloom filter bits/key | FPR for point lookups | 10 bits → 1% FPR; 30% less INLJ latency vs 2 bits |
| LSM levels (L) | Depth of tree | Each level adds potential I/O per point lookup |
| Entry size | Row width in bytes | Hash join degrades 6x at 4KB vs 32B; INLJ degrades 50% |
| Join selectivity | Match rate | Low selectivity → INLJ; high → sort-merge or hash |
| Level ratio (k) | Growth factor between levels | Affects point lookup cost: Θ(log²(N/B) / log k) |

## algorithm-steps

### sort-merge-join
1. **Open merge iterators** on both tables (LSM provides globally-sorted view natively)
2. **Advance** both iterators in lockstep, comparing join keys
3. **On match:** emit joined row; handle cross-product for duplicate keys
4. **On mismatch:** advance the iterator with the smaller key
5. **I/O cost:** `5(N*e/B)` — sequential scan of both sides, no random reads
6. **Memory:** O(1) working set (streaming)

### hash-join
1. **Scan build side** (smaller table) into in-memory hash table on join key
2. **Scan probe side** (larger table), looking up each key in hash table
3. **On match:** emit joined row
4. **I/O cost:** `3(NR*eR/B)` for build relation scan + probe side scan
5. **Memory:** O(build_side_size) — must fit in memory; spill to disk if not
6. **Parallelism:** multi-threaded probe phase (CockroachDB, TiDB)

### index-nested-loop-join
1. **Iterate outer side** (small, filtered table)
2. **For each outer row:** lookup join key in inner table's index
3. **Bloom filter pre-check:** skip SSTables where bloom filter says "no"
4. **On match:** read full row from data block, emit joined row
5. **I/O cost per probe:** `O(L*p + ceil(e/B))` where p = bloom FPR
6. **Optimization:** batch probes via MultiGet (128-1024 keys per batch)

## implementation-notes

### lsm-specific-advantages
- **Free sort order:** primary key scans are already sorted — sort-merge needs no sort step
- **Bloom filter integration:** INLJ naturally benefits from per-SSTable bloom filters
- **Compression:** MyRocks stores 2-3.5x more compactly than InnoDB, so more data in cache
- **Sequential scan strength:** LSM merge iterators provide efficient full-table scans

### lsm-specific-disadvantages
- **CPU read amplification:** MyRocks achieves only 46-77% of InnoDB QPS on cached workloads
  due to decompression, bloom filter checks, and multi-level merging overhead
- **Point lookup cost:** each Get() may probe L levels (3-5 I/Os in practice)
- **No in-place update:** intermediate results written via WAL→MemTable→SSTable pipeline

### production-system-patterns

**CockroachDB (Pebble LSM):**
- Hash join (multi-threaded), merge join (streaming), lookup join (INLJ)
- Vectorized merge joiner: 20x improvement for COUNT, 3x for standard merge joins
- DistSQL: hash-based distribution of join inputs across nodes

**TiDB (TiKV LSM + TiFlash columnar):**
- Hash join preferred for TiFlash; index join preferred for TiKV
- Coprocessor pushdown: filters/aggregations pushed to TiKV, joins at SQL layer
- Index join: 65.6ms with index vs 313ms hash join (small build, indexed probe)

**MyRocks (RocksDB under MySQL):**
- Uses MySQL's standard join engine (transparent storage layer)
- 8-14x less write amplification than InnoDB for intermediate results
- Read performance gap: 46-77% of InnoDB QPS (CPU-bound, not I/O-bound)

**Cassandra/ScyllaDB:**
- No server-side joins — by design. Query-driven denormalization instead
- Alternatives: materialized views, client-side joins, Spark/Presto for analytics

### crossover-point-formula
When number of probe keys exceeds `N / (B * L)`, full scan is cheaper than point lookups.
For 10GB table, 4KB blocks, 5 levels: crossover at ~500K probe keys.

## complexity-analysis

### per-algorithm-cost

| Algorithm | I/O Cost | Memory | CPU |
|-----------|----------|--------|-----|
| Sort-merge | O((N+M)/B) sequential | O(1) streaming | O(N+M) comparisons |
| Hash join | O((N+M)/B) sequential | O(min(N,M)) hash table | O(N+M) hash + probe |
| INLJ | O(N * L * p) random | O(1) per probe | O(N * L) bloom checks |
| INLJ + MultiGet | O(N/batch * L) batched | O(batch_size) | Pipelined bloom checks |

## tradeoffs

### strengths
- Sort-merge is essentially free when both sides match LSM sort order
- Bloom filters make INLJ viable for selective joins (>99% true negative rate)
- MultiGet batching transforms random I/O into pipelined batched operations
- LSM compression means more data fits in cache, improving join throughput

### weaknesses
- Point lookup cost (3-5 I/Os) makes INLJ expensive for large outer sides
- CPU read amplification from decompression/bloom-checks is the primary bottleneck
- Range join predicates cannot use bloom filters at all
- Intermediate result materialization goes through full LSM write path

### compared-to-alternatives
- vs [anti-patterns](lsm-join-anti-patterns.md): what to avoid when implementing these
- vs [snapshot consistency](lsm-join-snapshot-consistency.md): how to ensure correctness

## practical-usage

### when-to-use-each
- **Sort-merge:** both sides large, sorted on join key, moderate-to-high selectivity
- **Hash join:** one side small, join key doesn't match sort order, low selectivity
- **INLJ:** outer side very small (after filters), inner side has bloom filters on join key
- **INLJ + MultiGet:** same as INLJ but outer side has 100+ rows (batch amortization)

## code-skeleton

```
class LsmJoinExecutor:
    def sort_merge_join(left_iter, right_iter, join_key):
        # Both iterators already sorted by LSM merge iterator
        while left_iter.valid() and right_iter.valid():
            cmp = compare(left_iter.key(join_key), right_iter.key(join_key))
            if cmp < 0: left_iter.next()
            elif cmp > 0: right_iter.next()
            else: yield from cross_product(left_iter, right_iter, join_key)

    def inlj_batched(outer_iter, inner_table, join_key, batch_size=256):
        batch = []
        for row in outer_iter:
            batch.append(row)
            if len(batch) >= batch_size:
                results = inner_table.multi_get([r.key(join_key) for r in batch])
                for outer_row, inner_row in zip(batch, results):
                    if inner_row is not None:
                        yield merge(outer_row, inner_row)
                batch.clear()
```

## sources

1. [Are Joins over LSM-trees Ready? (VLDB 2025)](https://arxiv.org/html/2501.16759) — 29 join methods benchmarked
2. [Vectorizing the Merge Joiner (CockroachDB)](https://www.cockroachlabs.com/blog/vectorizing-the-merge-joiner-in-cockroachdb/) — 3-20x speedup
3. [TiDB Join Explain](https://docs.pingcap.com/tidb/stable/explain-joins/) — index vs hash vs merge selection
4. [MyRocks vs InnoDB (2024)](https://smalldatum.blogspot.com/2024/07/myrocks-vs-innodb-on-cached-sysbench.html) — CPU read amplification

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

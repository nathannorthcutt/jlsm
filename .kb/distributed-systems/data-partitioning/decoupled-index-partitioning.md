---
title: "Decoupled Index Partitioning"
aliases: ["global indexes", "independent index placement", "local vs global indexes"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["partitioning", "secondary-indexes", "global-index", "local-index", "write-amplification", "rebalancing"]
complexity:
  time_build: "O(n) full index backfill; O(1) amortized per write for local, O(k) for global where k = affected index partitions"
  time_query: "O(1) routing for global; O(P) scatter-gather for local where P = partitions"
  space: "O(n) per index replica; global indexes add cross-partition metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-09"
applies_to: []
related:
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/data-partitioning/vector-search-partitioning.md"
  - "distributed-systems/data-partitioning/cross-partition-query-planning.md"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/en/database/oracle/oracle-database/18/vldbg/index-partitioning.html"
    title: "Oracle 18c: Index Partitioning"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://docs.pingcap.com/tidb/stable/global-indexes/"
    title: "TiDB Global Indexes Documentation"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://www.alexdebrie.com/posts/distributed-databases-indexes/"
    title: "How do distributed databases handle secondary indexes?"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://newsletter.scalablethread.com/p/how-indexes-work-in-partitioned-databases"
    title: "How Indexes Work in Partitioned Databases"
    accessed: "2026-04-09"
    type: "blog"
---

# Decoupled Index Partitioning

## summary

In a distributed database, tables are partitioned across nodes by a primary key
(range or hash). Indexes on non-partition columns can be **co-partitioned**
(local — each partition maintains its own index shard) or **independently
partitioned** (global — the index is partitioned by the indexed column, not
the table's partition key). The choice fundamentally affects write amplification,
read fan-out, data balance, DDL lifecycle, and rebalancing complexity. Most
production systems offer both and the decision is per-index.

## how-it-works

### local indexes (co-partitioned)

A local index is equipartitioned with its base table. Partition N's index
contains entries only for rows that live on partition N. Writes are single-node
— the index update is co-located with the row mutation.

```
Table partitioned by customer_id (range)
  Partition 1: customers [A-M]
    local index on order_date → entries for [A-M] customers only
  Partition 2: customers [N-Z]
    local index on order_date → entries for [N-Z] customers only
```

**Subtypes (Oracle terminology):**
- **Local prefixed** — partition key is a left prefix of the index key.
  Enables partition pruning on the index itself.
- **Local non-prefixed** — index key does not start with partition key.
  Every query must probe all partitions unless the WHERE clause includes
  the partition key.

### global indexes (independently partitioned)

A global index is partitioned by the indexed column(s), independently of the
table's partition key. A single index partition may contain entries pointing
to rows on many different table partitions.

```
Table partitioned by customer_id (range)
Global index on product_id (hash-partitioned by product_id)
  Index partition 1: product_ids hashing to [0-127]
    → pointers to rows on any table partition
  Index partition 2: product_ids hashing to [128-255]
    → pointers to rows on any table partition
```

**TiDB encoding:** global indexes use `TableID` (not `PartitionID`) as the key
prefix, storing `PartitionID` in the value. This keeps the index contiguous for
range scans on the indexed column while preserving a back-pointer to the owning
partition.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Index placement | Local vs global | Per-index decision | Determines write/read tradeoff |
| Partition count (P) | Number of table partitions | 10–10,000 | Local scatter-gather cost scales with P |
| Index partition count | Partitions of the global index itself | 1–P | Affects global index rebalancing |
| Sync vs async replication | How writes propagate to global index | System-dependent | Consistency vs write latency |

## algorithm-steps

### creating a local index
1. For each table partition, create a co-located index partition
2. Index entries reference only local rows — no cross-partition pointers
3. Maintenance (split, merge, drop partition) automatically adjusts the index

### creating a global index
1. Full-table scan to build index entries with back-pointers to source partitions
2. Partition the index by the indexed column (range or hash)
3. Place index partitions on nodes according to the index's own placement policy
4. On each write: atomically update both the table partition and all affected
   global index partitions (synchronous) or queue async propagation

## implementation-notes

### write path

| Operation | Local index | Global index (sync) | Global index (async) |
|-----------|-------------|---------------------|----------------------|
| Single-row INSERT | 1 node | 1 + k nodes (k = affected index partitions) | 1 node + async queue |
| Write latency | Baseline | +1–5 ms per index (CockroachDB measured) | Baseline |
| Consistency | Strong | Strong | Eventual |
| Unique constraint | Only if index key includes partition key | Supported | Not supported |

### ddl lifecycle

Local indexes are transparent to partition DDL — `DROP PARTITION`, `SPLIT`,
`MERGE` affect only the co-located index shard. Global indexes require updating
every index partition that references the affected table partition. TiDB reports
that `DROP PARTITION` and `TRUNCATE PARTITION` block until global index updates
complete.

### data-structure-requirements

- Global index entries must store both the indexed column value and a
  back-pointer (partition ID + primary key) to locate the source row
- Local index entries need only the indexed column value and the local
  primary key — the partition is implicit

### edge-cases-and-gotchas

- **Orphaned entries**: if async global index replication lags, a global index
  may temporarily point to a deleted or moved row. Readers must handle
  "row not found" gracefully.
- **Rebalancing divergence**: when the table rebalances (split/merge), local
  indexes rebalance automatically. Global indexes do not — they have their own
  partition boundaries that may become imbalanced independently.
- **Hot index partitions**: a global index hash-partitioned by a skewed column
  (e.g., a popular product_id) creates hot index shards even if the table
  itself is balanced.

## complexity-analysis

### write-phase

- Local: O(1) per index — single-node co-located write
- Global (sync): O(k) where k = number of global index partitions affected
  (typically 1 per index, but multi-column indexes may span more)
- Global (async): O(1) write + O(k) deferred propagation

### read-phase

- Local index, query without partition key: O(P) scatter-gather
- Local index, query with partition key: O(1) with pruning
- Global index, point lookup: O(1) — route directly to index partition
- Global index, range scan: O(g) where g = index partitions in range

### memory-footprint

Global indexes require additional metadata: partition-to-node mappings for
both the table and each independent index. For P table partitions and G global
indexes each with I index partitions, the router maintains O(P + G*I) entries.

## tradeoffs

### strengths

**Local indexes:**
- Zero write amplification beyond the single node
- Automatic rebalancing with table partitions
- Partition independence — DDL on one partition never touches others
- Simpler transactional model (single-node writes)

**Global indexes:**
- O(1) reads on non-partition-key columns — no scatter-gather
- Unique constraints on arbitrary columns
- Better read performance at high partition counts (avoids O(P) fan-out)
- TiDB benchmarks: up to 53x improvement in point lookups at 100 partitions

### weaknesses

**Local indexes:**
- Scatter-gather for any query not including partition key
- Tail latency grows with partition count
- Cannot enforce global uniqueness without partition key in index

**Global indexes:**
- Write amplification — every mutation touches multiple nodes
- DDL operations block on index updates
- Async mode sacrifices consistency — stale reads possible
- Independent rebalancing can diverge from table balance
- Recovery requires full index rebuild (cannot restore partial global index)

### compared-to-alternatives
- [partitioning-strategies.md](partitioning-strategies.md) — base partitioning
  models that these index strategies layer on top of
- [cross-partition-query-planning.md](cross-partition-query-planning.md) —
  how the query planner adapts to each index type

## production-systems

| System | Local Index | Global Index | Sync Model |
|--------|-------------|--------------|------------|
| Oracle | Local prefixed + non-prefixed | Global prefixed (range/hash) | Synchronous |
| TiDB | Default for partitioned tables | Opt-in, `GLOBAL` keyword | Synchronous (Percolator 2PC) |
| CockroachDB | Co-located with ranges | Sync resharded | Synchronous (parallel commit) |
| DynamoDB | LSI (same partition key) | GSI (async resharded) | Asynchronous |
| Cassandra | Scatter-gather (no reshard) | N/A | N/A |
| YugabyteDB | Co-located | Sync resharded | Synchronous |

## practical-usage

### when-to-use-local-indexes
- Write-heavy workloads where latency matters
- Queries that naturally include the partition key (time-series, tenant-scoped)
- Systems with frequent partition DDL (archival, rolling windows)
- Moderate partition counts where scatter-gather cost is acceptable

### when-to-use-global-indexes
- Read-heavy workloads on non-partition-key columns
- Unique constraints required on non-partition-key columns
- High partition counts where O(P) scatter-gather is prohibitive
- Point lookups dominate (OLTP on secondary attributes)

### when-not-to-use-global-indexes
- Write-dominated workloads — amplification erodes throughput
- Frequent partition maintenance (DROP/TRUNCATE) — DDL blocks on index updates
- Systems requiring partial index recovery — global indexes are all-or-nothing

## sources

1. [Oracle 18c: Index Partitioning](https://docs.oracle.com/en/database/oracle/oracle-database/18/vldbg/index-partitioning.html) — canonical reference for local/global taxonomy and manageability tradeoffs
2. [TiDB Global Indexes](https://docs.pingcap.com/tidb/stable/global-indexes/) — encoding scheme, benchmarks (53x improvement), DDL implications
3. [How do distributed databases handle secondary indexes?](https://www.alexdebrie.com/posts/distributed-databases-indexes/) — cross-system survey: DynamoDB, CockroachDB, Cassandra, sync vs async models
4. [How Indexes Work in Partitioned Databases](https://newsletter.scalablethread.com/p/how-indexes-work-in-partitioned-databases) — scatter-gather mechanics, two-phase global index reads

---
*Researched: 2026-04-09 | Next review: 2026-10-09*

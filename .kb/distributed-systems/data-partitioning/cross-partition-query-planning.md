---
title: "Cross-Partition Query Planning"
aliases: ["distributed query planning", "scatter-gather queries", "partition-wise joins", "cross-shard index intersection"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["query-planning", "scatter-gather", "partition-pruning", "index-intersection", "distributed-joins", "cost-model"]
complexity:
  time_build: "N/A (query planning is per-query)"
  time_query: "O(P) scatter-gather; O(1) with pruning; join cost depends on strategy"
  space: "O(P) partition metadata in planner; O(result set) for intermediate materialization"
research_status: "active"
confidence: "high"
last_researched: "2026-04-09"
applies_to: []
related:
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/data-partitioning/vector-search-partitioning.md"
  - "distributed-systems/data-partitioning/decoupled-index-partitioning.md"
  - "algorithms/vector-indexing/partitioned-fanout-queries.md"
decision_refs: []
sources:
  - url: "https://www.postgresql.org/docs/current/ddl-partitioning.html"
    title: "PostgreSQL 18: Table Partitioning"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://www.cockroachlabs.com/blog/better-sql-joins-in-cockroachdb/"
    title: "On the way to better SQL joins in CockroachDB"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://www.pingcap.com/article/mastering-query-optimization-in-distributed-sql-databases/"
    title: "Mastering Query Optimization in Distributed SQL Databases (TiDB)"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://www.alexdebrie.com/posts/distributed-databases-indexes/"
    title: "How do distributed databases handle secondary indexes?"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://docs.oracle.com/en/database/oracle/oracle-database/18/vldbg/index-partitioning.html"
    title: "Oracle 18c: Index Partitioning"
    accessed: "2026-04-09"
    type: "docs"
---

# Cross-Partition Query Planning

## summary

When table data and indexes are partitioned across nodes — especially when
independently partitioned — the query planner must decide how to route
predicates, combine index results, and execute joins without pulling all data
to a single coordinator. The core strategies are **partition pruning** (eliminate
irrelevant partitions), **scatter-gather** (fan out to all partitions and merge),
**partition-wise joins** (push joins down to co-located partitions), and
**distributed index intersection** (combine results from independently-placed
indexes). The planner's cost model must account for network round-trips, partial
result materialization, and tail latency from the slowest partition.

## how-it-works

### the fundamental problem

Consider a query: `SELECT * FROM orders WHERE product_id = 42 AND region = 'EU'`

If `orders` is range-partitioned by `customer_id`:
- A **local** index on `product_id` exists on every partition
- A **global** index on `region` is independently partitioned by region

The planner must decide:
1. Use the global `region` index → get candidate row pointers → fetch from table partitions
2. Scatter-gather the local `product_id` index across all partitions → filter by region
3. Intersect both index results → fetch only matching rows

Each strategy has different network, latency, and resource costs.

### execution strategies

```
Strategy 1: Global index probe
  ┌─────────┐     ┌──────────────┐     ┌─────────────┐
  │ Planner │────▶│ Global index │────▶│ Table       │
  │         │     │ (region=EU)  │     │ partitions  │
  └─────────┘     │ → row ptrs   │     │ (targeted)  │
                  └──────────────┘     └─────────────┘
  Cost: 1 index probe + F targeted fetches (F = matching rows)

Strategy 2: Scatter-gather on local index
  ┌─────────┐     ┌──────┐ ┌──────┐ ┌──────┐
  │ Planner │────▶│ P1   │ │ P2   │ │ P3   │  ... all P partitions
  │         │     │local │ │local │ │local │
  └─────────┘     │idx   │ │idx   │ │idx   │
                  └──┬───┘ └──┬───┘ └──┬───┘
                     └────────┴────────┘
                        merge + filter
  Cost: P parallel probes + merge + filter at coordinator

Strategy 3: Distributed index intersection
  ┌─────────┐     ┌──────────────┐     ┌──────────────────┐
  │ Planner │────▶│ Global index │     │ Scatter-gather   │
  │         │     │ (region=EU)  │     │ local product_id │
  └─────────┘     │ → set A      │     │ → set B          │
                  └──────┬───────┘     └────────┬─────────┘
                         └──────┬───────────────┘
                           intersect A ∩ B
                           fetch matching rows
  Cost: 1 global probe + P local probes + intersection + targeted fetches
```

### key-parameters

| Parameter | Description | Impact |
|-----------|-------------|--------|
| P (partition count) | Number of table partitions | Scatter-gather cost, tail latency |
| Selectivity | Fraction of rows matching predicate | Determines whether index probe beats scan |
| Co-location | Whether joined tables share partition key | Enables partition-wise join |
| Index type | Local vs global for each predicate column | Determines routing strategy |
| Network RTT | Round-trip time between nodes | Amplifies cost of multi-hop strategies |

## algorithm-steps

### partition pruning

1. **Extract partition key predicates** from WHERE clause
2. **Map predicates to partition boundaries** — range predicates map to range
   partitions; equality predicates map via hash function for hash partitions
3. **Eliminate partitions** that cannot contain matching rows
4. **Propagate pruning** to local indexes on surviving partitions

Pruning is purely predicate-driven — it does not depend on indexes existing.
A query with `WHERE customer_id BETWEEN 100 AND 200` on a range-partitioned
table prunes to partitions overlapping that range regardless of index presence.

### scatter-gather execution

1. **Plan locally** — generate a sub-plan per partition (index scan + filter)
2. **Fan out** — send sub-plans to all non-pruned partitions in parallel
3. **Execute locally** — each partition runs its sub-plan against local data
4. **Stream results** — partitions send partial results to coordinator
5. **Merge** — coordinator merges ordered streams (for ORDER BY) or unions them
6. **Apply final operators** — LIMIT, HAVING, final aggregations

### partition-wise joins

When two tables are **co-partitioned** on the join key (same partition function,
same boundaries), the join can be pushed down:

1. **Detect co-partitioning** — planner checks partition metadata for both tables
2. **Decompose** — split the join into P independent sub-joins, one per partition pair
3. **Execute** — each node joins its local partition pair (hash join, merge join, etc.)
4. **Union** — coordinator unions results (no merge needed if unordered)

```
Co-partitioned join (both tables partitioned by customer_id):
  Node 1: orders_P1 ⋈ customers_P1
  Node 2: orders_P2 ⋈ customers_P2
  ...
  Coordinator: UNION ALL results
```

This avoids shuffling data between nodes. PostgreSQL calls this "partition-wise
join" and CockroachDB implements it as "co-located join".

### distributed index intersection

When a query has predicates on multiple columns served by different indexes
on different nodes:

1. **Probe each index independently** — global index returns set A of row
   pointers; scatter-gather on local index returns set B
2. **Normalize pointers** — both sets must use the same row identifier format
   (partition ID + primary key)
3. **Intersect** — compute A ∩ B at the coordinator (hash intersection or
   sort-merge on row pointers)
4. **Fetch** — retrieve full rows only for intersection results, routed to
   the correct table partitions

**Critical cost factor**: if either index has low selectivity, the intermediate
set is large and the intersection is cheap. If both have moderate selectivity,
the intermediate sets are large and intersection becomes the bottleneck — at
that point a single scatter-gather with compound filtering may be cheaper.

### non-co-partitioned joins (shuffle join)

When tables are partitioned on different keys, the planner must **reshuffle**
one or both sides:

1. **Choose shuffle key** — typically the join column
2. **Repartition** — hash rows from each table by the join key, sending
   each row to the node that owns that hash bucket
3. **Join locally** — each node joins the reshuffled data
4. **Return results**

CockroachDB calls this "distributed hash join". It requires materializing
intermediate data across the network — O(n + m) network transfer where n and
m are the sizes of the two tables (after filtering).

## implementation-notes

### cost-model adjustments for distributed planning

A local (single-node) cost model prices CPU and I/O. A distributed planner
must additionally price:

| Cost Factor | What It Captures |
|-------------|-----------------|
| Network round-trips | Latency per hop (RTT × hops) |
| Data transfer volume | Bytes shipped between nodes for shuffles/fetches |
| Fan-out parallelism | P-way scatter-gather runs in parallel but tail latency = max(P) |
| Materialization | Intermediate results that must buffer before intersection/join |
| Coordinator bottleneck | Single node merging P streams |

TiDB's Cost Model v2 (introduced v6.2.0) uses calibrated regression to weight
these factors. CockroachDB's optimizer explicitly models "distribution cost"
as a property of physical plan alternatives.

### edge-cases-and-gotchas

- **Tail latency dominance**: scatter-gather latency = slowest partition, not
  average. With P=1000, the p99 of individual partition latency becomes the
  expected query latency. This makes scatter-gather impractical at very high
  partition counts for latency-sensitive queries.
- **Global index stale reads**: with async global indexes (DynamoDB GSI model),
  the intersection of a stale global index with a fresh local index can produce
  incorrect results (missing rows that were recently written, or pointers to
  deleted rows).
- **Skewed index intersection**: if one predicate is highly selective (returns
  10 rows) and the other is not (returns 100K rows), the planner should probe
  the selective index first and use the result as a lookup into the other —
  not intersect two large sets.
- **Partition-wise join eligibility**: tables must have identical partition
  boundaries, not just the same partition function. A table with 100 range
  partitions cannot partition-wise join with one that has 50 range partitions,
  even if both use range partitioning on the same column.

## complexity-analysis

### single-predicate queries

| Index type | Prunable? | Cost |
|------------|-----------|------|
| Local, query includes partition key | Yes | O(1) index probe |
| Local, query omits partition key | No | O(P) scatter-gather |
| Global | N/A | O(1) index probe + O(F) row fetches |

### multi-predicate index intersection

- Best case: one global index (selective) → small candidate set → targeted
  lookups. Cost: O(1) + O(F) where F is small.
- Worst case: two local indexes, no partition key in query → two O(P)
  scatter-gathers → O(R₁ + R₂) intersection at coordinator.

### join queries

| Join type | Network cost | When applicable |
|-----------|-------------|-----------------|
| Partition-wise | O(0) shuffle — join is local | Both tables co-partitioned on join key |
| Lookup join | O(R₁) targeted fetches | One side small after filtering |
| Shuffle join | O(n + m) data transfer | Non-co-partitioned, both sides large |
| Broadcast join | O(min(n,m) × P) | One side very small |

## tradeoffs

### strengths

- Partition pruning eliminates entire nodes from query execution — multiplicative
  savings
- Partition-wise joins are essentially free when tables are co-partitioned
- Global indexes turn O(P) scatter-gather into O(1) probe for point lookups
- Cost models that price network can make optimal choices between local and
  global strategies

### weaknesses

- Scatter-gather tail latency scales with partition count — fundamental limit
- Distributed index intersection requires coordinator materialization
- Non-co-partitioned joins always require data shuffling
- Independent index partitioning means the planner must reason about two
  different partition topologies simultaneously
- Async global indexes introduce consistency gaps that the planner cannot
  fully compensate for

### compared-to-alternatives
- [decoupled-index-partitioning.md](decoupled-index-partitioning.md) —
  the storage-layer decisions that create the topology this planner navigates
- [partitioning-strategies.md](partitioning-strategies.md) — base partitioning
  models that determine co-location and pruning opportunities

## practical-usage

### when-to-use-partition-wise-joins
- Tables frequently joined on the same key (orders ⋈ customers on customer_id)
- Partition both tables on the join key from the start — retrofitting is expensive
- Accept that queries joining on other columns will require shuffling

### when-to-use-global-indexes
- Query patterns are known and dominated by lookups on specific non-partition columns
- The write amplification cost is acceptable for the workload
- Strong consistency is required (rules out async GSI model for the intersection use case)

### when-to-use-scatter-gather
- Ad-hoc queries where the predicate columns are unpredictable
- Moderate partition counts (P < 100) where tail latency is manageable
- Analytics workloads where latency tolerance is high

### when-not-to-use
- Scatter-gather at P > 1000 for latency-sensitive OLTP — tail latency dominates
- Distributed index intersection when both indexes have low selectivity — full
  scan + filter may be cheaper
- Shuffle joins on large tables without filtering — consider denormalization
  or co-partitioning instead

## code-skeleton

```java
// Simplified distributed query planner decision tree
sealed interface QueryPlan permits ScatterGather, GlobalProbe, PartitionWiseJoin, ShuffleJoin {
    record ScatterGather(List<Integer> partitions, IndexScan localScan) implements QueryPlan {}
    record GlobalProbe(GlobalIndex index, Predicate predicate) implements QueryPlan {}
    record PartitionWiseJoin(QueryPlan left, QueryPlan right, JoinKey key) implements QueryPlan {}
    record ShuffleJoin(QueryPlan left, QueryPlan right, JoinKey key) implements QueryPlan {}
}

QueryPlan planSingleTable(Predicate predicate, TableMetadata table) {
    // 1. Attempt partition pruning
    var surviving = prunePartitions(predicate, table.partitionBoundaries());

    // 2. Check for global index on predicate column
    var globalIdx = table.globalIndexFor(predicate.column());
    if (globalIdx != null && predicate.isPointLookup()) {
        return new GlobalProbe(globalIdx, predicate);
    }

    // 3. Fall back to scatter-gather on surviving partitions
    var localScan = table.localIndexFor(predicate.column());
    return new ScatterGather(surviving, localScan);
}

QueryPlan planJoin(QueryPlan left, QueryPlan right, JoinKey key, TableMetadata leftTable, TableMetadata rightTable) {
    if (isCoPartitioned(leftTable, rightTable, key)) {
        return new PartitionWiseJoin(left, right, key);
    }
    return new ShuffleJoin(left, right, key);
}
```

## sources

1. [PostgreSQL 18: Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html) — partition pruning, partition-wise joins, constraint exclusion
2. [Better SQL Joins in CockroachDB](https://www.cockroachlabs.com/blog/better-sql-joins-in-cockroachdb/) — distributed join strategies, parallel execution, cost modeling
3. [Mastering Query Optimization in TiDB](https://www.pingcap.com/article/mastering-query-optimization-in-distributed-sql-databases/) — Cost Model v2, CBO with CMSketch/histograms
4. [How do distributed databases handle secondary indexes?](https://www.alexdebrie.com/posts/distributed-databases-indexes/) — scatter-gather vs resharding tradeoffs across DynamoDB, CockroachDB, Cassandra
5. [Oracle 18c: Index Partitioning](https://docs.oracle.com/en/database/oracle/oracle-database/18/vldbg/index-partitioning.html) — local prefixed/non-prefixed, global partitioned, OLTP vs DSS guidance

---
*Researched: 2026-04-09 | Next review: 2026-10-09*

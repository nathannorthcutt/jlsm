---
title: "Distributed Join Execution Strategies"
aliases: ["co-partitioned join", "broadcast join", "shuffle join", "distributed query"]
topic: "distributed-systems"
category: "query-execution"
tags: ["join", "broadcast", "shuffle", "co-partition", "semi-join", "pushdown", "distributed-query", "adaptive-query-execution", "morsel-driven", "push-based", "learned-cardinality", "disaggregated"]
complexity:
  time_build: "N/A"
  time_query: "varies by strategy"
  space: "varies by strategy"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/query-processing/lsm-join-algorithms.md"
  - "systems/query-processing/lsm-join-anti-patterns.md"
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
decision_refs: ["distributed-join-execution", "aggregation-query-merge", "limit-offset-pushdown"]
sources:
  - url: "https://thedataplatformer.substack.com/p/join-strategies-for-distributed-query"
    title: "Join Strategies for Distributed Query Engines"
    accessed: "2026-04-13"
    type: "article"
  - url: "https://howqueryengineswork.com/13-distributed-query.html"
    title: "Distributed Query Execution — How Query Engines Work"
    accessed: "2026-04-13"
    type: "book-chapter"
  - url: "https://www.yugabyte.com/blog/5-query-pushdowns-for-distributed-sql-and-how-they-differ-from-a-traditional-rdbms/"
    title: "5 Query Pushdowns for Distributed SQL (YugabyteDB)"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://sanj.dev/post/distributed-sql-databases-comparison"
    title: "Distributed SQL 2025: CockroachDB vs TiDB vs YugabyteDB"
    accessed: "2026-04-13"
    type: "comparison"
  - url: "https://dl.acm.org/doi/10.14778/3685800.3685818"
    title: "Adaptive and Robust Query Execution for Lakehouses at Scale (Xue et al.)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "http://www.vldb.org/pvldb/vol18/p5531-viktor.pdf"
    title: "Still Asking: How Good Are Query Optimizers, Really? (Leis et al.)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/article/10.1007/s00778-025-00936-6"
    title: "Simple Adaptive Query Processing vs. Learned Query Optimizers"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/2588555.2610507"
    title: "Morsel-Driven Parallelism (Leis et al., SIGMOD 2014)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.vldb.org/cidrdb/papers/2025/p2-otaki.pdf"
    title: "Resource-Adaptive Query Execution with Paged Memory Management (CIDR 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

# Distributed Join Execution Strategies

## Summary

When tables span multiple partitions on different nodes, the local join algorithms
(sort-merge, hash, INLJ — see `lsm-join-algorithms.md`) still execute the actual
join, but a **distribution layer** must first arrange for matching rows to be
co-located. The four core strategies are: co-partitioned (no movement), broadcast
(replicate small side), shuffle/repartition (hash-redistribute both sides), and
semi-join reduction (send keys first, then matching rows). Aggregation and
LIMIT/OFFSET pushdown further reduce cross-node traffic.

## How It Works

### Co-Partitioned Join (No Data Movement)

Both tables partitioned on the join key with identical function and boundaries.
Each node joins its local partitions independently — zero network cost. Only
works for equi-joins on the partition key; secondary-column joins need a
different strategy.

### Broadcast Join (Replicate Small Side)

Serialize the smaller table and send to every node. Each node builds a local hash
table and probes against its partition. Supports all predicate types.

- **Threshold**: small side must fit in per-node memory. Defaults: 10 MB (Spark);
  for jlsm, use ~25% of ArenaBufferPool per-node allocation.
- **Network cost**: `O(S * N)` where S = small table size, N = node count.

### Shuffle / Repartition Join (Hash-Redistribute Both Sides)

Hash-partition both tables on join key and redistribute so matching keys land on
the same executor: (1) hash each row's key to a target, (2) shuffle over network,
(3) local sort-merge or hash join per partition pair.

- **Network cost**: `O(|R| + |S|)` — every row crosses the network.
- **Spill**: shuffled partitions written as temporary SSTables in jlsm.
- **Skew**: hot keys concentrate on one executor; mitigate with salted keys + union.

### Semi-Join Reduction (Two-Phase Filter)

Reduces shuffle volume when many rows will not match:

1. **Phase 1**: each node sends only distinct join keys (or a bloom filter of keys)
   from its partition of table R to the coordinator.
2. **Phase 2**: coordinator distributes the key set to nodes holding table S. Each
   node filters S locally, sending only matching rows.
3. **Phase 3**: filtered S rows are shuffled to R's nodes for the final join.

Network savings are proportional to the selectivity of the join. If 90% of S rows
have no match in R, semi-join reduces shuffle by ~90%.

### Partition Pruning

The coordinator consults partition metadata (range boundaries) to eliminate
partitions that cannot contain matching rows, applied to both join sides
independently before any data movement.

### Aggregation Pushdown

Two-stage: each partition computes partial aggregates (`SUM`, `COUNT`, `MIN`,
`MAX`, local `GROUP BY` hash maps), coordinator merges. `AVG` requires
`SUM`/`COUNT` pair; `COUNT(DISTINCT)` needs HyperLogLog or full key merge.
Runs after local join phase, before results leave the partition.

### LIMIT/OFFSET Pushdown (Distributed Top-N)

Each partition returns local top-(k+m) rows sorted. Coordinator performs k-way
min-heap merge, skips m, emits k. Without pushdown partitions send all rows.
Large offsets remain expensive — keyset (cursor) pagination is preferred.

## Algorithm Steps — Strategy Selection Decision Tree

```
Is the join on the partition key of both tables?
├─ YES → Are partition boundaries identical?
│        ├─ YES → CO-PARTITIONED JOIN (zero network cost)
│        └─ NO  → SHUFFLE JOIN (repartition to align)
└─ NO  → Is one side small enough to broadcast?
         ├─ YES → BROADCAST JOIN
         └─ NO  → Estimate join selectivity
                  ├─ Low selectivity (few matches) → SEMI-JOIN REDUCTION + SHUFFLE
                  └─ High selectivity → SHUFFLE JOIN
```

## Implementation Notes — jlsm Integration

- **Local join reuse**: the distribution layer is a data-placement strategy only.
  Once rows are co-located, the existing sort-merge / hash / INLJ algorithms from
  `jlsm-core` execute unchanged.
- **LSM scan paths**: co-partitioned joins exploit the LSM sort order directly —
  if the join key matches the SSTable sort key, sort-merge join runs with no
  additional sort pass.
- **Memory budgets**: broadcast table size must respect `ArenaBufferPool` limits.
  The coordinator should check `broadcastSize <= pool.availablePerNode() * threshold`
  before choosing broadcast (suggested threshold: 0.25 of available pool memory).
- **Partition metadata**: range-partitioned LSM tables expose min/max key per
  partition via SSTable metadata. The coordinator uses this for pruning.
- **Temporary storage for shuffle**: shuffled partitions are written as temporary
  SSTables (sorted on join key) — this feeds directly into sort-merge join.

## Complexity Analysis

| Strategy | Network | Memory per Node | Latency |
|---|---|---|---|
| Co-partitioned | 0 | Local join cost | Lowest |
| Broadcast | O(S * N) | O(S) per node | Low if S small |
| Shuffle | O(\|R\| + \|S\|) | O(partition size) | High (full reshuffle) |
| Semi-join | O(keys) + O(matched S) | O(distinct keys) | Medium (two rounds) |

Aggregation pushdown: reduces coordinator input from O(rows) to O(groups).
LIMIT pushdown: reduces per-partition output from O(rows) to O(k+m).

## Tradeoffs

| Strategy | Advantage | Main Risk |
|---|---|---|
| Co-partitioned | Zero network cost | Requires identical partitioning — schema coupling |
| Broadcast | Simple, supports all predicate types | Breaks when "small" side grows or node count is high |
| Shuffle | Universal fallback | Highest network cost; skew concentrates load |
| Semi-join | Saves ~(1-selectivity) of shuffle traffic | Extra round-trip; bloom variant trades false positives for memory |

Aggregation pushdown requires algebraic decomposability — `MEDIAN`/`PERCENTILE`
need approximate algorithms or full data at the coordinator. LIMIT pushdown with
large OFFSET is still O(k+m) per partition — keyset pagination avoids this.

## Practical Usage

| System | Join Strategies | Pushdown | Co-partition Support |
|---|---|---|---|
| **CockroachDB** | Lookup (INLJ to remote ranges), merge, hash; cost-based selection | Filters to range-local reads | Implicit via range colocation |
| **TiDB** | Hash, index, merge; coprocessor pushes filters/partial aggs to TiKV | `tidb_broadcast_join_threshold_size` | Via coprocessor layer |
| **YugabyteDB** | Batched nested-loop primary; filters/aggs/LIMIT pushed to DocDB tablets | Yes (filter, agg, LIMIT) | Same partition key (hash/range) |
| **Citus** | Co-located local joins, reference-table broadcast, pull-to-coordinator fallback | Filter pushdown | First-class `DISTRIBUTED BY` |

## Code Skeleton

```java
sealed interface JoinStrategy {
    record CoPartitioned(int[] partitionIds) implements JoinStrategy {}
    record Broadcast(TableRef smallSide) implements JoinStrategy {}
    record Shuffle(HashFunction partitioner, int buckets) implements JoinStrategy {}
    record SemiJoin(TableRef probe, TableRef build) implements JoinStrategy {}
}

JoinStrategy select(JoinPlan plan, PartitionMetadata left, PartitionMetadata right) {
    var pL = prunePartitions(left, plan.leftPredicates());
    var pR = prunePartitions(right, plan.rightPredicates());

    if (plan.isEquiJoin() && pL.partitionKey().equals(plan.joinKey())
            && pR.partitionKey().equals(plan.joinKey())
            && pL.boundaries().equals(pR.boundaries()))
        return new JoinStrategy.CoPartitioned(pL.partitionIds());

    long smaller = Math.min(pL.estimatedBytes(), pR.estimatedBytes());
    if (smaller <= poolBudgetPerNode / 4)
        return new JoinStrategy.Broadcast(
                pL.estimatedBytes() < pR.estimatedBytes() ? plan.left() : plan.right());

    if (estimateSelectivity(plan, pL, pR) < 0.1)
        return new JoinStrategy.SemiJoin(plan.left(), plan.right());

    return new JoinStrategy.Shuffle(Murmur3::hash, targetBuckets(pL, pR));
}
```

## Sources

- [Join Strategies for Distributed Query Engines](https://thedataplatformer.substack.com/p/join-strategies-for-distributed-query) — strategy overview with selection criteria
- [Distributed Query Execution — How Query Engines Work](https://howqueryengineswork.com/13-distributed-query.html) — aggregation pushdown, exchange operators, stage boundaries
- [5 Query Pushdowns for Distributed SQL (YugabyteDB)](https://www.yugabyte.com/blog/5-query-pushdowns-for-distributed-sql-and-how-they-differ-from-a-traditional-rdbms/) — pushdown taxonomy for distributed SQL
- [Distributed SQL 2025: CockroachDB vs TiDB vs YugabyteDB](https://sanj.dev/post/distributed-sql-databases-comparison) — architectural comparison of NewSQL join handling

## Updates 2026-04-13

### What changed
Added frontier research on distributed query execution beyond traditional strategies.

### frontier-research

#### key-papers
| Paper | Venue | Year | Key Contribution |
|-------|-------|------|------------------|
| Adaptive and Robust Query Execution for Lakehouses at Scale (Xue et al.) | VLDB | 2024 | Fragment-based AQE: decomposes plan into stages, re-optimizes between stages using runtime statistics; switches join strategy and partition count mid-query |
| Still Asking: How Good Are Query Optimizers, Really? (Leis et al.) | VLDB | 2025 | Empirical evaluation showing static optimizers still fall short; motivates adaptive and learned approaches |
| Simple Adaptive Query Processing vs. Learned Query Optimizers (VLDB Journal) | VLDB J. | 2025 | Head-to-head comparison: simple adaptive rules (re-partition on skew, switch to broadcast) often match or beat learned optimizers on real workloads |
| Morsel-Driven Parallelism (Leis et al.) | SIGMOD | 2014 | Foundational work: fine-grained work-stealing over data morsels; NUMA-aware scheduling; elasticity without plan recompilation |
| Photon: A Fast Query Engine for Lakehouse Systems | SIGMOD | 2022 | Vectorized C++ engine with push-based pipelines; AQE layer between optimizer and distributed scheduler |
| Resource-Adaptive Query Execution with Paged Memory Management (Otaki) | CIDR | 2025 | Buffer-pool-aware execution enabling memory adjustment and lightweight query context-switching at runtime |

#### emerging-approaches

**Adaptive query execution (AQE).** Decompose a physical plan into fragments
at shuffle boundaries. Execute each fragment, collect runtime statistics
(actual row counts, partition sizes, skew), then re-optimize remaining
fragments. Concrete adaptations: convert shuffle-join to broadcast when a
materialized side is smaller than expected; split skewed partitions into
sub-partitions; coalesce too-small partitions to reduce scheduling overhead.

```
// AQE re-optimization loop (pseudocode)
fragments = decompose(physicalPlan, shuffleBoundaries)
for each fragment in topologicalOrder(fragments):
    stats = execute(fragment)
    remainingPlan = reOptimize(remainingPlan, stats)
    // e.g., if stats.bytes(buildSide) < broadcastThreshold:
    //        replace ShuffleJoin → BroadcastJoin
```

**Learned cardinality estimation.** Replace histogram-based estimators with
neural models (autoregressive, graph-based, or sample-based). Recent work
(ByteCard at ByteDance, ASM at SIGMOD 2024) shows production-grade accuracy
gains on multi-table joins. The simple-vs-learned comparison (VLDB Journal
2025) cautions that simple adaptive fallbacks remain competitive when
statistics drift — learned models require continuous retraining.

**Morsel-driven parallelism for distributed execution.** Extend the morsel
model beyond single-node NUMA to distributed clusters: a dispatcher assigns
pipeline-job + morsel pairs to workers across nodes. Elasticity is inherent —
the parallelism degree adjusts at morsel granularity without plan
recompilation. Photon and Databricks' AQE layer build on this by executing
vectorized push-based pipelines where each stage boundary is a potential
re-optimization point.

**Push-based execution.** Invert the traditional Volcano pull model: producers
push data batches (morsels) into consumer operators. Control flow moves off
the call stack into an explicit task graph, enabling: (1) cooperative
scheduling without blocking threads, (2) mid-pipeline interruption for
adaptive re-planning, (3) natural vectorized batch processing.

**Disaggregated query processing.** Separate compute (stateless executors)
from storage (object stores, LSM engines). Intermediate shuffle data lands
in a shared shuffle service rather than local disk. Benefits: independent
scaling of compute and storage, elastic executor pools, and reduced
re-shuffling on executor failure since intermediate data survives in the
shared service. Tradeoff: network latency replaces local I/O for
intermediates.

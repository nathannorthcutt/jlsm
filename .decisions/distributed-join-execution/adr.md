---
problem: "distributed-join-execution"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
depends_on: ["aggregation-query-merge", "limit-offset-pushdown"]
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Distributed Join Execution

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Distributed Join Execution Strategies | Primary — strategy taxonomy, decision tree, code skeleton | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Join Algorithm Selection & Cost Models | Local join algorithm selection | [`.kb/systems/query-processing/lsm-join-algorithms.md`](../../.kb/systems/query-processing/lsm-join-algorithms.md) |
| Join Anti-Patterns & Optimizations | Optimization checklist | [`.kb/systems/query-processing/lsm-join-anti-patterns.md`](../../.kb/systems/query-processing/lsm-join-anti-patterns.md) |
| Snapshot Consistency for Joins | Cross-table consistency model | [`.kb/systems/query-processing/lsm-join-snapshot-consistency.md`](../../.kb/systems/query-processing/lsm-join-snapshot-consistency.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Foundation — proxy table pattern this builds on |
| [scatter-backpressure](../scatter-backpressure/adr.md) | Foundation — credit budget constrains broadcast size |
| [aggregation-query-merge](../aggregation-query-merge/adr.md) | Dependency — aggregation over join results |
| [limit-offset-pushdown](../limit-offset-pushdown/adr.md) | Dependency — pagination of join results |
| [table-partitioning](../table-partitioning/adr.md) | Foundation — range partitioning provides natural sort order |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — join strategy selector, broadcast distributor

## Problem
How should joins across distributed (partitioned) tables be executed? The scatter-gather
proxy handles single-table fan-out; multi-table joins need a distribution layer that
arranges for matching rows to be co-located before local join algorithms execute.

## Constraints That Drove This Decision
- **ArenaBufferPool budget** (weight 3): broadcast table must fit within pool allocation
  per node — limits broadcast to small reference tables
- **Cross-table snapshot consistency** (weight 3): both sides of the join must see the
  same logical point in time — global sequence number binding required
- **Composability with proxy pattern** (weight 2): must reuse existing scatter-gather
  infrastructure and local join algorithms

## Decision
**Chosen approach: Co-Partitioned + Broadcast (two-tier strategy selector)**

A two-tier join strategy that covers the vast majority of real-world workloads without
shuffle complexity. The selector inspects partition metadata at query time and chooses:

1. **Co-Partitioned Join** — when both tables are partitioned on the join key with
   identical partition count and boundary alignment: each node joins its local partitions
   independently. Zero network cost. This is the primary design target.

2. **Broadcast Join** — when one side is small enough to fit within the broadcast budget:
   serialize the smaller table and distribute to every node holding a partition of the
   larger table. Each node builds a local hash table and probes against its partition.

3. **Reject** — when neither co-partitioned nor broadcast applies: return an
   `UnsupportedJoinException` with a diagnostic message explaining why (partition key
   mismatch, both sides too large for broadcast). Shuffle/repartition joins are deferred.

### Strategy Selection Decision Tree

```
Is the join an equi-join on the partition key of both tables?
├─ YES → Are partition count and boundaries identical?
│        ├─ YES → CO-PARTITIONED JOIN (zero network cost)
│        └─ NO  → Fall through to broadcast check
└─ NO  → Fall through to broadcast check

Is one side small enough for broadcast?
├─ YES → BROADCAST JOIN (smaller side)
└─ NO  → REJECT (UnsupportedJoinException)
```

### Broadcast Budget

The broadcast threshold is computed dynamically, not a static percentage:

```
broadcast_budget = pool.available() / (active_queries * safety_factor)
```

Where `safety_factor` accounts for concurrent WAL, compaction, and SSTable read
allocations. Default safety_factor = 4 (configurable via builder). The selector checks
`estimatedSize(smallerSide) <= broadcast_budget` before choosing broadcast.

### Co-Partitioned Detection (Fail-Safe)

Co-partitioned detection must be conservative. The selector verifies:
1. Same partition key column(s) — schema-level check
2. Same partition count — metadata check
3. Boundary alignment — verify each partition's [lowKey, highKey) matches its counterpart

If any check fails or is uncertain (e.g., one table is mid-rebalance), the selector
does NOT assume co-partitioned — it falls through to the broadcast check. False negatives
(falling back to broadcast when co-partitioned would work) are safe. False positives
(assuming co-partitioned when boundaries differ) would produce incorrect results.

## Rationale

### Why Co-Partitioned + Broadcast
- **Covers >90% of real-world workloads**: co-partitioned joins for co-designed tables
  (analytics, range queries), broadcast for lookup/reference table joins (user enrichment,
  configuration tables)
  ([KB: #practical-usage](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **No temporary SSTables**: both strategies operate in-memory or use existing I/O paths —
  no write amplification from shuffle
  ([KB: #complexity-analysis](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **Incremental delivery**: ship the two most valuable strategies first, add shuffle and
  semi-join later when needed. The `JoinStrategy` sealed interface supports extension.
- **Resource safety**: co-partitioned uses zero extra memory; broadcast is bounded by
  dynamic budget check. No unbounded memory growth.

### Why not Co-Partitioned Only
- **Functional limitation**: rejects all non-co-partitioned joins. Reference table lookups
  (broadcast) are a common pattern that would be unavailable. jlsm-sql would generate join
  plans that always error for non-partition-key joins.

### Why not Full Strategy Selection Framework
- **Deferred complexity**: shuffle requires temporary SSTables, disk space management,
  cleanup-on-crash logic, and cardinality estimation for semi-join. The two-tier approach
  delivers value sooner. When shuffle is needed, it can be added to the existing sealed
  interface without changing the selector's structure.

### Why not Pull-to-Coordinator
- **Hard disqualifier on Resources**: requires buffering both tables at coordinator, violating
  ArenaBufferPool budget for any non-trivial dataset.

## Implementation Guidance

**Strategy selector** from [`distributed-join-strategies.md#code-skeleton`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md):
```java
sealed interface JoinStrategy {
    record CoPartitioned(int[] partitionIds) implements JoinStrategy {}
    record Broadcast(TableRef smallSide) implements JoinStrategy {}
    // Future: record Shuffle(...) implements JoinStrategy {}
    // Future: record SemiJoin(...) implements JoinStrategy {}
}
```

**Cross-table snapshot consistency** from [`lsm-join-snapshot-consistency.md`](../../.kb/systems/query-processing/lsm-join-snapshot-consistency.md):
- Acquire a global sequence number before the join starts
- Both table scans read at that sequence number
- The scatter-backpressure ADR's continuation tokens encode the sequence number

**Broadcast distribution:**
- Coordinator scans the smaller table completely (via proxy)
- Serializes result into a compact payload
- Distributes to all nodes holding partitions of the larger table
- Each node deserializes into a local hash table, probes against its partition
- Payload must fit within `broadcast_budget` — check before scan

**Local join algorithm selection** from [`lsm-join-algorithms.md`](../../.kb/systems/query-processing/lsm-join-algorithms.md):
- Co-partitioned + equi-join on sort key → sort-merge (free sort from LSM order)
- Broadcast → hash join (build hash table from broadcast side, probe from large side)
- Future shuffle → sort-merge (shuffled partitions written as sorted SSTables)

Known edge cases from [`lsm-join-anti-patterns.md`](../../.kb/systems/query-processing/lsm-join-anti-patterns.md):
- INLJ without MultiGet batching is 5-10x slower — batch point lookups when using broadcast
- Bloom filters useless for range predicates — fall back to sort-merge for range joins
- Tombstone-heavy tables inflate scan cost — factor into broadcast size estimation

## What This Decision Does NOT Solve
- Shuffle/repartition joins for large non-co-partitioned tables — the most general fallback
  strategy, deferred until needed
- Semi-join reduction for low-selectivity joins — optimization on top of shuffle, deferred
- Query planner integration — how jlsm-sql's query translator selects and invokes join
  strategies is a separate concern
- Join ordering optimization for multi-way joins — requires cost-based planner

## Conditions for Revision
This ADR should be re-evaluated if:
- Non-co-partitioned large-table joins become a common use case and the reject path is
  unacceptable — add shuffle strategy
- Broadcast budget proves too conservative — may need spill-to-disk broadcast
- jlsm-sql generates join plans that require shuffle — the two-tier selector would need
  extension
- Multi-way joins enter scope — may need query planner layer above the strategy selector

---
*Confirmed by: user pre-accepted all changes | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*

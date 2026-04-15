---
problem: "aggregation-query-merge"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Aggregation Query Merge

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Distributed Join Execution Strategies | Chosen approach (aggregation pushdown section) | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Distributed Scan Cursor Management | Backpressure context (credit model) | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Parent — aggregation extends the proxy's merge phase |
| [scatter-backpressure](../scatter-backpressure/adr.md) | Foundation — credit budget constrains coordinator memory |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` �� proxy aggregation merge logic

## Problem
How should the scatter-gather proxy merge aggregation results (COUNT, SUM, AVG, MIN,
MAX, GROUP BY) from per-partition execution into a single result for the caller, within
the ArenaBufferPool credit budget and the existing Table interface contract?

## Constraints That Drove This Decision
- **ArenaBufferPool credit budget** (weight 3): coordinator memory must be bounded — no
  unbounded accumulation of partition results
- **Exact results** (weight 3): algebraic aggregates must produce identical results whether
  the query runs on 1 partition or 100
- **Table interface transparency** (weight 2): callers use the same API regardless of
  aggregation type — no special distributed aggregation API

## Decision
**Chosen approach: Two-Phase Partial Aggregation with Cardinality Guard**

Each partition computes partial aggregates locally (SUM, COUNT, MIN, MAX, per-group hash
maps for GROUP BY), then sends the compact partial results to the coordinator. The
coordinator merges partials — a simple combine step that reduces O(total_rows) network
traffic to O(groups). AVG is computed as SUM/COUNT at the coordinator. A cardinality
guard monitors GROUP BY group count: if partials would exceed the credit budget, the
coordinator falls back to sorted-run merge aggregation (streaming k-way merge exploiting
LSM sort order).

### Scope
- **In scope:** COUNT, SUM, MIN, MAX, AVG, GROUP BY with these aggregates
- **Out of scope:** DISTINCT aggregates (COUNT(DISTINCT), SUM(DISTINCT)) — cannot be
  decomposed into per-partition partials without approximation or full dedup. Deferred.
- **Out of scope:** Holistic aggregates (MEDIAN, PERCENTILE) — require full data at
  coordinator. Deferred.

### Cardinality Guard
The coordinator estimates GROUP BY cardinality before selecting the merge strategy:

```
max_groups = credit_budget / (P * per_group_bytes)
```

If estimated group count exceeds `max_groups`, the coordinator uses sorted-run merge
aggregation instead of partial-aggregate accumulation. The threshold is configurable
via builder. This prevents high-cardinality GROUP BY from exceeding the memory budget.

### Merge Functions

| Aggregate | Partial Type | Merge Operation |
|-----------|-------------|-----------------|
| COUNT     | long        | SUM of partials |
| SUM       | long/double | SUM of partials |
| MIN       | Comparable  | MIN of partials |
| MAX       | Comparable  | MAX of partials |
| AVG       | (sum, count) pair | final = total_sum / total_count |

## Rationale

### Why Two-Phase Partial Aggregation
- **Bounded memory**: for scalar aggregates and low-cardinality GROUP BY, coordinator holds
  O(groups) not O(rows). At P=1000 partitions with 1000 groups, coordinator holds 1000 merge
  entries — trivially within credit budget
  ([KB: #aggregation-pushdown](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **Minimal network traffic**: sends partials (one tuple per group per partition) instead of
  full result sets. Reduces coordinator input from O(rows) to O(groups)
  ([KB: #complexity-analysis](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **Standard pattern**: implemented by CockroachDB, TiDB, YugabyteDB — well-understood,
  battle-tested ([KB: #practical-usage](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **Cardinality guard**: addresses the falsification finding that high-cardinality GROUP BY
  degrades to near-full-materialization — the guard falls back to streaming before exceeding
  the memory budget

### Why not Full Materialization at Coordinator
- **Hard disqualifier on Resources**: requires buffering all rows at coordinator, violating
  ArenaBufferPool credit budget for any non-trivial dataset

### Why not Streaming Aggregate Merge (pure streaming, no partial pushdown)
- **Higher network cost**: processes all rows through coordinator — O(total_rows) network
  traffic vs O(groups) for two-phase. At P=1000 with 1M rows per partition and 1000 groups,
  streaming sends 1B rows vs two-phase sending 1M partials

## Implementation Guidance

**Partition-side execution:**
1. Receive aggregation query from coordinator
2. Execute local scan with predicates (reuse existing query path)
3. Accumulate results into per-group aggregator (hash map keyed by GROUP BY columns)
4. Serialize partial aggregates into a single page response
5. If no GROUP BY: single-tuple response (one partial per aggregate function)

**Coordinator-side merge:**
1. Fan out aggregation query to partitions (reuse scatter-gather proxy)
2. As partial responses arrive via Flow API:
   - Scalar (no GROUP BY): accumulate running merge — O(1) memory
   - GROUP BY: merge into coordinator hash map — O(groups) memory
3. After all partitions respond (or timeout with partial results):
   - Compute derived aggregates (AVG = SUM/COUNT)
   - Emit final result set

**Cardinality guard activation:**
- Before accumulating GROUP BY partials, check if estimated cardinality × P × per_group_bytes
  exceeds `credit_budget * guard_threshold` (default threshold: 0.5)
- If exceeded: switch to sorted-run merge — request partitions to return sorted partial
  streams, coordinator performs k-way merge aggregation with O(P) memory

**Partial result semantics:**
- If a partition times out: coordinator emits available partials with
  PartialResultMetadata (from scatter-gather ADR) indicating which partitions are missing
- Callers can decide whether partial aggregates are acceptable

Key parameters from [`distributed-join-strategies.md#aggregation-pushdown`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md):
- Partial aggregate size: ~8-16 bytes per aggregate function per group
- Cardinality guard threshold: 0.5 of credit budget (configurable)
- Sorted-merge fallback: requires GROUP BY key to align with partition sort order

## What This Decision Does NOT Solve
- DISTINCT aggregates (COUNT(DISTINCT), SUM(DISTINCT)) — require full dedup or approximation
- Holistic aggregates (MEDIAN, PERCENTILE, MODE) — require full data or approximate algorithms
- Aggregation over join results — requires join execution (separate ADR: distributed-join-execution)
- Adaptive cardinality estimation — guard uses static threshold; dynamic estimation deferred

## Conditions for Revision
This ADR should be re-evaluated if:
- DISTINCT aggregates become a primary use case — may need HyperLogLog integration
- Holistic aggregates (MEDIAN, PERCENTILE) enter scope — may need t-digest or full materialization
- Cardinality guard threshold proves too conservative or too aggressive in practice
- GROUP BY key frequently does not align with partition sort order — sorted-merge fallback degrades

---
*Confirmed by: user pre-accepted all changes | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*

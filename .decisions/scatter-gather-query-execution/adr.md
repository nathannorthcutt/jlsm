---
problem: "scatter-gather-query-execution"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Scatter-Gather Query Execution

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (general knowledge) | No direct KB entry — standard distributed query pattern | — |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [engine-api-surface-design](../engine-api-surface-design/adr.md) | Proxy implements the Table interface from this ADR |
| [table-partitioning](../table-partitioning/adr.md) | Range partitions provide natural ordering for k-way merge |
| [partition-to-node-ownership](../partition-to-node-ownership/adr.md) | HRW provides partition → node lookup for routing |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — proxy table and merge logic

## Problem
How should queries on partitioned tables fan out to partition owners and merge results, including handling partial results when some owners are unavailable?

## Constraints That Drove This Decision
- **Table interface transparency**: callers should not need to know whether a table is local or distributed — the same Table interface must work for both
- **Result correctness**: merged results must preserve global ordering from range partitions without an explicit sort step
- **Partial result transparency**: callers must know which partitions were unavailable

## Decision
**Chosen approach: Partition-Aware Proxy Table**

A clustered table implements the same Table interface as a local table. The proxy transparently scatters queries to partition owners via the transport abstraction, gathers results using a streaming k-way merge iterator that exploits range partition ordering, and attaches PartialResultMetadata to the response indicating any unavailable partitions. Callers cannot distinguish a clustered table from a local one.

**Partition pruning**: the proxy inspects query predicates before scattering. Point lookups route to one partition. Range scans route only to partitions whose key ranges overlap the query bounds. Non-key predicates require full fan-out. Pruning is O(log P) — a binary search on partition boundaries.

**Write routing**: PUT, DELETE, and other mutations route to the single partition owner identified by the key. No scatter needed.

## Rationale

### Why Partition-Aware Proxy Table
- **Interface transparency**: implements Table — callers use `table.scan()`, `table.query()` identically for local and clustered tables (composes with engine-api-surface-design ADR)
- **Streaming k-way merge**: range partitions return naturally ordered results; k-way merge produces globally ordered output in O(total_results * log P) without buffering everything in memory
- **Partition pruning**: key-bounded queries skip irrelevant partitions — O(log P) routing instead of full fan-out
- **Partial results**: PartialResultMetadata explicitly tracks which partitions were unavailable, what key ranges are missing

### Why not Coordinator Node Scatter-Gather
- Introduces a separate "coordinator" concept outside the Table interface, breaking the handle pattern from engine-api-surface-design. Functionally equivalent to the proxy but with a worse API fit.

### Why not Client-Side Fan-Out
- Pushes merge correctness to every caller — duplicated effort, high bug risk. Breaks the Table abstraction entirely.

## Implementation Guidance

**Proxy query flow:**
1. Receive query on proxy Table
2. Inspect predicates for key bounds → prune partition list
3. Look up partition owners via HRW
4. Send sub-queries concurrently via transport abstraction (with per-partition timeout)
5. As results arrive, feed into k-way merge iterator
6. If a partition times out, record it in PartialResultMetadata, continue with remaining
7. Return merged iterator + metadata to caller

**K-way merge**: use a min-heap of partition iterators, keyed by the current entry's key. Poll the minimum, advance that iterator. O(log P) per result entry.

**LIMIT/OFFSET**: request LIMIT from each partition, merge, trim to global LIMIT. Over-fetches by up to P * LIMIT in the worst case. Partition pruning reduces P significantly for key-bounded queries.

**PartialResultMetadata**:
```java
record PartialResultMetadata(
    Set<PartitionId> unavailablePartitions,
    boolean isComplete  // true if all partitions responded
) {}
```

## What This Decision Does NOT Solve
- Distributed joins across tables (deferred: distributed-join-execution)
- Aggregation query merge strategies (COUNT, SUM need per-partition execution + merge)
- LIMIT/OFFSET optimization beyond over-fetching (e.g., partition-aware pushdown)
- Cross-partition transactions or atomic multi-partition writes

## Conditions for Revision
This ADR should be re-evaluated if:
- Distributed joins enter scope — may need a query planner layer above the proxy (see deferred: distributed-join-execution)
- Aggregation queries become a primary use case and the over-fetch approach is too wasteful
- The Table interface proves insufficient for expressing distributed query semantics (partial results, timeouts)

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*

---
problem: "Distributed join execution strategy for partitioned tables"
slug: "distributed-join-execution"
captured: "2026-04-14"
status: "final"
---

# Constraint Profile — distributed-join-execution

## Problem Statement
How should joins across distributed (partitioned) tables be executed? The scatter-gather
proxy handles single-table fan-out. Multi-table joins require a distribution layer that
arranges for matching rows to be co-located before local join algorithms (sort-merge,
hash, INLJ from jlsm-core) execute the join. The strategy must compose with the existing
proxy pattern, ArenaBufferPool credit budget, and Table interface transparency.

## Constraints

### Scale
Variable table sizes and partition counts. Join workloads range from small lookup joins
(point join against a reference table) to large analytical joins (two large tables). The
solution must handle both gracefully. Co-partitioned joins (same partition key) are the
common case that must be optimized.

### Resources
Bounded by ArenaBufferPool. Broadcast joins require holding the small table in memory per
node — this must respect the pool budget. Shuffle joins create temporary SSTables —
disk-backed but write-amplified. No external dependencies.

### Complexity Budget
Library internals — implementation complexity unconstrained. The public API must be simple:
callers call `join(leftTable, rightTable, predicate)` or equivalent. The strategy selection
should be automatic based on partition metadata.

### Accuracy / Correctness
Join results must be semantically correct: every matching pair appears exactly once, no
duplicates, no missed matches. Cross-table snapshot consistency required — both sides of the
join must see the same logical point in time (from lsm-join-snapshot-consistency KB entry).
Partial results (from unavailable partitions) must be flagged, not silently omitted.

### Operational Requirements
Must compose with:
- Scatter-gather proxy pattern (scatter-gather-query-execution ADR)
- Credit-based Flow API backpressure (scatter-backpressure ADR)
- Continuation token pagination (limit-offset-pushdown ADR)
- Aggregation merge (aggregation-query-merge ADR)

### Fit
Java 25, JPMS. Local join algorithms (sort-merge, hash, INLJ) already exist in jlsm-core.
The distribution layer sits above the proxy tables and below jlsm-sql. Range partitioning
provides natural key ordering — sort-merge is "free" when the join key matches the partition
key.

## Key Constraints (most narrowing)
1. **ArenaBufferPool budget for broadcast** — broadcast table must fit within pool allocation
   per node, limiting broadcast to small tables
2. **Cross-table snapshot consistency** — both join sides must see same logical snapshot
3. **Composability with proxy pattern** — must reuse existing scatter-gather infrastructure

## Unknown / Not Specified
None — full profile captured.

## Constraint Falsification — 2026-04-14
Checked: scatter-gather ADR, scatter-backpressure ADR, aggregation-query-merge ADR,
limit-offset-pushdown ADR, F04 (engine-clustering), F30 (partition-data-operations),
distributed-join-strategies.md, lsm-join-snapshot-consistency.md.
- Accuracy: lsm-join-snapshot-consistency.md implies global sequence number for cross-table
  reads. Confirmed as constraint.
- Resources: scatter-backpressure ADR's credit model constrains concurrent join execution.
  Confirmed.
- No additional implied constraints found.

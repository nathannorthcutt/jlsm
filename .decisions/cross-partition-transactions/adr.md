---
problem: "cross-partition-transactions"
date: "2026-03-30"
version: 1
status: "deferred"
merged: ["cross-partition-atomic-writes"]
---

# Cross-Partition Transaction Coordination — Deferred

## Problem
Atomic multi-partition operations — transaction coordination (2PC, Calvin,
Percolator) and atomic writes across partitions. This includes both the
coordination protocol and the write-path guarantees.

## Why Deferred
Scoped out independently by two parent decisions:
- `table-partitioning` — "Cross-partition transaction coordination" (separate decision needed)
- `scatter-gather-query-execution` — "Cross-partition transactions or atomic multi-partition writes"

Both describe the same fundamental problem: ensuring atomicity when an operation
spans multiple partitions.

## Resume When
When both `table-partitioning` and `scatter-gather-query-execution`
implementations are stable and cross-partition atomicity becomes blocking.

## What Is Known So Far
- See `.decisions/table-partitioning/adr.md` — partition model and data placement
- See `.decisions/scatter-gather-query-execution/adr.md` — query routing and
  result merging across partitions
- The partition model is range-based with per-partition co-located indices
- The query layer uses scatter-gather with partition pruning

## Merged From
- `cross-partition-atomic-writes` (deferred 2026-03-30, parent: scatter-gather-query-execution)
  — merged 2026-04-12 during roadmap consolidation

## Next Step
Run `/architect "cross-partition-transactions"` when ready to evaluate.

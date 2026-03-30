---
problem: "vector-query-partition-pruning"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Vector Query Partition Pruning at Scale — Deferred

## Problem
Vector query optimization at 1000+ partitions — needs partition-level metadata for skip logic to avoid scanning all partitions.

## Why Deferred
Scoped out during `table-partitioning` decision. Not needed at current scale.

## Resume When
When `table-partitioning` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-partitioning/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "vector-query-partition-pruning"` when ready to evaluate.

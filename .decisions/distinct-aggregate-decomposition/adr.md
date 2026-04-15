---
problem: "distinct-aggregate-decomposition"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Distinct Aggregate Decomposition — Deferred

## Problem
DISTINCT aggregates (COUNT(DISTINCT), SUM(DISTINCT)) cannot be decomposed into per-partition partials without approximation or full deduplication. How should the distributed query layer handle DISTINCT aggregates?

## Why Deferred
Scoped out during `aggregation-query-merge` decision. DISTINCT aggregates require either HyperLogLog (approximate) or full key merge (expensive), both of which are fundamentally different from the two-phase partial approach chosen for algebraic aggregates.

## Resume When
When DISTINCT aggregates become a primary use case and the current "not supported" status is blocking.

## What Is Known So Far
Identified during architecture evaluation of `aggregation-query-merge`. See `.decisions/aggregation-query-merge/adr.md` for the architectural context. The KB entry at `.kb/distributed-systems/query-execution/distributed-join-strategies.md` notes that COUNT(DISTINCT) needs HyperLogLog or full key merge.

## Next Step
Run `/architect "distinct-aggregate-decomposition"` when ready to evaluate.

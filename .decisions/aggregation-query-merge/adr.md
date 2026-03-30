---
problem: "aggregation-query-merge"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Aggregation Query Merge Strategies — Deferred

## Problem
Aggregation query merge strategies — COUNT, SUM, AVG need per-partition execution with results merged at the coordinator.

## Why Deferred
Scoped out during `scatter-gather-query-execution` decision. Aggregation needs per-partition execution + merge, beyond current scatter-gather.

## Resume When
When `scatter-gather-query-execution` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/scatter-gather-query-execution/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "aggregation-query-merge"` when ready to evaluate.

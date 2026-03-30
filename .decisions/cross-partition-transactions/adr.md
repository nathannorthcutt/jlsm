---
problem: "cross-partition-transactions"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Cross-Partition Transaction Coordination — Deferred

## Problem
Cross-partition transaction coordination (2PC, Calvin, Percolator) for atomic multi-partition operations.

## Why Deferred
Scoped out during `table-partitioning` decision. Separate decision needed.

## Resume When
When `table-partitioning` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-partitioning/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "cross-partition-transactions"` when ready to evaluate.

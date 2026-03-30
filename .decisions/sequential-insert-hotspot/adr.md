---
problem: "sequential-insert-hotspot"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Sequential Insert Hotspot Mitigation — Deferred

## Problem
Sequential insert hotspot mitigation — needs compound partition key design to distribute monotonic inserts across partitions.

## Why Deferred
Scoped out during `table-partitioning` decision. Needs compound partition key design.

## Resume When
When `table-partitioning` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-partitioning/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "sequential-insert-hotspot"` when ready to evaluate.

---
problem: "cross-partition-atomic-writes"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["cross-partition-transactions"]
---

# Cross-Partition Atomic Writes — Deferred

## Problem
Cross-partition transactions or atomic multi-partition writes.

## Why Deferred
Scoped out during `scatter-gather-query-execution` decision. Separate concern from query execution.

## Resume When
When `scatter-gather-query-execution` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/scatter-gather-query-execution/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "cross-partition-atomic-writes"` when ready to evaluate.

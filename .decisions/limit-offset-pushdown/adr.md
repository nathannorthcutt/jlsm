---
problem: "limit-offset-pushdown"
date: "2026-03-30"
version: 1
status: "deferred"
---

# LIMIT/OFFSET Partition Pushdown — Deferred

## Problem
LIMIT/OFFSET optimization beyond over-fetching — partition-aware pushdown to reduce data transfer.

## Why Deferred
Scoped out during `scatter-gather-query-execution` decision. Over-fetching is sufficient for initial implementation.

## Resume When
When `scatter-gather-query-execution` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/scatter-gather-query-execution/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "limit-offset-pushdown"` when ready to evaluate.

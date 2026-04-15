---
problem: "hierarchical-query-memory-budget"
date: "2026-04-13"
version: 1
status: "deferred"
---

# Hierarchical Query Memory Budget — Deferred

## Problem
Should the scatter-gather coordinator use hierarchical memory budgeting (Presto-style
MemoryContext: operator → query → pool) to manage memory across concurrent queries?

## Why Deferred
Scoped out during `scatter-backpressure` decision. Per-query credit budgeting is sufficient
for v1. Hierarchical budgeting adds complexity without proven need.

## Resume When
When concurrent query count exceeds credit budget capacity, or when query-level memory
isolation proves insufficient in production.

## What Is Known So Far
Presto/Trino use hierarchical MemoryContext tracking with cooperative blocking and
query-kill as backstop. The KB article on scatter-gather backpressure describes this model.
See `.decisions/scatter-backpressure/adr.md` for context.

## Next Step
Run `/architect "hierarchical-query-memory-budget"` when ready to evaluate.

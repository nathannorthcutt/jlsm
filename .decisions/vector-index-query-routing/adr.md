---
problem: "vector-index-query-routing"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["vector-storage-cost-optimization"]
---

# Vector Index Query Routing — Deferred

## Problem
When multiple vector indices exist on the same field with different quantization (e.g., SQ8 for fast scan, flat for high-recall reranking), how does the query engine choose which index to use?

## Why Deferred
Scoped out during `vector-storage-cost-optimization` decision. IndexRegistry single-dispatch returns the first matching index — no routing mechanism exists.

## Resume When
When multiple quantization levels are implemented and query routing between them is needed.

## What Is Known So Far
IndexRegistry.findIndex() returns the first matching index for a predicate. Two VECTOR indices on the same field both pass validation but only the first is used. The routing mechanism must be added to QueryExecutor or exposed as a query hint.

## Next Step
Run `/architect "vector-index-query-routing"` when ready to evaluate.

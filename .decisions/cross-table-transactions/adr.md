---
problem: "cross-table-transactions"
date: "2026-03-30"
version: 1
status: "deferred"
merged: ["cross-table-transaction-coordination"]
---

# Cross-Table Transaction Coordination — Deferred

## Problem
Transaction coordination across tables — ensuring atomicity and consistency
when operations span multiple tables in the engine. This includes both the
engine-level API contract and the catalog-level coordination mechanism.

## Why Deferred
Scoped out independently by two parent decisions:
- `engine-api-surface-design` — "Transaction coordination across tables"
- `table-catalog-persistence` — "Cross-table transaction coordination"

Both describe the same fundamental problem: ensuring atomicity when an operation
spans multiple tables.

## Resume When
When both `engine-api-surface-design` and `table-catalog-persistence`
implementations are stable and cross-table atomicity becomes blocking.

## What Is Known So Far
- See `.decisions/engine-api-surface-design/adr.md` — handle-based API surface
  with tracked lifecycle and lease eviction
- See `.decisions/table-catalog-persistence/adr.md` — per-table metadata
  directories with lazy recovery
- The engine currently provides per-table isolation only
- The catalog uses per-table metadata directories (no shared transaction log)

## Merged From
- `cross-table-transaction-coordination` (deferred 2026-03-30, parent: table-catalog-persistence)
  — merged 2026-04-12 during roadmap consolidation

## Next Step
Run `/architect "cross-table-transactions"` when ready to evaluate.

---
problem: "cross-table-transaction-coordination"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["cross-table-transactions"]
---

# Cross-Table Transaction Coordination — Deferred

## Problem
Cross-table transaction coordination for consistent multi-table operations.

## Why Deferred
Scoped out during `table-catalog-persistence` decision. Separate concern from catalog persistence.

## Resume When
When `table-catalog-persistence` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-catalog-persistence/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "cross-table-transaction-coordination"` when ready to evaluate.

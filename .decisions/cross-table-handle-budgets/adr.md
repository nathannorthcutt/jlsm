---
problem: "cross-table-handle-budgets"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Cross-Table Handle Budgets — Deferred

## Problem
Cross-table handle budgets — per-table limits only for now, no global cross-table budget.

## Why Deferred
Scoped out during `engine-api-surface-design` decision. Per-table limits only for now.

## Resume When
When `engine-api-surface-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/engine-api-surface-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "cross-table-handle-budgets"` when ready to evaluate.

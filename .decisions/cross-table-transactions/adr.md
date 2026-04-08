---
problem: "cross-table-transactions"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Cross-Table Transaction Coordination — Deferred

## Problem
Transaction coordination across tables in the engine.

## Why Deferred
Scoped out during `engine-api-surface-design` decision. Separate concern from engine API surface.

## Resume When
When `engine-api-surface-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/engine-api-surface-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "cross-table-transactions"` when ready to evaluate.

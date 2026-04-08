---
problem: "handle-priority-levels"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Handle Priority Levels — Deferred

## Problem
Handle priority levels — all sources are currently equal; greediest gets evicted. May need priority tiers.

## Why Deferred
Scoped out during `engine-api-surface-design` decision. All sources are equal for now; greediest gets evicted.

## Resume When
When `engine-api-surface-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/engine-api-surface-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "handle-priority-levels"` when ready to evaluate.

---
problem: "scatter-backpressure"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Scatter Backpressure — Deferred

## Problem
Backpressure on high-volume scatter operations to prevent overwhelming target nodes.

## Why Deferred
Scoped out during `transport-abstraction-design` decision. Implementation concern, not transport abstraction concern.

## Resume When
When `transport-abstraction-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/transport-abstraction-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "scatter-backpressure"` when ready to evaluate.

---
problem: "weighted-node-capacity"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Weighted Node Capacity — Deferred

## Problem
Weighted/heterogeneous node capacity — simple extension (multiply hash by weight) but not built-in initially.

## Why Deferred
Scoped out during `partition-to-node-ownership` decision. Not built-in initially; simple extension available.

## Resume When
When `partition-to-node-ownership` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/partition-to-node-ownership/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "weighted-node-capacity"` when ready to evaluate.

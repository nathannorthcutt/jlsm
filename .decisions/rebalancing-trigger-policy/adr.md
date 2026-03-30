---
problem: "rebalancing-trigger-policy"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Rebalancing Trigger Policy — Deferred

## Problem
The decision of when to trigger rebalancing — currently handled by the membership protocol's grace period.

## Why Deferred
Scoped out during `partition-to-node-ownership` decision. Handled by the membership protocol's grace period.

## Resume When
When `partition-to-node-ownership` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/partition-to-node-ownership/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "rebalancing-trigger-policy"` when ready to evaluate.

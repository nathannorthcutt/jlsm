---
problem: "partition-takeover-priority"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Partition Takeover Priority Ordering — Deferred

## Problem
Priority ordering of partition takeover — which partitions replay first on the new owner.

## Why Deferred
Scoped out during `rebalancing-grace-period-strategy` decision. Not needed for initial implementation.

## Resume When
When `rebalancing-grace-period-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/rebalancing-grace-period-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "partition-takeover-priority"` when ready to evaluate.

---
problem: "membership-view-stall-recovery"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Membership View Stall Recovery — Deferred

## Problem
View changes stall if >25% of nodes are simultaneously unreachable. No recovery mechanism exists for this scenario.

## Why Deferred
Scoped out during `cluster-membership-protocol` decision. Known limitation of the Rapid protocol's 75% agreement threshold.

## Resume When
When `cluster-membership-protocol` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/cluster-membership-protocol/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "membership-view-stall-recovery"` when ready to evaluate.

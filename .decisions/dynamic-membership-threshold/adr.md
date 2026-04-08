---
problem: "dynamic-membership-threshold"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Dynamic Membership Threshold — Deferred

## Problem
Dynamic adjustment of the 75% agreement threshold as expected cluster size changes.

## Why Deferred
Scoped out during `cluster-membership-protocol` decision. Fixed threshold is simpler for initial implementation.

## Resume When
When `cluster-membership-protocol` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/cluster-membership-protocol/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "dynamic-membership-threshold"` when ready to evaluate.

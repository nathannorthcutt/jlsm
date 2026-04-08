---
problem: "slow-node-detection"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Slow Node Detection — Deferred

## Problem
Gradual performance degradation detection — distinguishing slow nodes from dead nodes. Phi accrual helps at the edge level but cut detection is binary.

## Why Deferred
Scoped out during `cluster-membership-protocol` decision. Phi accrual helps at the edge level but cut detection is binary.

## Resume When
When `cluster-membership-protocol` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/cluster-membership-protocol/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "slow-node-detection"` when ready to evaluate.

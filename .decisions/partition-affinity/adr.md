---
problem: "partition-affinity"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Partition Affinity — Deferred

## Problem
Partition affinity — co-locating related partitions on the same node for locality.

## Why Deferred
Scoped out during `partition-to-node-ownership` decision. Not built-in initially.

## Resume When
When `partition-to-node-ownership` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/partition-to-node-ownership/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "partition-affinity"` when ready to evaluate.

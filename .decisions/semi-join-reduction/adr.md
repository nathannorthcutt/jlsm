---
problem: "semi-join-reduction"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Semi-Join Reduction — Deferred

## Problem
Semi-join reduction for low-selectivity distributed joins — send a key set from one side
to filter the other side before shuffling matched rows, reducing data movement when only
a small fraction of rows will participate in the final join.

## Why Deferred
This is an optimization on top of shuffle/repartition joins, which are themselves deferred.
Implementing semi-join reduction before the underlying shuffle strategy is in place has no
value.

## Resume When
Shuffle/repartition joins (`shuffle-repartition-joins`) are implemented and profiling shows
that low-selectivity joins are producing excessive data movement that warrants a pre-filter
step.

## What Is Known So Far
See `.decisions/distributed-join-execution/adr.md` for the architectural context and
`.decisions/shuffle-repartition-joins/adr.md` for the prerequisite strategy this
optimization depends on.

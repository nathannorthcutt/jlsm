---
problem: "holistic-aggregate-support"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Holistic Aggregate Support — Deferred

## Problem
Holistic aggregates (MEDIAN, PERCENTILE, MODE) cannot be decomposed into per-partition partials. They require full data at the coordinator or approximate algorithms (t-digest, quantile sketches).

## Why Deferred
Scoped out during `aggregation-query-merge` decision. Holistic aggregates are fundamentally incompatible with the two-phase partial approach and require either full materialization or approximate streaming algorithms.

## Resume When
When MEDIAN, PERCENTILE, or similar holistic aggregates enter scope.

## What Is Known So Far
Identified during architecture evaluation of `aggregation-query-merge`. See `.decisions/aggregation-query-merge/adr.md`. The KB notes that holistic aggregates need approximate algorithms or full data at the coordinator.

## Next Step
Run `/architect "holistic-aggregate-support"` when ready to evaluate.

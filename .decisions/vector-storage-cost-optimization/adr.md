---
problem: "vector-storage-cost-optimization"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Vector Storage Cost Optimization — Deferred

## Problem
Storage cost optimization at billion-scale — flat encoding stores every element at full precision.

## Why Deferred
Scoped out during `vector-type-serialization-encoding` decision. Flat encoding at full precision is sufficient for current scale.

## Resume When
When `vector-type-serialization-encoding` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/vector-type-serialization-encoding/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "vector-storage-cost-optimization"` when ready to evaluate.

---
problem: "sparse-vector-support"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Sparse Vector Support — Deferred

## Problem
Sparse vector use cases — VectorType is for dense fixed-dimension vectors only. Sparse representations need a different encoding.

## Why Deferred
Scoped out during `vector-type-serialization-encoding` decision. VectorType is designed for dense fixed-dimension vectors.

## Resume When
When `vector-type-serialization-encoding` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/vector-type-serialization-encoding/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "sparse-vector-support"` when ready to evaluate.

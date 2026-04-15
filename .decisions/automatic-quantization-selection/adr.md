---
problem: "automatic-quantization-selection"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["vector-storage-cost-optimization"]
---

# Automatic Quantization Selection — Deferred

## Problem
Automatically choosing the right quantization scheme based on data characteristics (dimension count, value distribution, recall requirements) instead of requiring the caller to specify.

## Why Deferred
Scoped out during `vector-storage-cost-optimization` decision. Requires benchmarking data across schemes to build a selection heuristic.

## Resume When
When SQ8 and RaBitQ are implemented and there is benchmark data to inform automatic selection.

## What Is Known So Far
KB comparison at vector-quantization/CLAUDE.md shows clear tradeoffs: SQ8 for high recall, BQ for extreme compression, RaBitQ for provable bounds. Selection could be based on dimensions (high-dim favors BQ), target recall, and storage budget.

## Next Step
Run `/architect "automatic-quantization-selection"` when ready to evaluate.

---
problem: "backend-optimal-block-size"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Backend-Optimal Block Size Selection — Deferred

## Problem
Optimal block size selection for different storage backends (local SSD vs S3 vs GCS).

## Why Deferred
Scoped out during `sstable-block-compression-format` decision. Separate concern from compression format.

## Resume When
When `sstable-block-compression-format` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/sstable-block-compression-format/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "backend-optimal-block-size"` when ready to evaluate.

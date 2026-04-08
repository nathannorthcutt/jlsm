---
problem: "max-compressed-length"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Max Compressed Length Pre-Allocation — Deferred

## Problem
maxCompressedLength(int) method for output buffer pre-allocation — avoids trial-and-error buffer sizing.

## Why Deferred
Scoped out during `compression-codec-api-design` decision. Can be added later.

## Resume When
When `compression-codec-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/compression-codec-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "max-compressed-length"` when ready to evaluate.

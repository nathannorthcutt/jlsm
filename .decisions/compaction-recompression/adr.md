---
problem: "compaction-recompression"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Compaction-Time Re-Compression — Deferred

## Problem
Compaction-time re-compression with a different codec — allows upgrading compression strategy during background compaction.

## Why Deferred
Scoped out during `sstable-block-compression-format` decision. Separate optimization from the compression format itself.

## Resume When
When `sstable-block-compression-format` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/sstable-block-compression-format/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "compaction-recompression"` when ready to evaluate.

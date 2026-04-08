---
problem: "per-block-checksums"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Per-Block Checksums — Deferred

## Problem
Per-block checksums for data integrity verification at the block level. The map structure can be extended with a checksum field later.

## Why Deferred
Scoped out during `sstable-block-compression-format` decision. Map structure can be extended with a checksum field later.

## Resume When
When `sstable-block-compression-format` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/sstable-block-compression-format/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "per-block-checksums"` when ready to evaluate.

---
problem: "wal-compression"
date: "2026-03-30"
version: 1
status: "deferred"
---

# WAL Compression — Deferred

## Problem
Compression of WAL entries to reduce write amplification and storage cost.

## Why Deferred
Scoped out during `sstable-block-compression-format` decision. Out of scope for SSTable block compression.

## Resume When
When `sstable-block-compression-format` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/sstable-block-compression-format/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "wal-compression"` when ready to evaluate.

---
problem: "sstable-end-to-end-integrity"
date: "2026-04-11"
version: 1
status: "deferred"
---

# ADR: SSTable End-to-End Integrity

**Status:** deferred
**Source:** out-of-scope from `per-block-checksums`

## Problem

End-to-end integrity across the full SSTable (footer checksum, index checksum)
is not covered by per-block CRC32C checksums. A corrupt footer or index section
would not be detected by block-level checks alone.

## Why Deferred

Scoped out during `per-block-checksums` decision. Per-block checksums address
the most common corruption case (data blocks). Footer and index integrity is a
separate, layered concern.

## Resume When

When SSTable corruption is observed in practice that per-block checksums do not
catch, or when the format is next revised.

## What Is Known So Far

See `.decisions/per-block-checksums/adr.md` for the architectural context.
The v3 format stores CRC32C per data block in the CompressionMap. Extending
this to footer and index sections is a natural follow-on.

## Next Step

Run `/architect "sstable-end-to-end-integrity"` when ready to evaluate.

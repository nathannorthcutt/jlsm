---
problem: "adaptive-compression-strategy"
date: "2026-04-12"
version: 1
status: "closed"
resolved_by: "compaction-recompression"
---

# Adaptive Compression Strategy — Closed (Resolved)

## Problem
Adaptive compression strategy selection per compaction level — LZ4 for hot/recent levels (fast), ZSTD+dictionary for cold/archival levels (high ratio).

## Decision
**Resolved by `compaction-recompression` ADR.** The writer-factory injection design inherently supports per-level codec selection — the `SSTableWriterFactory` already receives a `Level` parameter, and the tree builder gains `compressionPolicy(Function<Level, CompressionCodec>)`. No separate decision needed.

## Reason
The `compaction-recompression` ADR's chosen approach (writer-factory injection with per-level codec policy) solves this problem as a natural consequence of its design. The factory closure inspects the level and returns a writer with the appropriate codec. This was recognized during deliberation when the user challenged the separation of these two concerns.

## Context
See `.decisions/compaction-recompression/adr.md` for the full design.

## Conditions for Reopening
If per-level policies need runtime reconfiguration (current design is build-time only) or if a more granular policy is needed (per-file, per-key-range) beyond per-level.

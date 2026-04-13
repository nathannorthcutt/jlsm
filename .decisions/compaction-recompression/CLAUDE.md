---
problem: "compaction-recompression"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-12"
---

# Compaction-Time Re-Compression — Decision Index

**Problem:** How should the compactor support writing output SSTables with a different compression codec than the source?
**Status:** confirmed
**Current recommendation:** Writer-factory injection with per-level codec policy — SpookyCompactor uses SSTableWriterFactory, tree builder gains compressionPolicy(Function<Level, CompressionCodec>)
**Last activity:** 2026-04-12 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-12 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-12 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-12 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-12 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Block Compression Algorithms | Codec context | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |
| ZSTD Dictionary Compression | Dictionary training context | [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-12 | active | Writer-factory injection with per-level codec policy |

## Also Resolves

| Problem | Slug | How |
|---------|------|-----|
| Adaptive Compression Strategy | adaptive-compression-strategy | Per-level policy is inherent in the factory design — Level parameter on SSTableWriterFactory enables codec selection per level |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

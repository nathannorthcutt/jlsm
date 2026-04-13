---
problem: "codec-dictionary-support"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-12"
---

# Codec Dictionary Support — Decision Index

**Problem:** How should the CompressionCodec API and SSTable writer support dictionary compression (ZSTD dictionaries, tiered native/pure-Java, per-SST lifecycle)?
**Status:** confirmed
**Current recommendation:** Writer-orchestrated, codec stays stateless — dictionary lifecycle in writer + ZstdDictionaryTrainer, tiered Panama FFM detection, pure-Java decompressor fallback
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
| ZSTD Dictionary Compression | Informed lifecycle design | [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md) |
| Block Compression Algorithms | Codec survey context | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-12 | active | Writer-orchestrated dictionary support with tiered Panama FFM detection |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

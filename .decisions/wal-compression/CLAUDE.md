---
problem: "wal-compression"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-12"
---

# WAL Compression — Decision Index

**Problem:** Compress WAL records to reduce storage cost; evolve CompressionCodec to MemorySegment
**Status:** confirmed
**Current recommendation:** Per-record compression with MemorySegment-native codec API evolution
**Last activity:** 2026-04-12 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-12 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-12 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-12 |
| [research-brief.md](research-brief.md) | Research Agent commission | 2026-04-12 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-12 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| WAL Compression Patterns | Chosen approach | [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md) |
| Block Compression Algorithms | Codec characteristics | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |
| Multi-Writer WAL Design | Future architecture context | [`.kb/distributed-systems/data-partitioning/multi-writer-wal.md`](../../.kb/distributed-systems/data-partitioning/multi-writer-wal.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-12 | active | Per-record compression + MemorySegment codec API |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

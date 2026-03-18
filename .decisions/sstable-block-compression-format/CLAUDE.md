---
problem: "sstable-block-compression-format"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# SSTable Block Compression Format — Decision Index

**Problem:** How to encode per-block compression metadata in the SSTable on-disk format
**Status:** confirmed
**Current recommendation:** Compression Offset Map — separate file section with per-block metadata array, loaded at reader open time
**Last activity:** 2026-03-17 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-17 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-17 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-17 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-17 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Block Compression Algorithms | Informed codec design | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Compression Offset Map chosen over inline headers and hybrid |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

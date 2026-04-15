---
problem: "sstable-end-to-end-integrity"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# SSTable End-to-End Integrity — Decision Index

**Problem:** How to ensure integrity across all SSTable sections, not just data blocks?
**Status:** confirmed
**Current recommendation:** Three-layer integrity — fsync discipline (prevention) + VarInt-prefixed self-describing blocks (recovery) + per-section CRC32C (detection)
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Corruption Detection and Repair | Recovery strategies, per-layer checksums | [`.kb/systems/database-engines/corruption-detection-repair.md`](../../.kb/systems/database-engines/corruption-detection-repair.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | — | 2026-04-11 | superseded | Deferred — per-block checksums sufficient for now |
| v2 | [adr.md](adr.md) | 2026-04-14 | active | Three-layer integrity (fsync + VarInt blocks + CRC) |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

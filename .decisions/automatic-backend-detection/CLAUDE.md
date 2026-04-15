---
problem: "automatic-backend-detection"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# Automatic Backend Detection — Decision Index

**Problem:** Should TrieSSTableWriter auto-detect storage backend for optimal block size defaults?
**Status:** confirmed
**Current recommendation:** Pool-aware block size configuration — derive block size from ArenaBufferPool instead of detecting the backend
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates) | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| (none) | Decision space bounded by NIO API surface | — |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | — | 2026-04-11 | superseded | Deferred — condition not met |
| v2 | [adr.md](adr.md) | 2026-04-14 | active | Pool-aware block size configuration |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

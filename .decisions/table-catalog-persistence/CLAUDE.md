---
problem: "table-catalog-persistence"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-19"
---

# Table Catalog Persistence — Decision Index

**Problem:** How should the jlsm-engine persist its table registry to survive restarts, support lazy recovery, and isolate per-table failures?
**Status:** confirmed
**Current recommendation:** Per-Table Metadata Directories — each table gets its own subdirectory with metadata file; advisory catalog index for fast enumeration
**Last activity:** 2026-03-19 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-19 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-19 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-19 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-19 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Catalog Persistence Patterns | All 4 patterns evaluated | [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-19 | active | Per-Table Metadata Directories chosen for lazy recovery + failure isolation |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

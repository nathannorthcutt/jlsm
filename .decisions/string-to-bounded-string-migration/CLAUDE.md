---
problem: "string-to-bounded-string-migration"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# String to BoundedString Migration — Decision Index

**Problem:** Schema migration policy when field constraints are tightened
**Status:** confirmed
**Current recommendation:** Compaction-time migration + on-demand scan with quarantine
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Schema Type Systems | Migration strategies, compaction-time pattern | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Compaction-time + on-demand scan composite |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Quarantine resolution policy | quarantine-resolution-policy | deferred | Migration implemented |
| Cross-table schema migration | cross-table-schema-migration | deferred | Multi-table atomic schema changes needed |

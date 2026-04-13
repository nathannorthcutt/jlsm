---
problem: "parameterized-field-bounds"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Parameterized Field Bounds — Decision Index

**Problem:** Extend parameterized bounds to field types beyond BoundedString
**Status:** confirmed
**Current recommendation:** BoundedArray sealed permit only; numeric bounds deferred
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
| Schema Type Systems | Parameterized bounds patterns | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | BoundedArray sealed permit; numeric bounds deferred |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Numeric field range bounds | numeric-field-range-bounds | deferred | OPE byte cap raised or new encryption scheme |

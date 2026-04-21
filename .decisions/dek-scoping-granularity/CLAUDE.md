---
problem: "dek-scoping-granularity"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-21"
---

# dek-scoping-granularity — Decision Index

**Problem:** Identity shape of a DEK within the three-tier hierarchy.
**Status:** confirmed
**Current recommendation:** DEK identity = `(tenantId, domainId, tableId, dekVersion)`. Domain may contain multiple tables; each table has its own versioned DEK under the domain KEK. Structurally determined by ADR A's encrypt-once invariant.
**Last activity:** 2026-04-21 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-21 |
| [evaluation.md](evaluation.md) | Structural argument + falsification | 2026-04-21 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-21 |
| [log.md](log.md) | Decision history | 2026-04-21 |

## Prerequisite

| Slug | Relationship |
|------|-------------|
| [three-tier-key-hierarchy](../three-tier-key-hierarchy/adr.md) | Encrypt-once invariant and three-tier commitment structurally determine this ADR's outcome |

## Confidence

High. Structurally forced by ADR A; the only design freedom was whether to collapse domain=table, which the user rejected.

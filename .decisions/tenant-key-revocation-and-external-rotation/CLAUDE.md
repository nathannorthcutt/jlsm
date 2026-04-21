---
problem: "tenant-key-revocation-and-external-rotation"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-21"
---

# tenant-key-revocation-and-external-rotation — Decision Index

**Problem:** How jlsm handles a tenant externally rotating or revoking their KEK (flavor 3, BYO-KMS) without coordinating with jlsm.
**Status:** confirmed
**Current recommendation:** API primary + opt-in polling; streaming paginated rekey with dual-reference migration; three-state failure machine (healthy / grace-read-only / failed) with N=5 permanent-failures threshold and 1h grace window defaults. Explicit decommission deferred.
**Last activity:** 2026-04-21 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-21 |
| [evaluation.md](evaluation.md) | Design validation + falsification | 2026-04-21 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-21 |
| [log.md](log.md) | Decision history + deliberation summary | 2026-04-21 |

## Prerequisite

| Slug | Relationship |
|------|-------------|
| [three-tier-key-hierarchy](../three-tier-key-hierarchy/adr.md) | This ADR inherits sharded-registry / per-tenant-KMS isolation / cascading rewrap from ADR A |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Tenant decommission (data erasure, audit retention, catalog cleanup) | tenant-lifecycle | deferred | Compliance requirement surfaces OR first GDPR right-to-erasure request arrives |

## Confidence

Medium-high. Main residual uncertainty: whether N=5 / 1h defaults tune correctly under production traffic; whether deferring explicit decommission will bite compliance teams.

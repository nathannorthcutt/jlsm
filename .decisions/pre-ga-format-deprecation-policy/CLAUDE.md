---
problem: "pre-ga-format-deprecation-policy"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-24"
---

# Pre-GA Format-Version Deprecation Policy — Decision Index

**Problem:** How jlsm retires old versions of any persistently-stored
or on-wire format artefact (SSTable, WAL, catalog `table.meta`,
ciphertext envelope, document serializer) without indefinite
backward-compatibility debt, in both pre-GA (zero migration debt) and
post-GA (≥ 1 major deprecation window) regimes.

**Status:** confirmed
**Current recommendation:** Full mechanism set (Candidate C) — Prefer-
current-version rule + bounded background sweep + format inventory +
per-collection watermark + operator-triggered targeted upgrade command.
Pre-GA window = zero (eager delete); first exercise = SSTable v1–v4
collapse.
**Last activity:** 2026-04-24 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-24 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates × 6 dimensions, post-falsification) | 2026-04-24 |
| [constraints.md](constraints.md) | Constraint profile (6 dimensions + 5 falsification additions) | 2026-04-24 |
| [research-brief.md](research-brief.md) | Research Agent commission for KB entry | 2026-04-24 |
| [log.md](log.md) | Full decision history + deliberation summary | 2026-04-24 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Format-Version Deprecation Strategies in Production Database Systems | Primary evidence (commissioned for this decision) | [`.kb/systems/database-engines/format-version-deprecation-strategies.md`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md) |
| Encryption Key Rotation Patterns | Analog for compaction-driven rewrite | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Database Catalog Persistence Patterns | Catalog format-version-byte pattern | [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md) |
| Dispatch-Discriminant Corruption Bypass | Anti-pattern informing watermark cross-check | [`.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md`](../../.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md) |
| Version Discovery Self-Only With No External Cross-Check | Anti-pattern informing catalog-mediated check | [`.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-24 | active | Full mechanism set (Candidate C); pre-GA window = zero; first exercise SSTable v1–v4 collapse |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Cluster format-version coexistence (C+D hybrid) | [cluster-format-version-coexistence](../cluster-format-version-coexistence/adr.md) | deferred | A real consumer embeds jlsm in a cluster product and cluster-version coordination fights with per-node policy |
| jlsm release cadence definition | [jlsm-release-cadence](../jlsm-release-cadence/adr.md) | deferred | Project commits to a first release (alpha/beta/stable) |
| Format-version downgrade-attack defence (per artefact) | [sstable-active-tamper-defence](../sstable-active-tamper-defence/adr.md) (already existed from WD-02; covers SSTable side) + future per-artefact defences | deferred (existing) | Per-artefact need arises (SSTable already tracked) |

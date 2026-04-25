---
problem: "table-handle-scope-exposure"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-24"
---

# Table-Handle Scope Exposure — Decision Index

**Problem:** How the encryption read path in jlsm-core obtains `(tenantId, domainId, tableId)` scope from a `Table` handle in jlsm-engine.
**Status:** confirmed
**Current recommendation:** Extend `TableMetadata` with `Optional<EncryptionMetadata>` sub-record; `TableScope` composes WD-01 identity records; new Engine methods `createEncryptedTable` and `enableEncryption`; encryption is one-way.
**Last activity:** 2026-04-24 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-24 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates + missing-candidate G from falsification) | 2026-04-24 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-24 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-24 |

## KB Sources Used

None — API-shape decision dominated by fit against existing Engine/Table/Catalog surface.

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-24 | active | TableMetadata extended with Optional<EncryptionMetadata>; one-way enablement |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Disable encryption in place | encryption-disable-policy | deferred | A concrete user demand arises for in-place decrypt-back (vs copy-table + drop) |

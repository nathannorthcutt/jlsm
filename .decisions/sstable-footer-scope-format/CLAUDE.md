---
problem: "sstable-footer-scope-format"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-24"
---

# SSTable Footer Scope Format — Decision Index

**Problem:** How the SSTable footer encodes `(tenantId, domainId, tableId)` scope + DEK version set for the encryption read path.
**Status:** confirmed
**Current recommendation:** v5→v6 format bump with fixed-position scope section; CRC32C-covered via the existing v5 section-checksum scheme.
**Last activity:** 2026-04-24 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-24 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates) | 2026-04-24 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-24 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-24 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| SSTable Block-Level Ciphertext Envelope and Key-ID Signalling | Surveyed production patterns; informed candidate enumeration | [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-24 | active | v5→v6 format bump with fixed-position scope section |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Per-block AES-GCM encryption transition | encryption-granularity-per-field-vs-per-block | deferred | If jlsm commits to per-block encryption model within 12 months |

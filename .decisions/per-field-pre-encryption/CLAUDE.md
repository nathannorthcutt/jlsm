# Per-Field Pre-Encryption — Decision Index

**Problem:** How should JlsmDocument support partial (per-field) pre-encryption instead of all-or-nothing?
**Status:** confirmed
**Current recommendation:** Bitset Flag — `long preEncryptedBitset` in JlsmDocument with per-field factory method
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
| Client-Side Encryption Patterns | Per-field encryption in MongoDB CSFLE/AWS DB SDK | [`.kb/systems/security/client-side-encryption-patterns.md`](../../.kb/systems/security/client-side-encryption-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Bitset Flag with per-field factory method |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

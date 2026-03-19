---
problem: "encrypted-index-strategy"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-18"
---

# Encrypted Index Strategy — Decision Index

**Problem:** How should secondary indices and queries adapt when field values are encrypted?
**Status:** confirmed
**Current recommendation:** Static Capability Matrix with tiered full-text search (T1 keyword, T2 phrase, T3 SSE)
**Last activity:** 2026-03-18 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-18 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-18 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-18 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-18 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Searchable Encryption Schemes | Informed capability matrix + full-text tiers | [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md) |
| Vector Encryption Approaches | Informed DCPE ANN support | [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-18 | active | Static capability matrix, 3-tier full-text search |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

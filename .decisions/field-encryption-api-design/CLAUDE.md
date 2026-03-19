---
problem: "field-encryption-api-design"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-18"
---

# Field Encryption API Design — Decision Index

**Problem:** How should field-level encryption be expressed in JlsmSchema, how should keys bind to fields, and how should DocumentSerializer integrate?
**Status:** confirmed
**Current recommendation:** Schema Annotation — FieldDefinition carries sealed EncryptionSpec, keys in Arena-backed holder
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
| Searchable Encryption Schemes | Informed EncryptionSpec variants | [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md) |
| Vector Encryption Approaches | Informed DistancePreserving spec | [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md) |
| JVM Key Handling Patterns | Informed key holder design | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-18 | active | Schema Annotation with sealed EncryptionSpec |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

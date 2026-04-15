# WAL Entry Encryption — Decision Index

**Problem:** How should WAL entries be encrypted at rest while preserving recovery semantics?
**Status:** confirmed
**Current recommendation:** Per-Record AES-GCM-256 with Sequence-Number Nonce — opt-in, compress-then-encrypt
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
| WAL Encryption Approaches | Core design: cipher, nonce, format, integration | [`.kb/systems/security/wal-encryption-approaches.md`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Encryption Key Rotation Patterns | Envelope encryption for WAL SEKs | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Per-record AES-GCM-256, sequence-number nonce |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

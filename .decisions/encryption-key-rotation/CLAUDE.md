# Encryption Key Rotation — Decision Index

**Problem:** How should encryption keys be rotated without downtime or bulk data rewrite?
**Status:** confirmed
**Current recommendation:** Envelope Encryption + Compaction-Driven Re-Encryption — KEK wraps versioned DEKs, compaction re-encrypts
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
| Encryption Key Rotation Patterns | Core design pattern | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| JVM Key Handling Patterns | Off-heap key storage | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Envelope encryption + compaction-driven re-encryption |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Forced immediate rotation | — | deferred | Compliance requires immediate rotation (not eventual) |
| Updatable encryption | — | deferred | UE schemes become practical in JCA providers |

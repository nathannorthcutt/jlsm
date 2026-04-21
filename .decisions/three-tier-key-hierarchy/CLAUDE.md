---
problem: "three-tier-key-hierarchy"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-21"
---

# three-tier-key-hierarchy — Decision Index

**Problem:** Design jlsm's key hierarchy for multi-tenant encryption with per-tenant KMS isolation.
**Status:** confirmed
**Current recommendation:** Three-tier envelope (tenant KEK → data-domain KEK → DEK) with per-tenant KMS isolation always-on; three KMS flavors (`none`/`local`/`external`); HKDF hybrid deterministic derivation; sharded or log-structured registry; dedicated `_wal` data domain per tenant.
**Last activity:** 2026-04-21 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-21 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (three-tier 59 / two-tier 40 / flat 27) | 2026-04-21 |
| [constraints.md](constraints.md) | Constraint profile + finalized invariants | 2026-04-21 |
| [log.md](log.md) | Full decision history + deliberation summary | 2026-04-21 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Three-Level Key Hierarchy | Chosen approach | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| Three-Level Key Hierarchy (detail) | Reference designs + code skeleton | [`.kb/systems/security/three-level-key-hierarchy-detail.md`](../../.kb/systems/security/three-level-key-hierarchy-detail.md) |
| Encryption Key Rotation Patterns | Envelope baseline, rotation model | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| JVM Key Handling Patterns | Panama FFM Arena, zeroize-on-close | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |
| Client-Side Encryption Patterns | Flavor 3 comparison reference | [`.kb/systems/security/client-side-encryption-patterns.md`](../../.kb/systems/security/client-side-encryption-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-21 | active | Three-tier envelope with per-tenant KMS; amends `encryption-key-rotation` and `per-field-key-binding`; triggers spec amendments to `encryption.primitives-lifecycle` and a Verification Note on `wal.encryption` |

## Downstream ADRs Gated on This Decision

- ADR B — `dek-scoping-granularity` (per-table vs per-SSTable vs per-object DEK scope)
- ADR C — `kms-integration-model` (cache TTL, outage semantics, retry posture)
- ADR D — `tenant-key-revocation-and-external-rotation` (flavor 3 revocation handling)

## Confidence

Medium-high. Cap set by deferred sharded-registry layout, deferred wire-tag byte format, and the "sub-tenant isolation is a real deployment requirement" design bet.

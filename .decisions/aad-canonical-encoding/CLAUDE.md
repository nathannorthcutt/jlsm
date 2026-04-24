---
problem: "aad-canonical-encoding"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-23"
---

# aad-canonical-encoding — Decision Index

**Problem:** Define the canonical byte-encoding of `EncryptionContext` (a `Map<String,String>` plus `Purpose` discriminant) used as AES-GCM AAD when wrapping DEKs under domain KEKs. Byte-exact determinism is required for successful unwrap; any mismatch produces an outage-class auth failure.
**Status:** confirmed
**Current recommendation:** Length-prefixed TLV — `[4B BE Purpose.code() | 4B BE attr-count | sorted-by-UTF8-byte-key (4B BE key-len | UTF-8 key | 4B BE val-len | UTF-8 val) pairs]`. Sorted keys + `Purpose.code()` (not `ordinal()`) + UTF-8 bytes-as-supplied. Zero external deps; mirrors R11 HKDF info encoding pattern.
**Last activity:** 2026-04-23 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-23 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (TLV / JSON-JCS / CBOR / Protobuf) | 2026-04-23 |
| [constraints.md](constraints.md) | Constraint profile with falsification appendix | 2026-04-23 |
| [log.md](log.md) | Decision history + deliberation summaries | 2026-04-23 |

## Prerequisites

| Slug | Relationship |
|------|-------------|
| [three-tier-key-hierarchy](../three-tier-key-hierarchy/adr.md) | Establishes the wrap/unwrap hierarchy that requires AAD |
| [kms-integration-model](../kms-integration-model/adr.md) | Defines `EncryptionContext` as `Map<String,String>` passed to `KmsClient`; amended by this ADR |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Three-Level Key Hierarchy | Cited — R11 HKDF info precedent | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| SSTable Block-Level Ciphertext Envelope | Cited — parallel AAD-binding use case | [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) |
| DEK Caching Policies (Multi-Tenant) | Cited — AAD-as-cache-key consumer | [`.kb/systems/security/dek-caching-policies-multi-tenant.md`](../../.kb/systems/security/dek-caching-policies-multi-tenant.md) |

## Rejected Candidates

| Candidate | Total | Why rejected |
|-----------|-------|--------------|
| JSON + RFC 8785 (JCS) | 40 | Library dep; library bugs = silent outages |
| CBOR (RFC 8949 §4.2.1) | 40 | First external dep in encryption path for tiny payload |
| Protobuf (deterministic) | 28 | 1.5MB dep + Google's own cross-version-determinism warning |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-23 | active | Retroactive capture of WU-2 landed choice; codified in spec v9 R80a / R80a-1 |

## Out-of-Scope Items (Tracked as Deferred Stubs)

| Topic | Slug | Status |
|-------|------|--------|
| Attribute-set forward compatibility | [aad-attribute-set-evolution](../aad-attribute-set-evolution/adr.md) | deferred |
| Context attribute value normalization | [aad-identifier-normalization](../aad-identifier-normalization/adr.md) | deferred |
| Non-Java consumer interoperability | [aad-non-java-consumer-interop](../aad-non-java-consumer-interop/adr.md) | deferred |
| Heterogeneous attribute value types | [aad-heterogeneous-value-types](../aad-heterogeneous-value-types/adr.md) | deferred |

## Confidence

High. Landed choice mirrors R11 HKDF info pattern (already approved); falsification found no weakened scores; all four rejected alternatives fail on either zero-dep or determinism constraints.

---
problem: "three-tier-key-hierarchy"
evaluated: "2026-04-21"
candidates:
  - path: ".kb/systems/security/three-level-key-hierarchy.md"
    name: "Three-tier hierarchy (tenant KEK → domain KEK → DEK)"
  - path: ".kb/systems/security/encryption-key-rotation-patterns.md"
    name: "Two-tier hierarchy (KEK → DEK) — legacy"
  - path: "n/a"
    name: "Flat (per-field independent keys, no KEK wrapping)"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — three-tier-key-hierarchy

## References

- Constraints: [constraints.md](constraints.md)
- Research: [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)
- Existing ADRs in scope to supersede: `encryption-key-rotation`, `per-field-key-binding`

## Constraint Summary

Binding constraints: unbounded scale with multi-tenant isolation, per-tenant KMS
(possibly customer-managed), and the "plaintext never lives outside ingress
window" correctness posture. Together these rule out flat and two-tier designs
before scoring begins.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|---|---|---|
| Scale | 3 | Unbounded tenant/domain/DEK space is the gating constraint |
| Resources | 2 | Panama FFM + Arena pattern is strict but solvable |
| Complexity | 1 | Not penalized per project feedback; agent-assisted |
| Accuracy | 3 | Zero-tolerance on cross-tenant leakage + ingress-window plaintext |
| Operational | 2 | Rotation-without-barrier and sub-ms cache hit are hard but bounded |
| Fit | 2 | Panama FFM + `KmsClient` SPI required; EncryptionKeyHolder open for refactor |

---

## Candidate: Three-tier hierarchy (tenant KEK → domain KEK → DEK)

**KB source:** [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)
**Relevant sections:** full article + detail file

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|---|---|---|---|---|
| Scale | 3 | 5 | 15 | Research: third level is only justified by multi-tenant blast-radius isolation; domain layer is specifically the horizontal scale unit. Matches "unbounded tenants" requirement. |
| | | | | **Would be a 2 if:** registry sharding isn't adopted — single-file atomic-update pattern breaks at 100GB scale. Pinned as a required consequence. |
| Resources | 2 | 4 | 8 | Research detail shows three-tier adds one KMS round-trip on cold domain open (+~50ms) but hot path is the same as two-tier. Panama FFM Arena handles per-tier wrapped-bytes cleanly. |
| | | | | **Would be a 2 if:** KMS outage takes out unrelated tenants (ADR C TTL must isolate by tenant). |
| Complexity | 1 | 3 | 3 | +1 tier of wrap/unwrap, +1 conceptual layer in `EncryptionKeyHolder`. Research has code skeleton. Justified by scale requirement. |
| Accuracy | 3 | 5 | 15 | HKDF-info length-prefixed derivation gives provable domain separation. AES-KW for L1→L2 (deterministic, auth-covered by AES), AES-GCM with HKDF-info-as-AD for L2→L3 blocks cross-domain ciphertext swap. Meets zero-tolerance bar. |
| | | | | **Would be a 2 if:** info-field canonicalization is skipped — `tenant=a,table=bc` vs `tenant=ab,table=c` collision. Pinned as length-prefixed. |
| Operational | 2 | 4 | 8 | Lazy cascading rewrap bounds per-op work under rotation. Hash-map lookup keyed by `(domainId, version)` delivers sub-ms cache hits. Rotation grace period tied to WAL retention. |
| | | | | **Would be a 2 if:** lazy rewrap isn't implemented and a synchronous O(domains) rewrap is required on root rotation. |
| Fit | 2 | 5 | 10 | Panama FFM `MemorySegment` maps cleanly onto wrapped-bytes-in-Arena at each tier. `KmsClient` SPI is the natural boundary for tier 1. HKDF-hybrid derivation aligns with existing `primitives-lifecycle` R9–R16. |
| | | | | **Would be a 2 if:** the hierarchy forces heap `byte[]` for any live key material. Research code skeleton shows `MemorySegment` all the way. |
| **Total** | | | **59** | |

**Hard disqualifiers:** none.

**Key strengths:** Only candidate that satisfies unbounded multi-tenant scale
with operator-compromise resistance. HKDF hybrid derivation enables the
"per-field ciphertext produced once, reused across pipeline" pattern. Domain
layer is the horizontal scale unit.

**Key weaknesses:** Added operational complexity (sharded registry, lazy
rewrap, deprecation grace period). Acceptable given the constraint profile
(complexity weight = 1).

---

## Candidate: Two-tier hierarchy (KEK → DEK) — legacy

**KB source:** [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md)
**Relevant sections:** existing envelope-encryption pattern

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|---|---|---|---|---|
| Scale | 3 | 1 | 3 | **Hard disqualifier:** single KEK across all tenants — any tenant compromise propagates to all. Multi-tenant blast-radius isolation impossible. |
| Resources | 2 | 5 | 10 | One fewer tier → less work per operation. |
| Complexity | 1 | 5 | 5 | Simpler; existing `primitives-lifecycle` R17 is two-tier. |
| Accuracy | 3 | 2 | 6 | HKDF-per-field gives some isolation but no cross-tenant boundary. Zero-tolerance bar fails under multi-tenant requirement. |
| Operational | 2 | 5 | 10 | Simpler rotation (only DEK rotation matters; KEK rotation re-wraps O(DEKs)). |
| Fit | 2 | 3 | 6 | Existing `EncryptionKeyHolder` already two-tier; conforms but can't extend cleanly to multi-tenant. |
| **Total** | | | **40** | |

**Hard disqualifiers:** Fails Scale dimension under multi-tenant requirement
(single shared KEK across tenants).

**Key strengths:** Simplicity. Fewer KMS round-trips.

**Key weaknesses:** Cannot provide per-tenant isolation. Cannot honor
"operator-compromise resistance" when tenant KEK must live in tenant's KMS.

---

## Candidate: Flat (per-field independent keys, no KEK wrapping)

**KB source:** none (not a referenced pattern)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|---|---|---|---|---|
| Scale | 3 | 2 | 6 | Each field has its own key. Registry grows with `fields × rotations`. No hierarchy to cascade rotations. |
| Resources | 2 | 1 | 2 | **Hard disqualifier:** every field write requires a fresh KMS operation to materialise a key — unbounded KMS traffic. |
| Complexity | 1 | 4 | 4 | Conceptually simple (no wrapping). |
| Accuracy | 3 | 3 | 9 | Per-field isolation is strong, but no structural tenant boundary. |
| Operational | 2 | 1 | 2 | Rotation means re-encrypting every field. Infeasible at scale. |
| Fit | 2 | 2 | 4 | No KMS pattern supports this well. Panama FFM fine but no composition with existing primitives. |
| **Total** | | | **27** | |

**Hard disqualifiers:** Resources (KMS traffic unbounded). Operational
(rotation requires full re-encryption).

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|---|---|---|---|---|---|---|---|
| Three-tier | 15 | 8 | 3 | 15 | 8 | 10 | **59** |
| Two-tier | 3 | 10 | 5 | 6 | 10 | 6 | 40 |
| Flat | 6 | 2 | 4 | 9 | 2 | 4 | 27 |

## Preliminary Recommendation

**Three-tier hierarchy (tenant KEK → domain KEK → DEK)**, with the
consequences pinned in constraints.md:

- Sharded or log-structured registry
- Per-domain-scoped DEK version space (wire tag: `(domainId, version)`)
- Lazy domain open + cascading lazy rewrap
- HKDF hybrid derivation (deterministic per-field DEK identity)
- Dedicated `_wal` domain for WAL encryption
- Plaintext bounded to ingress window; MemTable holds ciphertext
- Grace period for retiring tenant KEK exceeds WAL retention

Two-tier is ruled out by the multi-tenant scale requirement. Flat is ruled
out by KMS traffic volume.

## Risks and Open Questions

- **Risk:** sharded-registry design is unspecified — must be pinned in the
  amended `encryption.primitives-lifecycle` spec. Per-domain files vs
  log-structured merge-tree-of-registry vs SQLite-like single-file page
  format are all candidates.
- **Risk:** `(domainId, version)` wire-tag format amends F42 and
  `primitives-lifecycle` R22. Downstream consumers (query/encrypted-*
  specs) may need version-tag-aware lookup.
- **Open:** DEK scope (per-table vs per-SSTable) — ADR B.
- **Open:** KMS cache TTL and outage semantics — ADR C.
- **Open:** Tenant-driven revocation + external rotation — ADR D.

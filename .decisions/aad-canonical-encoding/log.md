## 2026-04-23 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Retroactive architect session to capture the AAD canonical encoding decision landed inline during WU-2 implementation and codified in spec v9 R80a. Amends `kms-integration-model` (EncryptionContext semantics) and cross-references `three-tier-key-hierarchy`.

**Files written/updated:**
- `constraints.md` — full six-dimension profile captured from invocation

**KB files read:**
- [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) (header, context-binding section)
- [`.kb/systems/security/dek-caching-policies-multi-tenant.md`](../../.kb/systems/security/dek-caching-policies-multi-tenant.md) (AAD-as-cache-key grep)
- [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) (AAD contents grep)

**Dependencies verified:**
- `kms-integration-model` — confirmed (2026-04-21)
- `three-tier-key-hierarchy` — confirmed (2026-04-21)

---

## 2026-04-23 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Length-prefixed TLV (sorted keys, `Purpose.code()` discriminant) confirmed as the canonical AAD encoding for DEK wrap/unwrap under domain KEKs. Retroactive capture of choice landed inline during WU-2 and codified in spec v9 R80a / R80a-1. Amends `kms-integration-model` EncryptionContext semantics section.

### Deliberation Summary

**Rounds of deliberation:** 0 (retroactive capture — invocation explicitly requested `confirmed` final state; the choice was already implemented and tested in WU-2 and codified in spec v9 R80a)
**Recommendation presented:** Length-prefixed TLV (custom, sorted keys, `Purpose.code()`)
**Final decision:** Length-prefixed TLV (same as presented)

**Topics raised during deliberation:**
- (No interactive deliberation occurred — invocation supplied full constraint profile and explicitly requested confirmed state as this ADR documents a landed choice.)

**Constraints updated during falsification:**
- Fit dimension: added consistency-with-R11 constraint (length-prefixed UTF-8 + 4-byte BE lengths is the R11 HKDF info pattern; choosing the same encoding reduces maintenance burden). Surfaced during Step 1b falsification against `.spec/domains/encryption/primitives-lifecycle.md#R11`.

**Assumptions explicitly called out:**
- R11 HKDF info encoding remains stable (most dangerous assumption per falsification).
- All context attribute values remain strings (R80a-1 `dekVersion` is decimal UTF-8).
- Callers are responsible for identifier stability (no Unicode normalization inside the encoder).

**Override:** None
**Override reason:** N/A

**Confirmation:** User invocation explicitly requested `confirmed` final state and supplied the full constraint profile — treated as pre-confirmation for this retroactive capture.

**Files written after confirmation:**
- `adr.md` — decision record v1 (confirmed)
- `constraints.md` — full six-dimension profile + Step 1b falsification appendix
- `evaluation.md` — four-candidate weighted matrix (TLV=63, JSON/JCS=40, CBOR=40, Protobuf=28) + three sub-choice evaluations

**KB files read during evaluation:**
- [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) (R11 precedent)
- [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) (parallel AAD use case)
- [`.kb/systems/security/dek-caching-policies-multi-tenant.md`](../../.kb/systems/security/dek-caching-policies-multi-tenant.md) (AAD-as-cache-key consumer)

**Spec files read:**
- `.spec/domains/encryption/primitives-lifecycle.md` (R11, R80a, R80a-1)
- `.spec/domains/encryption/ciphertext-envelope.md`

**ADRs amended or cross-referenced:**
- Amends: `kms-integration-model` (EncryptionContext semantics section — AAD encoding was underspecified there; this ADR is the canonical reference)
- Depends on: `three-tier-key-hierarchy`, `kms-integration-model`

**Work group context:**
- `.work/implement-encryption-lifecycle/WD-01.md` — COMPLETE (landed the implementation)
- `.work/implement-encryption-lifecycle/WD-02.md` — DRAFT (depends on this ADR)
- `.work/implement-encryption-lifecycle/WD-03.md` — DRAFT (depends on this ADR)
- `.work/implement-encryption-lifecycle/WD-04.md` — DRAFT (depends on this ADR)
- `.work/implement-encryption-lifecycle/WD-05.md` — DRAFT (depends on this ADR)

**Falsification outcome:**
- All 6 scores ≥ 4 on the recommended candidate survived challenge — none weakened
- Strongest counter (CBOR) rejected: cross-language scenario not in constraint profile
- Missing-candidate check: Bencode / MessagePack / ASN.1 DER considered; none change recommendation
- Most dangerous assumption (R11 stability) acknowledged with explicit revision trigger

---

# Decision Log — three-tier-key-hierarchy

## 2026-04-21 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** New ADR opened for jlsm three-tier key hierarchy structure. Supersedes the two-tier assumption in `encryption.primitives-lifecycle` R17, `encryption-key-rotation`, and `per-field-key-binding`. Triggered by `implement-encryption-lifecycle` WD-01 specification work.

**Files written/updated:**
- `.decisions/three-tier-key-hierarchy/constraints.md` — initial constraint profile

**KB files read:**
- [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)
- [`.kb/systems/security/three-level-key-hierarchy-detail.md`](../../.kb/systems/security/three-level-key-hierarchy-detail.md)
- [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md)
- [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md)

**Deliberation so far:**
- User confirmed three-tier structure (2026-04-21) — amends two-tier spec R17.
- Scale set to unbounded with 4 pinned consequences (sharded registry, per-domain version scope, lazy open, cascading rewrap).
- Tenant KMS posture set to always-on isolation with three flavors: `none` / `local` (reference, insecure) / `external` (BYO-KMS).
- `EncryptionKeyHolder` is open for modification; `KmsClient` SPI becomes new public surface.
- User flagged `tenant-key-revocation-and-external-rotation` as a separate ADR (ADR D) to decide after this one.

---

## 2026-04-21 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Three-tier hierarchy confirmed (Medium-high confidence). Amends `encryption-key-rotation` and `per-field-key-binding`. Triggers spec amendments to `encryption.primitives-lifecycle` R17+, and a Verification Note on `wal.encryption` R2.

### Deliberation Summary

**Rounds of deliberation:** 6+ (constraint iteration drove most of the shape).

**Recommendation presented:** Three-tier hierarchy (tenant KEK → domain KEK → DEK).
**Final decision:** Three-tier hierarchy — same as presented.

**Topics raised during deliberation:**
- User surfaced "where does unbounded scale break?" — produced 4 pinned structural consequences (sharded registry, per-domain version scope, lazy domain open, cascading rewrap).
- User surfaced per-tenant KMS isolation as a requirement — drove three-flavor `KmsClient` SPI design.
- User surfaced WAL rotation interaction — confirmed dedicated `_wal` domain per tenant; grace-period-must-exceed-WAL-retention invariant recorded.
- User surfaced "encrypt once at ingress" as a correctness posture — drove HKDF hybrid deterministic derivation and "primary keys plaintext, field values ciphertext through pipeline" pin.
- User asked about lift to High confidence — declined in favor of deferring sharded-registry layout and wire-tag format to spec-authoring.

**Constraints updated during deliberation:**
- Scale: unbounded with 4 pinned consequences (added after user question).
- Fit: `MemorySegment` requirement hardened; `EncryptionKeyHolder` marked open-for-modification.
- Correctness: "plaintext bounded to ingress + MemTable holds field ciphertext + primary keys remain plaintext" posture added.
- New "Finalized Invariants" section appended after in-session deliberation.

**Assumptions explicitly confirmed by user:**
- Sub-tenant blast-radius isolation is a real requirement (not aspirational at decision time).
- Mixed-sensitivity data domains within a tenant are expected.
- Primary keys remain plaintext (sort-order preservation).
- Deferred items (sharded-registry layout, wire-tag format) are acceptable as spec-authoring decisions.

**Falsification outcomes:**
- Scale=5 weakened to "holds under sub-tenant isolation requirement" — user confirmed requirement.
- Accuracy=5 weakened to "structural not cryptographic" — acceptable because structural isolation is the blast-radius primitive.
- Most dangerous assumption: MemTable-holds-ciphertext vs primary-key-encryption — pinned "primary keys plaintext; field values ciphertext".
- Missing candidates (client-side envelope, HSM-native keyring): complementary to this ADR, not competing. Client-side is handled by flavor 3; HSM-native is a `KmsClient` implementation.

**Override:** None.

**Confidence:** Medium-high. Cap set by: (a) deferred sharded-registry concrete layout; (b) deferred wire-tag byte format; (c) sub-tenant isolation requirement is a design bet not yet evidenced by real deployments.

**Confirmation:** User confirmed via "Accept Medium-high" choice after surfacing what would lift to High.

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — appended Finalized Invariants section during deliberation
- `evaluation.md` — scoring matrix (three-tier 59 / two-tier 40 / flat 27)

**KB files read during evaluation:**
- [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)
- [`.kb/systems/security/three-level-key-hierarchy-detail.md`](../../.kb/systems/security/three-level-key-hierarchy-detail.md)
- [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md)
- [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md)
- [`.kb/systems/security/client-side-encryption-patterns.md`](../../.kb/systems/security/client-side-encryption-patterns.md)

**Specs read for falsification:**
- `.spec/domains/encryption/primitives-lifecycle.md` (DRAFT v4)
- `.spec/domains/encryption/primitives-key-holder.md` (APPROVED v1)
- `.spec/domains/encryption/primitives-variants.md` (APPROVED v1)
- `.spec/domains/wal/encryption.md` (APPROVED v3)

---

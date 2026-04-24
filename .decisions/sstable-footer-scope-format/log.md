# sstable-footer-scope-format — Decision Log

## 2026-04-24 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Evaluating how the SSTable footer encodes (tenantId, domainId, tableId) scope + DEK version set so the encryption read path satisfies primitives-lifecycle R22b/R23a.

**Files written/updated:**
- `constraints.md` — initial constraint profile derived from WD-02 context and primitives-lifecycle R22b/R23a
- `evaluation.md` — four candidates (v6 fixed-position, v6 TLV, v5 optional, external registry)

**KB files read:**
- [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
- [`.kb/systems/security/CLAUDE.md`](../../.kb/systems/security/CLAUDE.md)
- [`.decisions/sstable-end-to-end-integrity/adr.md`](../sstable-end-to-end-integrity/adr.md)

---

## 2026-04-24 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** C1 (v5→v6 format bump with fixed-position scope section) confirmed after falsification reframed Accuracy score and user explicitly rejected bundling per-block AES-GCM transition.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** C1 — v5→v6 fixed-position scope section (weighted total 65 → 63 post-falsification)
**Final decision:** C1 — same as presented

**Topics raised during deliberation:**
- User asked whether to bundle per-block AES-GCM encryption into this ADR/WD.
  Response: enumerated risks — breaks OPE/DCPE variants (incompatible with
  block-level encryption); violates `encryption.ciphertext-envelope` R1a
  cross-tier uniformity invariant; cascades across 6+ APPROVED specs
  (primitives-variants, primitives-dispatch, primitives-key-holder,
  client-side-sdk, wal.encryption); requires WD-03/04 redesign; 2–3
  month re-architecting cost. User confirmed: "let's not do that work."

**Constraints updated during deliberation:** None (stable profile).

**Assumptions explicitly confirmed by user:**
- Per-block AES-GCM transition is NOT planned within the next 12 months
- Scope identifiers stay under ~128B typical
- Cryptographic defence delegates to WD-01's HKDF scope binding; footer
  scope + R22b is a fast-fail / clear-error layer, not a tamper-crypto
  layer

**Override:** None
**Override reason:** N/A

**Falsification outcome:**
- Accuracy=5 challenged: subagent argued CRC32C is not cryptographic
  tamper resistance. Held on reframed meaning — R22b is an ordering
  requirement, not a cryptographic boundary; HKDF scope binding
  (WD-01 primitives-lifecycle R11) is the cryptographic defence.
- Resources=5 → 4: rescored honestly; variable-length fields mean
  computed offsets not literal-fixed positions.
- C5 scope-as-AAD alternative raised; rejected as redundant with
  existing HKDF scope binding.
- C6 filename-encoded and C7 manifest-authoritative alternatives raised;
  rejected — change catalog/naming conventions significantly.
- Recommendation change: None (C1 total adjusted 65→63; still wins).

**Confirmation:** User confirmed with: "let's not do that work. i approve the suggested adr scope"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes beyond the falsification block written before deliberation
- `evaluation.md` — updated with falsification adjustments

**KB files read during evaluation:**
- [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)

---

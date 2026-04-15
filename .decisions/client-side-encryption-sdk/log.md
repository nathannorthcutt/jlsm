## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** pre-encrypted-document-signaling
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — re-deferred

**Agent:** Architect Agent (WD-09 batch)
**Event:** re-deferred
**Reason:** Dependencies now confirmed (per-field-pre-encryption, encryption-key-rotation, per-field-key-binding). SDK requires those implementations before design can proceed. KB research complete (client-side-encryption-patterns.md). Updated "What Is Known So Far" with confirmed dependency decisions and design direction.

---

## 2026-04-15 — decision-confirmed

**Agent:** Work Plan (WD-12)
**Event:** decision-confirmed
**Resolution:** Promoted from deferred to accepted — resolved by F45 (Client-Side Encryption SDK). Schema-driven auto-encrypt/decrypt with KeyVault SPI, off-heap key caching, per-field HKDF key derivation reusing F41 infrastructure.
**Spec:** F45 (APPROVED, 35 reqs)

---

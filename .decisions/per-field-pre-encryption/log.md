## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** pre-encrypted-document-signaling
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** Bitset Flag — replace `boolean preEncrypted` with `long preEncryptedBitset` in JlsmDocument. New `preEncrypted(schema, fieldNames, nameValuePairs...)` factory method. Backward compatible with existing all-or-nothing API.
**Candidates evaluated:** Bitset Flag (53/60), Per-Field Wrapper Type (37/60), Metadata Map (31/60)
**Key rationale:** Zero-overhead per-field check via bitwise AND. No new types. Parent ADR rejected metadata map pattern.

---

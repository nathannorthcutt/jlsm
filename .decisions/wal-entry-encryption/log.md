## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** field-encryption-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** Per-Record AES-GCM-256 with Sequence-Number Nonce. Opt-in encryption with compress-then-encrypt ordering. Per-segment SEK wrapped by caller's principal key.
**Candidates evaluated:** Per-Record AES-GCM (53/60), Segment-Level AES-CTR (36/60), Field-Selective (32/60)
**Key rationale:** Random-access replay, per-record authentication, zero nonce state. DuckDB uses same pattern. Composes with both WAL implementations.

---

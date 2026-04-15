## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** encrypted-index-strategy
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** Low-Cost Mitigation Bundle. (1) Per-field HKDF keys (already decided in per-field-key-binding). (2) Power-of-2 response padding (~50 lines, at most 2x bandwidth). (3) LeakageProfile documentation on EncryptionSpec.
**Candidates evaluated:** Mitigation Bundle (52/60), Full ORAM (23/60), Differential Privacy (31/60)
**Key rationale:** KB's top 3 recommendations. Zero-to-low cost. ORAM deferred (10-100x overhead). Documents inherent leakage tradeoffs of DET/OPE already accepted in parent ADR.

---

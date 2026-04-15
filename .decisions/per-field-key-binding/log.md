## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** field-encryption-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** HKDF derivation from master key — `HKDF-SHA256(masterKey, tableName:fieldName)` at schema construction. Zero caller configuration. Per-field keys are cryptographically independent.
**Candidates evaluated:** HKDF Derivation (54/60), Explicit Key IDs (41/60), Key Routing Table (40/60)
**Key rationale:** KB recommends per-field HKDF as "low cost, high value." Eliminates cross-field frequency correlation automatically.

---

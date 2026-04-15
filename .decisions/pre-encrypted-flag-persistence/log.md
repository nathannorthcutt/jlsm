## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** pre-encrypted-document-signaling
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — closed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-closed
**Reason:** Non-issue — persisting a write-side flag adds dead metadata with no consumer. Per-field encryption markers (if ever needed) belong in `client-side-encryption-sdk`, not here. The parent ADR intentionally designed the flag as ephemeral.

---

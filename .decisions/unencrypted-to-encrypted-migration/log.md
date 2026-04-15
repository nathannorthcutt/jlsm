## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** field-encryption-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** Compaction-Driven Migration. Same mechanism as key rotation — compaction reads unencrypted, writes encrypted. Online, zero additional I/O. Bidirectional (also supports encrypted → unencrypted).
**Candidates evaluated:** Compaction-Driven (52/60), Background Task (37/60), Schema-Version Gate (28/60)
**Key rationale:** Reuses key rotation infrastructure. No stop-the-world. Mixed reads work via schema version tag on SSTable.

---

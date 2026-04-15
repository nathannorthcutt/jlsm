## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** field-encryption-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent (WD-09 batch)
**Event:** decision-confirmed
**Recommendation:** Envelope Encryption + Compaction-Driven Re-Encryption. KEK wraps versioned DEKs; 4-byte version tag in ciphertext; compaction re-encrypts to current DEK. Key registry file alongside manifest.
**Candidates evaluated:** Envelope + Compaction (52/60), Full Table Rebuild (29/60), Lazy Read Re-Encryption (26/60)
**Key rationale:** Zero additional I/O — piggybacks on existing compaction. Production-proven (CockroachDB). Mixed versions coexist safely.

---

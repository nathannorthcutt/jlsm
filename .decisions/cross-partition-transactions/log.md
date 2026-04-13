## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** table-partitioning
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Full evaluation started. No KB coverage on distributed transaction protocols — research commissioned before evaluation. WD-08 dependency on this decision found to be incorrect (query execution is read-only) and removed.

**Files written/updated:**
- `research-brief.md` — research commission

---

## 2026-04-13 — research-received

**Agent:** Research Agent
**Event:** research-received
**Summary:** KB article written: `.kb/distributed-systems/transactions/cross-partition-protocols.md`. Covers 2PC, Calvin, Percolator, OCC. Recommends Percolator as best fit for jlsm's peer-to-peer range-partitioned architecture. Research brief status updated to complete.

---

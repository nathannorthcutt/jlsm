## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** scatter-gather-query-execution
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Top-N Pushdown with Keyset Pagination confirmed. Falsification validated approach for key-ordered scans; secondary-sort noted as out of scope.

### Deliberation Summary

**Rounds of deliberation:** 1 (user pre-accepted all changes)
**Recommendation presented:** Top-N Pushdown with Keyset Pagination
**Final decision:** Top-N Pushdown with Keyset Pagination (same as presented)

**Topics raised during deliberation:**
- Falsification raised secondary-sort concern (ORDER BY non-key column)
  Response: jlsm's query model is key-ordered; secondary-sort is out of scope
- Falsification raised partition pruning as missing candidate
  Response: Already decided in scatter-gather ADR; complementary, not competitive

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- User pre-accepted all changes for batch processing

**Override:** None

**Confirmation:** User confirmed with: pre-accepted all changes

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — scored candidate matrix with falsification results
- `constraints.md` — constraint profile

**KB files read during evaluation:**
- [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md)
- [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md)

---

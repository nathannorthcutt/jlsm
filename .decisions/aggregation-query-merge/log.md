## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** scatter-gather-query-execution
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Two-Phase Partial Aggregation with Cardinality Guard confirmed. Falsification revised Scale and Resources scores; DISTINCT aggregates scoped out.

### Deliberation Summary

**Rounds of deliberation:** 1 (user pre-accepted all changes)
**Recommendation presented:** Two-Phase Partial Aggregation with Cardinality Guard
**Final decision:** Two-Phase Partial Aggregation with Cardinality Guard (same as presented)

**Topics raised during deliberation:**
- Falsification identified GROUP BY cardinality as load-bearing assumption
  Response: Revised Scale (5→4) and Resources (5→4) scores; added cardinality guard
- Falsification identified DISTINCT aggregates as potential hard disqualifier
  Response: Explicitly scoped out DISTINCT aggregates
- Falsification identified barrier semantics at coordinator
  Response: Revised Operational (4→3); noted tail latency concern

**Constraints updated during deliberation:**
- None — constraints derived from parent ADRs, no changes needed

**Assumptions explicitly confirmed by user:**
- User pre-accepted all changes for batch processing

**Override:** None
**Override reason:** N/A

**Confirmation:** User confirmed with: pre-accepted all changes ("I will not be available to prompt so I accept all changes")

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — scored candidate matrix (revised after falsification)
- `constraints.md` — constraint profile

**KB files read during evaluation:**
- [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md)
- [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md)

---

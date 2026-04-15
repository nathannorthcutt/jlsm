## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Credit-Based + Flow API composite confirmed after deliberation. User raised concern about server-side cursor management interaction; research commissioned and incorporated — continuation tokens align naturally with credit model, strengthening the recommendation.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** Credit-Based + Flow API (composite)
**Final decision:** Credit-Based + Flow API (composite) *(same as presented)*

**Topics raised during deliberation:**
- User concerned that "not solving server-side cursor management" could force a different backpressure approach. Research commissioned on distributed scan cursor management.
  Response: research showed continuation tokens (stateless) align naturally with credit-based backpressure — demand signals carry tokens, partitions are stateless between requests. Strengthened the recommendation rather than challenging it.

**Constraints updated during deliberation:**
- None — cursor model is orthogonal to backpressure mechanism

**Assumptions explicitly confirmed by user:**
- Continuation tokens (stateless cursors) are the right cursor model for scatter-gather
- Snapshot binding strategy is a separate implementation decision

**Override:** None.

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
- [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md)

---

## 2026-04-13 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Commissioned research on distributed scan cursor management during deliberation. User concerned that cursor model choice could affect backpressure recommendation.

**Files written/updated:**
- `.kb/distributed-systems/query-execution/distributed-scan-cursors.md` — new KB article

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Resumed deferred decision for scatter-backpressure. Constraint profile captured from parent ADRs (scatter-gather-query-execution, connection-pooling, transport-traffic-priority).

**Files written/updated:**
- `constraints.md` — constraint profile

**KB files read:**
- [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** transport-abstraction-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

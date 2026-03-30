---
problem: "table-partitioning"
created: "2026-03-16"
---

# Decision Log — table-partitioning

---

## 2026-03-16 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured. Top binding constraints: combined query efficiency, composability, correctness on partition ops.

**Files written/updated:**
- `constraints.md` — full six-dimension constraint profile

**KB files read:**
- (none yet — survey begins next step)

---

## 2026-03-16 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Range Partitioning with Per-Partition Co-located Indices confirmed after deliberation. Combined query efficiency was the binding constraint.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Range Partitioning with Per-Partition Co-located Indices
**Final decision:** Range Partitioning with Per-Partition Co-located Indices *(same as presented)*

**Topics raised during deliberation:**
- None — user confirmed immediately.

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- Partition count stays moderate (10-100)
- Combined queries are a primary use case
- jlsm remains a composable library

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md)
- [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md)

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** partition-replication-protocol, cross-partition-transactions, vector-query-partition-pruning, sequential-insert-hotspot, partition-aware-compaction
**Summary:** 5 out-of-scope items promoted to tracked deferred decisions during /curate session.

---

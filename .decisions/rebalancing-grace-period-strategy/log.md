## 2026-03-20 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Problem directory and constraints.md written for rebalancing grace period strategy. Driven by engine-clustering domain analysis.

**Files written/updated:**
- `constraints.md` — initial constraint profile

**KB files read:**
- None — this decision is an implementation design that layers on existing ADRs (cluster-membership-protocol, partition-to-node-ownership)

---

## 2026-03-20 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Eager Reassignment with Deferred Cleanup confirmed after deliberation. User challenged original View-Epoch Grace Period proposal (unavailability window), revised to eager reassignment. User noted object storage eliminates data migration concern.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** View-Epoch Grace Period (original)
**Final decision:** Eager Reassignment with Deferred Cleanup *(revised from original after user challenge)*

**Topics raised during deliberation:**
- User challenged unavailability window: "Does that indicate that partitions/tables can be completely unserved for whatever the delta time is for the grace period?"
  Response: Yes — revised to eager reassignment model where partitions are immediately reassigned, eliminating the unavailability window.
- User asked about replication compatibility: "Would there be any issues with replicas and their assignment in this setup?"
  Response: No — HRW top-R ranking naturally supports replication. Replicas are the next-highest-weight nodes in the same ranking.
- User noted object storage eliminates data migration: "since we're designing to run off of object storage we don't have to wait for data to transit, the new node already has access to it immediately"
  Response: Incorporated — takeover is WAL replay only, not data transfer. This makes eager reassignment very low cost.

**Constraints updated during deliberation:**
- Added: object storage means no data migration needed for takeover
- Added: WAL replay is the only takeover cost (bounded, fast)

**Assumptions explicitly confirmed by user:**
- Object storage provides immediate data access for new owners
- WAL replay covers in-flight memtable data
- Replication will be added later — architecture should be ready

**Override:** None — recommendation was revised based on valid challenge
**Confirmation:** User confirmed with: "confirm"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — updated with revised candidate
- `constraints.md` — no changes (object storage context noted in ADR directly)

**KB files read during evaluation:**
- None — implementation design layering on existing ADRs

---

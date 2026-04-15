## 2026-04-11 — deferred

**Agent:** Curation Agent
**Event:** deferred
**Parent ADR:** per-block-checksums
**Summary:** Deferred pending replication specification. Repair requires a source of truth; most robust strategies (read repair, anti-entropy, targeted replica fetch) require replication.

---

## 2026-04-14 — re-deferred

**Agent:** Work Plan (WD-02)
**Event:** re-deferred
**Summary:** Re-deferred — repair strategies require partition replication (WD-07/F32) which was not yet specified. Updated KB coverage noted.

---

## 2026-04-15 — decision-confirmed

**Agent:** Work Plan (WD-10)
**Event:** decision-confirmed
**Resolution:** Promoted from deferred to accepted — new spec F48 authored. Layered repair strategies: quarantine + compaction (single-node), read repair + anti-entropy + targeted replica fetch (replicated). 46 requirements, adversarially reviewed.
**Spec:** F48 (APPROVED)

---

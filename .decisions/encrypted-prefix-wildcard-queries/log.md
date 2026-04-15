## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** encrypted-index-strategy
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — re-deferred

**Agent:** Architect Agent (WD-09 batch)
**Event:** re-deferred
**Reason:** KB research complete. Recommended approach: prefix tokenization + DET (~100 lines). Deferred because core encryption features must be implemented first AND leakage is strictly more than DET equality — needs per-field keys and leakage documentation (index-access-pattern-leakage) in place before shipping.

---

## 2026-04-15 — decision-confirmed

**Agent:** Work Plan (WD-12)
**Event:** decision-confirmed
**Resolution:** Promoted from deferred to accepted — resolved by F46 (Encrypted Prefix Index). Prefix tokenization + DET encryption with configurable min/max prefix length, LsmInvertedIndex integration, documented L4 leakage profile.
**Spec:** F46 (APPROVED, 25 reqs)

---

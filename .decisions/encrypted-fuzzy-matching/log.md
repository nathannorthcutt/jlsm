## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** encrypted-index-strategy
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — re-deferred

**Agent:** Architect Agent (WD-09 batch)
**Event:** re-deferred
**Reason:** KB research complete. Recommended approach: LSH + Bloom filter (~200 lines). Deferred because core encryption features must be implemented first AND accuracy guarantees require empirical tuning (n-gram size, LSH parameters, Bloom FPR). No production encrypted DB supports fuzzy matching — novel territory.

---

## 2026-04-15 — decision-confirmed

**Agent:** Work Plan (WD-12)
**Event:** decision-confirmed
**Resolution:** Promoted from deferred to accepted — resolved by F47 (Encrypted Fuzzy Matcher). LSH + Bloom filter with AES-GCM encryption, character n-gram shingling, configurable accuracy parameters, documented L2 leakage profile.
**Spec:** F47 (APPROVED, 27 reqs)

---

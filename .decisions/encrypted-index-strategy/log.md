## 2026-03-18 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured for encrypted index strategy. Inherits scale/resources/complexity/fit from field-encryption-api-design. Top constraint: correctness — never return wrong results from encrypted index.

**Files written/updated:**
- `constraints.md` — constraint profile

**KB files read:**
- [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
- [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)

---
## 2026-03-18 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Static Capability Matrix with tiered full-text search confirmed. All three SSE tiers included in scope after user requested Tier 3 inclusion — no technical blocker, high complexity budget.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** Static Capability Matrix (initially without full-text, then with T1+T2, then with T1+T2+T3)
**Final decision:** Static Capability Matrix with all three full-text tiers (same approach, expanded scope)

**Topics raised during deliberation:**
- User asked why full-text search was out of scope — corrected: DET enables keyword search via inverted index, OPE positions enable phrase search
  Response: Updated capability matrix to include keyword and phrase columns, researched SSE tiers for KB
- User requested Tier 3 SSE inclusion — no technical blocker identified
  Response: Added T3 to capability matrix and ADR scope

**Constraints updated during deliberation:**
- None — constraint profile unchanged, scope expanded within existing constraints

**Assumptions explicitly confirmed by user:**
- All three SSE tiers should be in scope
- Complexity budget supports ~800 lines for Tier 3 SSE implementation
- No technical blocker for full SSE encrypted inverted index

**Override:** None

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — three candidates scored
- KB updated: `searchable-encryption-schemes.md` — added encrypted full-text search tiers

**KB files read during evaluation:**
- [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
- [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)

---
## 2026-03-18 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** User requested SSE phrase/proximity search on encrypted text be included in scope. Commissioning research on encrypted inverted index with SSE for richer full-text capabilities beyond DET keyword match.

**Files written/updated:**
- None yet — research pending

**KB files read:**
- [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md) — existing SSE overview, needs deeper implementation detail for inverted index integration

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching, encrypted-cross-field-joins, index-access-pattern-leakage
**Summary:** 4 out-of-scope items promoted to tracked deferred decisions during /curate session.

---

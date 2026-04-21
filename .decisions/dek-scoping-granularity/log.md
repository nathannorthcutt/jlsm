# Decision Log — dek-scoping-granularity

## 2026-04-21 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** DEK identity pinned to `(tenantId, domainId, tableId, dekVersion)`. Per-table scope is structurally forced by ADR A's encrypt-once invariant; domain tier retains grouping semantics (multiple tables per domain). High confidence — design space collapsed to one candidate.

### Deliberation Summary

**Rounds of deliberation:** 1 question (confirm domain-groups-tables vs domain=table; other alternatives structurally ruled out).
**Recommendation presented:** Option A — per-(tenant, domain, table) DEK.
**Final decision:** Same as presented.

**Topics raised:** None outside the single scoping question.

**Assumptions explicitly confirmed:**
- Domain tier is a grouping boundary (not 1:1 with table).
- Encrypt-once-at-ingress invariant from ADR A is preserved.

**Falsification outcomes:**
- Per-SSTable rejected structurally (violates encrypt-once).
- Per-object rejected structurally (registry size).
- Domain=table rejected structurally (collapses three-tier).
- Remaining candidate trivially passes.

**Override:** None.

**Confidence:** High. Structurally determined.

**Confirmation:** User confirmed via "Per-(tenant, domain, table) DEK (Option A)".

**Files written:**
- `adr.md`, `constraints.md`, `evaluation.md`

**KB files read:** inherited from ADR A.

---

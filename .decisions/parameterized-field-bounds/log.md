## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** bounded-string-field-type
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. Constraint profile captured. Two groups identified: layout-affecting bounds (arrays) and validation/encryption bounds (numerics).

**Files written/updated:**
- `constraints.md` — constraint profile

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** BoundedArray sealed permit confirmed. Numeric bounds deferred — OPE 2-byte cap means custom range bounds are unused today.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** BoundedArray only (revised from Split approach after falsification)
**Final decision:** BoundedArray only (same as revised presentation)

**Topics raised during deliberation:**
- Falsification discovered OPE 2-byte cap makes numeric bounds unnecessary
- Original Split approach (BoundedArray + ValueBounds on FieldDefinition) was revised
  to BoundedArray-only based on falsification finding

**Constraints updated during deliberation:**
- None — falsification narrowed the solution, not the constraints

**Assumptions explicitly confirmed by user:**
- OPE 2-byte cap means numeric bounds are not needed now
- BoundedArray follows the BoundedString precedent

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — candidate scoring (3 candidates)
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md)

---

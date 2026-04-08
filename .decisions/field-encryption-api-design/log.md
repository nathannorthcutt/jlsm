## 2026-03-18 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured for field encryption API design. Six dimensions specified, no unknowns. Top constraints: fit with existing type system, type-aware encryption per field, scale+latency on hot path.

**Files written/updated:**
- `constraints.md` — full constraint profile

**KB files read:**
- [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
- [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)
- [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md)

---
## 2026-03-18 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Schema Annotation (FieldDefinition carries EncryptionSpec) confirmed after deliberation. Sealed EncryptionSpec with 5 variants maps to researched encryption families.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Schema Annotation
**Final decision:** Schema Annotation (same as presented)

**Topics raised during deliberation:**
- None — user confirmed on first presentation

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- FieldDefinition can be extended from 2 to 3 components
- One key per table is sufficient initially
- Dispatch table pattern can incorporate encrypt/decrypt

**Override:** None

**Confirmation:** User confirmed with: "I agree, confirmed"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes
- `evaluation.md` — three candidates scored

**KB files read during evaluation:**
- [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
- [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)
- [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md)

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** encryption-key-rotation, wal-entry-encryption, unencrypted-to-encrypted-migration, per-field-key-binding
**Summary:** 4 out-of-scope items promoted to tracked deferred decisions during /curate session.

---

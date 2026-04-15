## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** bounded-string-field-type
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. Existing serialization format already embeds schema version (2-byte short). Decision is about migration POLICY, not mechanism.

**Files written/updated:**
- `constraints.md` — constraint profile

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Compaction-time migration + on-demand scan composite confirmed. Falsification identified cold-data gap in pure compaction-time approach; composite closes it.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** Compaction-time + on-demand scan (revised from pure compaction-time after falsification)
**Final decision:** Same as revised presentation

**Topics raised during deliberation:**
- Falsification identified cold bottom-level SSTable problem — compaction may never reach inactive key ranges
- Composite candidate (compaction-time + on-demand scan) proposed by falsification agent and adopted
- Fit score challenged: migration hook lives in jlsm-table, not jlsm-core — no dependency inversion needed

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- Composite approach (compaction + on-demand scan) is the right design
- Per-SSTable min-schema-version tracking is acceptable overhead

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — candidate scoring (3 candidates + composite)
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md)

---

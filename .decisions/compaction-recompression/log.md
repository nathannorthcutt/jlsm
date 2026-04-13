## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** sstable-block-compression-format
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-12 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured. Problem scoped to compactor writer creation path and per-level codec selection.

---

## 2026-04-12 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Writer-factory injection with per-level codec policy confirmed. Resolves both compaction-recompression and adaptive-compression-strategy in one design.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** A — Compactor-level output codec (initial), then D — Writer-factory injection (revised after falsification)
**Final decision:** D+B merged — Writer-factory injection with per-level codec policy

**Topics raised during deliberation:**
- Falsification surfaced missing Candidate D using existing SSTableWriterFactory
  Response: Revised recommendation from A to D
- User challenged rejection of B as "duplicating deferred scope"
  Response: Agreed — per-level policy is free with factory approach. Merged D+B.
- User flagged compactor writer state assumption as potential blocker
  Response: Verified code — no state carries between writers, mechanical replacement

**Constraints updated during deliberation:** None

**Assumptions explicitly confirmed by user:**
- Per-level codec policy should be solved now, not deferred
- SpookyCompactor writer creation has no implicit state

**Override:** None

**Confirmation:** User confirmed with: "yes, merge it"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes

**KB files read during evaluation:**
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)
- [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md)

---

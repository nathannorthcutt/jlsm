## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** vector-type-serialization-encoding
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** SparseVectorType sealed permit + inverted index storage confirmed. Reuses LsmInvertedIndex with integer dimension IDs. SPARSE_VECTOR index type with dot-product scoring.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** SparseVectorType + inverted index
**Final decision:** Same as presented

**Override:** None
**Confirmation:** User confirmed with: "yes"

**KB files read:**
- [`.kb/algorithms/vector-encoding/sparse-vector-representations.md`](../../.kb/algorithms/vector-encoding/sparse-vector-representations.md)

---

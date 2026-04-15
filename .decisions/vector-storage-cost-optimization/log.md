## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** vector-type-serialization-encoding
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. KB has 5 quantization articles + TurboQuant/SAQ enrichments. Decision is about architecture (where config lives), not algorithm selection.

**Files written/updated:**
- `constraints.md` — constraint profile
- `evaluation.md` — 3 candidates scored

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** QuantizationConfig on IndexDefinition + custom SPI escape hatch confirmed. Implementation priority: SQ8 → RaBitQ → BQ. Falsification identified hybrid approach (built-in + SPI) as strictly better than pure IndexDefinition config.

### Deliberation Summary

**Rounds of deliberation:** 1
**Recommendation presented:** IndexDefinition config + custom SPI (hybrid, revised after falsification)
**Final decision:** Same as presented

**Topics raised during deliberation:**
- Falsification identified QuantizationPolicy SPI as missing candidate that ties the recommendation
- Hybrid approach (built-in configs + Custom SPI) adopted as strictly dominant

**Constraints updated during deliberation:**
- None

**Assumptions explicitly confirmed by user:**
- Quantization is per-index, not per-field
- SQ8 is the right first implementation

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1

**KB files read during evaluation:**
- [`.kb/algorithms/vector-quantization/CLAUDE.md`](../../.kb/algorithms/vector-quantization/CLAUDE.md)
- [`.kb/algorithms/vector-quantization/scalar-quantization.md`](../../.kb/algorithms/vector-quantization/scalar-quantization.md)
- [`.kb/algorithms/vector-quantization/rabitq.md`](../../.kb/algorithms/vector-quantization/rabitq.md)

---

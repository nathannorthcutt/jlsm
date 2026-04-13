## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** compression-codec-api-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-12 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured. Problem reframed from "codec-specific configuration" to full dictionary compression lifecycle including tiered Panama FFM integration and SSTable writer buffering.

**Files written/updated:**
- `constraints.md` — full constraint profile

**KB files read:**
- [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md)
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

---

## 2026-04-12 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Commissioned research on ZSTD dictionary lifecycle in LSM storage engines. New KB entry written at `.kb/algorithms/compression/zstd-dictionary-compression.md`. Also conducted feasibility spike on pure-Java ZSTD dictionary decompression: neither aircompressor nor noop-dev supports it today, but adding it is mechanical (~150-175 lines, reusing existing table parsers).

---

## 2026-04-12 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Writer-orchestrated approach confirmed. Codec stays stateless; dictionary lifecycle managed by writer + ZstdDictionaryTrainer. Tiered Panama FFM detection for native ZSTD with pure-Java decompressor fallback.

### Deliberation Summary

**Rounds of deliberation:** 4 (constraint collection, ZSTD research discussion, falsification review, dictionary decompression spike)
**Recommendation presented:** A — Writer-Orchestrated, Codec Stays Stateless
**Final decision:** A — Writer-Orchestrated, Codec Stays Stateless (same as presented)

**Topics raised during deliberation:**
- User questioned whether ZSTD was too complex for pure-Java (~5000 lines claim)
  Response: Clarified that LZ4 is ~200 lines, ZSTD full is ~5000; decompressor-only is ~1500-2200
- User asked about SIMD/Panama acceleration for ZSTD
  Response: ZSTD designed for scalar ILP, not SIMD; marginal gains from Vector API
- User asked about native code invocation via Panama (like JSON SIMD)
  Response: Yes, tiered detection pattern reuses TierDetector approach; Panama FFM faster than JNI
- User asked about dictionary applicability and lifecycle
  Response: Commissioned research — wrote zstd-dictionary-compression.md KB entry
- User flagged pure-Java dictionary decompression as unverified risk
  Response: Conducted feasibility spike — mechanical addition of ~150-175 lines to existing decompressor

**Constraints updated during deliberation:**
- Complexity Budget: set to "unlimited" (user confirmation)

**Assumptions explicitly confirmed by user:**
- Cross-platform readability is a hard requirement
- Complexity budget is unlimited
- Pure-Java dictionary decompression feasibility (~150-175 lines) resolves the main risk

**Override:** None

**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes after initial capture
- `evaluation.md` — three-candidate comparison (A: 54, B: 39, C: 50)

**KB files read during evaluation:**
- [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md)
- [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

---

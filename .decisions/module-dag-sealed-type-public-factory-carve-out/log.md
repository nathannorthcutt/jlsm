## 2026-04-25 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Problem directory created for the module-DAG sealed-type
public-factory carve-out pattern. Constraints captured across all six
dimensions; binding constraints are JPMS per-package visibility,
exported-package boundary as trust mechanism, and HKDF cryptographic
backstop.

**Files written/updated:**
- `constraints.md` — full six-dimension profile

**KB files read:**
- None — Java/JPMS pattern decision; grounded in language-spec
  invariants and existing ADRs.

---

## 2026-04-25 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Public static factory + non-exported package +
package-private constructor confirmed as the canonical pattern for
sealed-permitted internal types whose construction caller lives in a
sibling public package within the same module. Decision made
autonomously per session directive (non-interactive mode).

### Deliberation Summary

**Rounds of deliberation:** 1 (autonomous)
**Recommendation presented:** Candidate A — Public static factory +
non-exported package + package-private ctor
**Final decision:** Candidate A *(same as presented — no override)*

**Topics raised during deliberation:**
- Constructor visibility vs. exported-package boundary as the
  load-bearing trust mechanism.
  Response: The `module-info.java` exports clause is the load-bearing
  layer; constructor visibility is intra-module hygiene. The
  cryptographic backstop (HKDF binding from primitives-lifecycle R11)
  is the final defence.
- Whether spec text mandating "non-public constructor" should be
  amended to use module-graph-aware phrasing.
  Response: Yes — the ADR documents the canonical phrasing for use in
  future specs. Amendment of existing R8f text in
  `sstable.footer-encryption-scope` is tracked separately (WD-02 retro
  or follow-up spec-write pass).
- Reflection-based factory considered and rejected.
  Response: R8f bullet 4 / R8h declare reflection access explicitly
  out-of-threat-model. Using reflection for legitimate construction
  inverts the project's stated posture.

**Constraints updated during deliberation:**
- None — full profile captured at intake.

**Assumptions explicitly confirmed by the autonomous deliberation:**
- The exported-package boundary in `module-info.java` is and remains
  the load-bearing trust mechanism. (Mitigated by R8j operator
  guidance forbidding production `--add-exports`.)
- The HKDF scope binding (R11) remains intact as the cryptographic
  backstop. (Verified against primitives-lifecycle spec.)
- 1:1 factory-to-constructor delegation is mandated (no defaulting
  divergence).

**Override:** None.

**Confirmation:** Confirmed autonomously per session directive — caller
explicitly requested non-interactive deliberation.

**Files written after confirmation:**
- `adr.md` — decision record v1
- `evaluation.md` — four candidates scored; A=53, B=28, C=23, D=20
- `constraints.md` — no changes after intake

**KB files read during evaluation:**
- None — pattern decision grounded in Java SE 25 access rules,
  JPMS exports semantics, and existing project ADRs.

**Related ADRs surveyed:**
- [`.decisions/table-handle-scope-exposure/adr.md`](../table-handle-scope-exposure/adr.md) v2
- [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md)
- [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md)

---

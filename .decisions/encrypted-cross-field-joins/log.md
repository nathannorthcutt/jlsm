## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** encrypted-index-strategy
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-14 — re-deferred

**Agent:** Architect Agent (WD-09 batch)
**Event:** re-deferred
**Reason:** KB research complete. Same-key DET joins already work. Cross-key joins require proxy re-encryption with leakage amplification risk (Hoover et al. 2024: >15% plaintext recovery). Deferred until distributed join execution is implemented AND concrete cross-key use case emerges.

---

## 2026-04-15 — re-deferred

**Agent:** Work Plan (WD-12)
**Event:** re-deferred
**Reason:** Same-key DET equi-joins already work with zero special handling. Cross-key joins have severe leakage amplification (Hoover et al. >15% plaintext recovery from cross-table equality patterns). All cross-key strategies require client-side key material at query time. No concrete use case identified. F40 (Distributed Join Strategy) is now APPROVED but does not address encryption. Re-deferred until concrete use case emerges AND SSE join tokens (Shafieinejad et al.) are evaluated against jlsm's threat model.

---

# Decision Log — tenant-key-revocation-and-external-rotation

## 2026-04-21 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** New ADR opened for handling external rotation/revocation of tenant KEKs under flavor 3 (BYO-KMS). Downstream of `three-tier-key-hierarchy`. User flagged this as a separate concern during ADR A deliberation.

**Files written:**
- `.decisions/tenant-key-revocation-and-external-rotation/constraints.md`

---

## 2026-04-21 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Rekey coordination = API primary + opt-in polling; execution = streaming paginated with dual-reference migration; failure = three-state machine (healthy/grace-read-only/failed) with N=5 and 1h defaults; explicit decommission deferred to tenant-lifecycle ADR.

### Deliberation Summary

**Rounds of deliberation:** 2 scoping questions + 1 design confirmation.
**Recommendation presented:** As decided — no candidate comparison since constraints from ADR A narrow the design to near-determinism.

**Topics raised during deliberation:**
- Rekey posture (API vs polling vs both) → user chose both.
- Failure semantics (hard/soft/hybrid) → user chose hybrid (hard fail with bounded grace).
- Explicit decommission scope → user confirmed deferral.

**Assumptions explicitly confirmed:**
- Tenant operators will coordinate rekey via API before revoking old CMK.
- Polling is optional defence-in-depth, not a primary detection path.
- N=5 / 1h defaults are configurable and revisable via production telemetry.

**Falsification outcomes:**
- Proof-of-control sentinel: mitigated via nonce + timestamp freshness (≤5min window).
- Classifier false positives: N=5 with jittered backoff tolerates ~30min transient issues.
- Stuck rekey progress: 24h-stale progress files emit observable events.
- Dual-reference registry bloat: transient (bounded to shard-commit window, ~100ms).

**Override:** None.

**Confidence:** Medium-high.

**Confirmation:** User confirmed via "Confirm as stated".

**Files written after confirmation:**
- `adr.md`
- `evaluation.md`

**KB files read:**
- [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md)
- [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)

**Specs read:**
- `.spec/domains/encryption/primitives-lifecycle.md`
- `.spec/domains/wal/encryption.md`

**Deferred stub created:**
- `tenant-lifecycle` (decommission semantics) — out of scope for this ADR.

---

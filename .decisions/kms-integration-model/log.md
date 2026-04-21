# Decision Log — kms-integration-model

## 2026-04-21 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** KmsClient SPI shape pinned (wrap/unwrap/isUsable + exception hierarchy); 30min cache TTL default; 3-retry exponential backoff for transient errors; 10s per-call timeout; encryption context carries tenantId+domainId+purpose on every operation; observability via KmsObserver interface.

### Deliberation Summary

**Rounds of deliberation:** 1 confirmation question; constraints inherited from ADRs A and D narrowed the design to defaults-tuning only.

**Recommendation presented:** As described; user confirmed.

**Assumptions explicitly confirmed:**
- 30min TTL is a reasonable security-vs-performance balance, configurable
- 3 retries with exp-backoff is industry-standard
- KmsClient implementations own connection pooling (no jlsm-side pooling)
- Observability is opt-in via KmsObserver (no enforced audit sink)

**Override:** None.

**Confidence:** High. Design space collapsed to defaults-tuning by ADRs A and D.

**Confirmation:** User confirmed via "Confirm as stated".

**Files written:**
- `adr.md`, `constraints.md`

**KB files read:** inherited from ADRs A and D.

---

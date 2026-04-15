## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Periodic Rediscovery + Optional Reactive Watch confirmed. User challenged periodic-only approach citing cluster repair latency; scope widened to include optional watchSeeds() SPI extension for sub-second push-based discovery.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** Periodic Rediscovery Loop (single candidate)
**Final decision:** Periodic Rediscovery + Optional Reactive Watch (composite) *[REVISED]*

**Topics raised during deliberation:**
- User challenged: "finding new nodes and balancing is definitely a high priority otherwise it can take a long time for clusters to repair." Requested sub-second discovery capability.
  Response: widened scope to include optional watchSeeds() SPI method following existing default-no-op pattern. Periodic loop becomes the universal fallback.

**Constraints updated during deliberation:**
- Accuracy: discovery latency elevated from "configurable interval" to "sub-second for watch-capable providers"

**Assumptions explicitly confirmed by user:**
- Watch SPI extension is warranted by cluster repair priority
- Composite approach (watch + periodic fallback) covers all provider types

**Override:** None — revision accepted.

**Confirmation:** User confirmed with: "confirm"

**Files written after confirmation:**
- `adr.md` — decision record v1 (revised composite)
- `constraints.md` — no changes (accuracy interpretation widened but profile unchanged)

**KB files read during evaluation:**
- [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Resumed deferred decision for continuous-rediscovery. Constraint profile captured from parent ADR discovery-spi-design.

**Files written/updated:**
- `constraints.md` — constraint profile
- `evaluation.md` — 4 candidates evaluated

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** discovery-spi-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

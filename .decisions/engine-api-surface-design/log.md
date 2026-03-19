## 2026-03-19 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Constraint profile captured for engine API surface design. Key narrowing constraint: dual-mode API (embedded direct access + remote client proxy) using the same interface contract.

**Files written/updated:**
- `constraints.md` — full constraint profile

**KB files read:**
- None yet — KB survey pending

---

## 2026-03-19 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction confirmed after deliberation. Two key refinements added during deliberation: (1) Table handles hold state and must be AutoCloseable, (2) engine must assume clients will NOT close handles — needs source-attributed tracking with greedy-source-first eviction and diagnostic information on forced closure.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** Interface-Based Handle Pattern
**Final decision:** Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction *(refined during deliberation)*

**Topics raised during deliberation:**
- User asked whether tables hold state (WAL, MemTable, SSTable readers, routing metadata, pending queries)
  Response: Yes — this means Table must be AutoCloseable with clear lifecycle semantics
- User raised that clients will NOT close handles reliably and engine must self-protect
  Response: Added source-attributed handle tracking, configurable limits, greedy-source-first eviction policy
- User required evicted handles provide diagnostic info so clients can fix leaks
  Response: HandleEvictedException carries table name, source ID, handle counts, allocation site, and eviction reason

**Constraints updated during deliberation:**
- Added handle lifecycle constraints: source attribution, eviction policy, diagnostic closure, configurable limits

**Assumptions explicitly confirmed by user:**
- Callers will NOT reliably close handles — engine must be self-protecting
- Greedy-source-first eviction is the right policy (punish leakers, protect well-behaved clients)
- Eviction must be diagnostic — client sees source, counts, allocation site, and reason

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — updated with handle lifecycle constraints

**KB files read during evaluation:**
- None — no direct KB coverage for this domain

---

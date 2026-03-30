## 2026-03-20 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Problem directory and constraints.md written for transport abstraction design.

**Files written/updated:**
- `constraints.md` — initial constraint profile

**KB files read:**
- None — API design decision

---

## 2026-03-20 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Message-Oriented Transport confirmed with threading model constraint added during deliberation. User flagged ThreadLocal + virtual threads risk from encryption code.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** Message-Oriented Transport
**Final decision:** Message-Oriented Transport with threading model constraint *(enhanced from original)*

**Topics raised during deliberation:**
- User flagged: CompletableFuture API means async code and virtual threads. ThreadLocal (used by encryption) + virtual threads is unsafe — need dedicated platform threads for encryption.
  Response: Added threading model constraint to ADR. Rule: encryption and ThreadLocal-dependent code on platform thread executor, transport I/O and scatter-gather coordination on virtual threads.

**Constraints updated during deliberation:**
- Added: threading model awareness — virtual threads for I/O, platform threads for ThreadLocal-dependent code

**Assumptions explicitly confirmed by user:**
- Dual executor model (virtual + platform) is acceptable
- This constraint applies across the clustering layer, not just transport

**Override:** None
**Confirmation:** User confirmed (implicit — accepted the enhanced recommendation)

**Files written after confirmation:**
- `adr.md` — decision record v1 with threading model constraint

**KB files read during evaluation:**
- None — API design decision

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** transport-traffic-priority, message-serialization-format, connection-pooling, scatter-backpressure
**Summary:** 4 out-of-scope items promoted to tracked deferred decisions during /curate session.

---

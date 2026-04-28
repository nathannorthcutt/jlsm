---
{
  "id": "engine.handle-lifecycle.state-machine",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "engine.handle-lifecycle",
  "_split_from": "engine.handle-lifecycle"
}
---

# engine.handle-lifecycle.state-machine — Handle State Machine

This spec was carved from `engine.handle-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R1. Every handle must be in exactly one of five states: OPEN, ACTIVE, IDLE, EXPIRED, or CLOSED. No other states are permitted.

R10. State transitions must be atomic with respect to concurrent observers. A handle must never be observed in an intermediate or inconsistent state.

R10a. When the engine is shut down (F05 R6), all non-CLOSED handles must transition to EXPIRED with reason ENGINE_SHUTDOWN before their resources are released.

R10b. When a table is dropped (F05 R26), all handles for that table must transition to EXPIRED with reason TABLE_DROPPED. This supersedes F05 R29 (which specified IllegalStateException without a reason).

### Idle timeout

R2. A newly created handle must begin in the OPEN state.

R3. A handle must transition from OPEN to ACTIVE when the first data operation begins on that handle.

R4. A handle must transition from ACTIVE to IDLE when the last in-progress data operation on that handle completes (successfully or with an error).

R5. A handle in the ACTIVE state must not be eligible for idle timeout, max-lifetime expiry, or eviction. Only IDLE and OPEN handles are eligible for these invalidation mechanisms.

R6. A handle must transition to EXPIRED when idle timeout, max-lifetime expiry, or eviction fires on that handle.

R6a. The EXPIRED transition must record the specific reason: one of IDLE_TIMEOUT, MAX_LIFETIME, EVICTION, ENGINE_SHUTDOWN, or TABLE_DROPPED.

R7. A handle must transition to CLOSED when the caller explicitly calls close(). The transition must be valid from any non-CLOSED state, including EXPIRED.

R7a. Calling close() on a CLOSED handle must be idempotent and succeed silently.

R8. The CLOSED state must be terminal. A handle in the CLOSED state must not transition to any other state.

R9. An expired handle must reject all subsequent data operations with an exception that carries the expiry reason. The close() method is not a data operation and must remain callable on an expired handle (per R7).



---

## Notes

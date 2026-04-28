---
{
  "id": "engine.handle-lifecycle",
  "version": 3,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "engine.in-process-database-engine"
  ],
  "invalidates": [
    "engine.in-process-database-engine.R29"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "engine-api-surface-design"
  ],
  "kb_refs": [
    "systems/database-engines/handle-lifecycle-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F34"
  ]
}
---

# engine.handle-lifecycle — Handle Lifecycle

## Requirements

### Handle state machine
R43. An exception thrown by an expired handle must carry: the table name, the expiry reason (IDLE_TIMEOUT, MAX_LIFETIME, EVICTION, ENGINE_SHUTDOWN, TABLE_DROPPED), the handle's creation time, and the handle's last-active time.

R44. The expiry reason must be queryable programmatically from the exception (not just in the message string).

### Concurrency

R45. The idle timeout scan and max-lifetime expiry must be safe to execute concurrently with handle creation, handle close, data operations, and eviction.

R46. Priority reservations and budget calculations must be safe to query and update concurrently from multiple threads without external synchronization.

R47. The acquisition timeout blocking mechanism must not hold any engine-wide lock while waiting. Blocked threads must be notified when a handle is released, not polled.

R48. The handle state machine (R1-R10) must be implemented with thread-safe state transitions. Concurrent transitions on the same handle (e.g., idle timeout firing while the caller starts an operation) must resolve deterministically: the first transition wins, the second observes the result.

R48a. When close() and expire() race on the same handle, the winning transition determines the final state. If close() wins, the handle is CLOSED and no expiry reason is recorded. If expire() wins, the handle is EXPIRED and a subsequent close() transitions it to CLOSED (per R7).

R48b. When a data operation begins (IDLE-to-ACTIVE transition) concurrently with an expiry attempt, the data operation must win if it observes the handle in IDLE state first. The expiry must observe the ACTIVE state and skip the handle.

### Input validation

R49. The engine builder must reject a negative idle timeout duration at build time with an illegal argument exception. Zero means disabled.

R50. The engine builder must reject a negative max-lifetime duration at build time with an illegal argument exception. Zero means disabled.

R51. The engine builder must reject a negative acquisition timeout at build time with an illegal argument exception.

R52. The engine builder must reject reserved handle counts that are negative at build time with an illegal argument exception.

R53. The engine builder must reject a configuration where the sum of all priority reservations equals or exceeds the global handle budget at build time with an illegal argument exception. At least one slot must remain for NORMAL priority.

R54. The engine builder must reject a per-table minimum floor that is zero or negative at build time with an illegal argument exception.

R55. The engine builder must reject a per-table minimum floor that exceeds the global handle budget at build time with an illegal argument exception.

R56. The engine builder must reject a scan interval that is zero or negative at build time with an illegal argument exception.

R57. The engine builder must reject a global handle budget of zero at build time with an illegal argument exception. The minimum global budget is 1.

### Handle resource cleanup

R58. Closing an EXPIRED handle via close() must release its registration from the handle tracker, consistent with F05 R77. An expired-but-unclosed handle remains registered in the tracker (for diagnostics and cleanup) but must not block new acquisitions (per R60).

R59. The engine's shutdown sequence must close() all EXPIRED handles after transitioning them (R10a), ensuring their registrations are released.

R60. EXPIRED handles must not count against the global or per-table handle budgets for the purposes of new handle acquisition. The budget calculation must use the count of non-EXPIRED, non-CLOSED handles as the active count.

R61. The idle timeout scan (R15) must also close() EXPIRED handles that have remained unclosed for longer than the idle timeout duration, releasing their registrations. This prevents budget exhaustion from callers that fail to close expired handles.

---


## Design Narrative

### Intent

Extend the F05 handle lifecycle with three capabilities that the engine-api-surface-design ADR explicitly deferred: (1) TTL-based expiry so stale handles are automatically reclaimed without relying solely on eviction pressure, (2) priority levels so administrative and high-priority operations are protected from resource starvation, and (3) cross-table budgets so a single hot table cannot monopolize the engine's handle capacity. These three features interact: priority determines eviction order, TTL provides a time-based reclamation floor, and budgets distribute capacity across tables.

### Why this approach

**Handle state machine (OPEN/ACTIVE/IDLE/EXPIRED/CLOSED):** Borrowed from connection pool patterns (HikariCP, PostgreSQL) because it cleanly separates "in use" from "reclaimable" — the critical distinction for both TTL and eviction. The ACTIVE state immunity (R5, R22) prevents mid-operation invalidation, which would cause data corruption or partial writes.

**Tiered reservation for priority:** The PostgreSQL reserved_connections pattern is simpler than weighted fair queuing and avoids starvation of admin operations, which is the primary use case.

**Soft-limit/hard-limit budgets:** Per-table soft limits with a global hard limit allow hot tables to burst beyond fair share when capacity exists, avoiding the waste of static partitioning.

### What was ruled out

- **Weighted fair queuing for priority:** Requires aging to prevent starvation; overkill for three fixed tiers.
- **Static per-table partitioning:** Wastes capacity when tables have uneven load.
- **Active handle preemption:** Interrupting in-progress operations requires operation-level rollback — far more complex than deferring expiry.

---
{
  "id": "engine.handle-lifecycle",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "F05"
  ],
  "invalidates": [
    "F05.R29"
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

R1. Every handle must be in exactly one of five states: OPEN, ACTIVE, IDLE, EXPIRED, or CLOSED. No other states are permitted.

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

R10. State transitions must be atomic with respect to concurrent observers. A handle must never be observed in an intermediate or inconsistent state.

R10a. When the engine is shut down (F05 R6), all non-CLOSED handles must transition to EXPIRED with reason ENGINE_SHUTDOWN before their resources are released.

R10b. When a table is dropped (F05 R26), all handles for that table must transition to EXPIRED with reason TABLE_DROPPED. This supersedes F05 R29 (which specified IllegalStateException without a reason).

### Idle timeout

R11. The engine must support a configurable idle timeout duration. The idle timeout measures the elapsed time since the handle last completed a data operation (transitioned from ACTIVE to IDLE).

R12. The idle timeout must be configurable at engine build time with a default of disabled (no idle timeout).

R13. A handle that has been IDLE for longer than the configured idle timeout must be transitioned to EXPIRED with reason IDLE_TIMEOUT.

R14. A handle in the OPEN state (never used) must be eligible for idle timeout based on its creation time.

R15. The engine must periodically scan for idle handles. The scan interval must be configurable at engine build time.

R15a. When the scan interval is not explicitly configured and the idle timeout is enabled, the default scan interval must be one-tenth the idle timeout duration, with a minimum of 100 milliseconds.

R15b. When the idle timeout is disabled and no scan interval is explicitly configured, the engine must not run idle scans.

R16. The idle timeout scan must not hold a global lock for the entire scan duration. The scan must be non-blocking with respect to handle creation and data operations.

R16a. If the idle scan selects a handle for expiry and that handle has concurrently transitioned to ACTIVE, the scan must skip that handle without error. The ACTIVE handle must not be expired.

### Max lifetime (TTL)

R17. The engine must support a configurable maximum lifetime duration. The maximum lifetime measures the elapsed time since the handle was created, regardless of usage.

R18. The maximum lifetime must be configurable at engine build time with a default of disabled (no maximum lifetime).

R19. A handle whose age exceeds the configured maximum lifetime must be transitioned to EXPIRED with reason MAX_LIFETIME.

R20. The engine must apply per-handle jitter to max-lifetime expiry times to prevent mass expiration of handles created in a burst. Handles created within the same wall-clock second must not all expire at the same instant.

R21. The jitter value for a handle must be determined at handle creation time and must not change for the lifetime of that handle.

R22. A handle in the ACTIVE state must not be expired by max-lifetime until it transitions to IDLE. The expiry must be deferred, not skipped.

R22a. When a deferred max-lifetime expiry fires on a handle that has transitioned to IDLE, the handle must be expired immediately. The deferred expiry must not require waiting for the next scan interval.

### Priority levels

R23. Every handle must be assigned a priority level at creation time. The priority levels must be, in descending order: ADMIN, HIGH, NORMAL.

R24. The default priority level for handles created without an explicit priority must be NORMAL.

R25. The engine builder must support configuring a reserved handle count for each priority level above NORMAL. Reserved handles are subtracted from the available budget before lower-priority handles can acquire.

R25a. The default reserved handle count for each priority level must be zero. When no reservations are configured, all priority levels compete equally for the full budget.

R26. A handle acquisition at NORMAL priority must be rejected when the number of available handles (total budget minus active count) is less than or equal to the sum of all higher-priority reservations. NORMAL is the lowest priority and must not trigger eviction of higher-priority handles to make room.

R27. A handle acquisition at HIGH priority must be rejected (or must trigger eviction of a NORMAL-priority handle per R29) when the number of available handles is less than or equal to the ADMIN reservation.

R28. A handle acquisition at ADMIN priority must succeed whenever any handle slot is available, regardless of reservations.

R29. Eviction under priority pressure must evict lower-priority handles before same-priority handles.

R29a. Within the same priority level, the existing LRU eviction policy (F05 R44-R46) applies.

R30. Priority levels must not affect the behavior of data operations on an already-acquired handle. All handles, regardless of priority, execute operations identically.

### Cross-table handle budgets

R31. The engine must support a global handle budget that bounds the total number of open handles across all tables.

R32. The global handle budget must be configurable at engine build time. The default must be the existing F05 max-total-handles configuration. F34's global budget supersedes F05's max-total-handles as the single source of truth for the engine-wide handle limit.

R33. Each table must have an individual handle budget that defaults to a fair share of the global budget: global budget divided by the number of ready tables.

R33a. The per-table budget must have a configurable minimum floor (see R34). The effective per-table budget must be the greater of the fair-share calculation and the floor.

R33b. When no tables are in the ready state, the per-table budget calculation must not be performed. Handle acquisition must be rejected because no ready table exists to serve it.

R34. The per-table budget minimum floor must be configurable at engine build time with a default of 1.

R35. When a table's handle count reaches its individual budget and the global budget has remaining capacity, the table may acquire handles up to the global budget. The per-table budget is a soft limit; the global budget is the hard limit.

R36. When the global budget is exhausted, eviction must select from the table with the highest handle count, consistent with F05 R81. Priority-based eviction (R29) applies within the selected table.

R37. When a new table is created or a table is dropped, per-table budgets must be recalculated.

R37a. Budget recalculation must not invalidate existing handles that exceed the new budget. Those handles remain valid until they are closed or evicted through normal pressure.

R37b. Budget recalculation during concurrent table creation or drop must be safe. Two concurrent recalculations must produce a consistent result reflecting all completed create/drop operations.

R38. The engine must expose per-table budget allocations and current usage through the metrics API (extending F05 R62).

### Interaction with F05 eviction

R39. When eviction is required to satisfy a handle acquisition, the engine must first attempt to evict IDLE or OPEN handles of the lowest priority level from the table with the highest handle count (consistent with F05 R81).

R39a. If no evictable handles of the lowest priority exist, the engine must escalate to the next higher priority level, up to but not including the requesting handle's own priority level.

R39b. If no evictable handles exist at any eligible priority level (all remaining handles are ACTIVE), the acquisition must either block or fail based on the configured acquisition timeout (R40-R42).

R40. The acquisition timeout must be configurable at engine build time with a default of zero (fail immediately if no handle is available after eviction attempts).

R41. When the acquisition timeout is zero, a failed acquisition (no evictable handles available) must throw an exception immediately without blocking.

R42. When the acquisition timeout is positive, a failed acquisition must block the calling thread for up to the configured duration. If a handle becomes available within the timeout, the acquisition must succeed. If the timeout expires, the acquisition must throw an exception.

R42a. Handle acquisition must be rejected immediately with an IllegalStateException during engine shutdown (after F05 R6 close() has been invoked), regardless of available capacity. The acquisition timeout must not apply during shutdown.

### Handle expiry diagnostics

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

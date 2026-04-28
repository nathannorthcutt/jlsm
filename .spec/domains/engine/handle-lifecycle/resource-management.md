---
{
  "id": "engine.handle-lifecycle.resource-management",
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

# engine.handle-lifecycle.resource-management — Priority, Budgets, and Eviction Integration

This spec was carved from `engine.handle-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

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



---

## Notes

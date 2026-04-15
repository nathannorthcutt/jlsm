---
title: "Database Handle Lifecycle and Resource Budgeting"
aliases: ["handle lifecycle", "connection pool", "session management", "TTL", "resource budget"]
topic: "systems"
category: "database-engines"
tags: ["handle", "session", "ttl", "priority", "budget", "pool", "lifecycle", "leak-detection"]
complexity:
  time_build: "N/A"
  time_query: "O(1) handle acquire"
  space: "O(max handles)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/catalog-persistence-patterns.md"
  - "systems/database-engines/in-process-database-engine.md"
decision_refs: ["handle-timeout-ttl", "handle-priority-levels", "cross-table-handle-budgets"]
sources:
  - url: "https://github.com/brettwooldridge/HikariCP/wiki/Down-the-Rabbit-Hole"
    accessed: "2026-04-13"
    description: "HikariCP internals: ConcurrentBag, fast path, bytecode-level optimization"
  - url: "https://www.postgresql.org/docs/current/runtime-config-connection.html"
    accessed: "2026-04-13"
    description: "PostgreSQL connection limits, timeout policies, tiered reservation"
  - url: "https://github.com/brettwooldridge/HikariCP"
    accessed: "2026-04-13"
    description: "HikariCP lifecycle: idle timeout, max lifetime, leak detection threshold"
---

# Database Handle Lifecycle and Resource Budgeting

## Handle Lifecycle State Machine

A handle (connection, session, table reference) follows a deterministic lifecycle:

```
OPEN --> ACTIVE --> IDLE --> EXPIRED --> CLOSED
  |        |         |                    ^
  |        |         +--- (TTL/evict) ----+
  |        +--- (close) -----------------+
  +--- (close) --------------------------+
```

- **OPEN**: Allocated and registered. Resources reserved but no query in flight.
- **ACTIVE**: Query or operation in progress. Must not be evicted or closed externally.
- **IDLE**: No operation in progress. Eligible for TTL expiry or eviction.
- **EXPIRED**: TTL fired or eviction policy triggered. Handle invalidated; next use throws.
- **CLOSED**: Terminal. Resources released. Idempotent re-close is safe.

Transition guards: ACTIVE->EXPIRED must be blocked (in-use handles are immune to
eviction). Only IDLE handles are candidates for TTL or eviction scans.

## TTL and Idle Timeout

Two independent clocks govern handle lifetime:

| Policy | Measures | Purpose |
|--------|----------|---------|
| **Idle timeout** | Time since last operation completed | Reclaim forgotten handles |
| **Max lifetime** | Time since handle opened | Bound total resource hold time |

HikariCP applies negative attenuation to max-lifetime (each handle gets a slightly
different expiry) to avoid mass expiry spikes. PostgreSQL uses `idle_session_timeout`
and `statement_timeout` as server-side enforcement.

```
// Pseudocode: idle scan (periodic timer)
for handle in registered_handles:
    if handle.state == IDLE and now - handle.lastActive > idleTimeout:
        handle.transition(EXPIRED, reason=IDLE_TIMEOUT)
        handle.close()
```

For max-lifetime, jitter prevents thundering herd:

```
// Pseudocode: max-lifetime with jitter
jitter = random(-maxLifetime * 0.025, 0)  // up to -2.5%
effectiveMaxLife = maxLifetime + jitter
if now - handle.createdAt > effectiveMaxLife:
    handle.transition(EXPIRED, reason=MAX_LIFETIME)
```

## Priority-Based Resource Dispatch

When handles are scarce, two models apply:

**Tiered reservation** (PostgreSQL model): partition budget into tiers with hard
guarantees. Each tier subtracts from `available` before checking. Simple, prevents
admin starvation, but reserved slots sit idle when their tier has no demand.

```
// Pseudocode: tiered acquire
acquire(priority):
    available = totalBudget - activeCount
    if priority == ADMIN:  return available > 0
    if priority == HIGH:   return available > reserved_admin
    if priority == NORMAL: return available > reserved_admin + reserved_high
```

**Weighted fair queue** (multi-tenant model): acquire requests enter a priority
queue; release notifies the highest-priority waiter. No wasted slots, but
low-priority requests can starve — requires aging (bump priority after N ms).

## Cross-Table Resource Budgeting

In a multi-table engine, a global handle budget prevents any single table from
monopolizing resources.

**Static partitioning**: each table gets a fixed slice (`globalMax / tableCount` or
configured per table). Simple but inflexible — hot tables throttled, cold tables waste.

**Dynamic rebalancing**: a coordinator shifts budget proportional to demand, with a
per-table floor so cold tables can still serve without waiting for rebalance.

```
// Pseudocode: two-level acquire (works with either partitioning model)
acquire(table, priority):
    if globalActiveCount >= globalMax:       return block_or_reject(priority)
    if table.activeCount >= table.budget:    return block_or_reject(priority)
    handle = table.allocate()
    globalActiveCount.increment()
    return handle
```

## Handle Leak Detection

Three strategies, ordered by overhead:

1. **Timeout warning** (low): log if ACTIVE longer than threshold. HikariCP's
   `leakDetectionThreshold` — alerts but does not close. Min practical: 2s.
2. **Phantom reference** (medium): register `PhantomReference` per handle; when
   GC'd without `close()`, the reference queue fires and logs the allocation site.
3. **Stack capture** (high, diagnostic only): capture `getStackTrace()` at creation.
   Include in leak warning. Enable only in debug/test builds.

jlsm's `AllocationTracking` enum (OFF / CALLER_TAG / FULL_STACK) already models
this three-tier approach in `HandleTracker`.

## How Systems Do It

| System | Global Limit | Idle Policy | TTL/Max Life | Priority | Leak Detection |
|--------|-------------|-------------|--------------|----------|----------------|
| **PostgreSQL** | `max_connections` (static) | `idle_session_timeout` | `statement_timeout` per session | Tiered reservation (superuser + reserved) | Server-side: `log_connections` |
| **MySQL** | `max_connections` (dynamic) | `wait_timeout` (8h default) | `max_connections_per_hour` per user | None built-in | `SHOW PROCESSLIST` |
| **HikariCP** | `maximumPoolSize` | `idleTimeout` (10m) | `maxLifetime` (30m) with jitter | None (FIFO queue) | `leakDetectionThreshold` |
| **MongoDB** | `maxPoolSize` per client | `maxIdleTimeMS` | `maxLifetimeMS` | Server selection (tag sets, read preference) | Driver-level monitoring |
| **RocksDB** | N/A (embedded, no handles) | N/A | N/A | Per-CF options (rate limiter, priority) | N/A |

RocksDB is notable: as an embedded engine, it has no handle/session concept. Resource
isolation uses column-family-level rate limiters and IO priority (`Env::Priority`).
This is closer to jlsm's model — resource control without connection pooling.

## Design Considerations for In-Process Engines

Unlike client-server databases, an in-process engine (like jlsm) has different
constraints:

1. **No network boundary** — handles are Java objects, not TCP connections. "Idle
   detection" means tracking method call timestamps, not socket keepalives.
2. **Shared heap** — handle overhead is small (no connection buffer), so the budget
   is about bounding concurrent operations and their associated memory, not TCP slots.
3. **Caller trust** — library callers are in the same JVM. Leak detection is
   diagnostic (help the developer), not defensive (protect the server).
4. **No authentication** — priority is set by the caller, not enforced by auth.
   Priority levels are advisory unless the engine validates caller identity.

## Cross-References

- jlsm engine constructs: [in-process-database-engine.md](in-process-database-engine.md)
- Hardcoded invalidation anti-pattern: [hardcoded-invalidation-reason.md](hardcoded-invalidation-reason.md)
- Resource close ordering: [../../patterns/resource-management/multi-resource-close-ordering.md](../../patterns/resource-management/multi-resource-close-ordering.md)
- Eviction scope mismatch: [../../patterns/resource-management/eviction-scope-mismatch.md](../../patterns/resource-management/eviction-scope-mismatch.md)

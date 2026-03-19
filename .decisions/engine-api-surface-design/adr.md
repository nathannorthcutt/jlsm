---
problem: "engine-api-surface-design"
date: "2026-03-19"
version: 1
status: "confirmed"
supersedes: null
files: []
---

# ADR — Engine API Surface Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (none) | No direct KB coverage — evaluated from general Java API design patterns | — |

---

## Files Constrained by This Decision
<!-- Key source files this decision affects. Populated as engine module is created. -->

## Problem
What should the jlsm-engine public API look like — engine lifecycle, table handle pattern, operation routing — to support concurrent callers in embedded mode now and remote client proxies over a future network layer, while protecting the engine from handle leaks by untrusted or careless clients?

## Constraints That Drove This Decision
- **Dual-mode API (embedded + remote proxy):** The same interface must work for direct in-process access and as a client proxy contract over the network
- **Handle lifecycle and leak protection:** Callers will not reliably close handles; the engine must track, limit, and evict handles to prevent resource exhaustion. Eviction must be diagnostic — clients see why and where the leak occurred.
- **Pass-through to existing fluent query API:** The table handle must expose the existing jlsm-table QueryBuilder directly without wrapping

## Decision
**Chosen approach: Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction**

The engine exposes two core interfaces: `Engine` (lifecycle + table management) and `Table` (data operations + query pass-through). `Table` extends `AutoCloseable`. Every `getTable()` call returns a tracked handle with source attribution. The engine enforces configurable handle limits and evicts the greediest source's handles first under pressure, providing full diagnostic information to evicted clients.

### Core API Shape

```java
interface Engine extends Closeable {
    Table createTable(String name, Schema schema);
    Table getTable(String name);
    void dropTable(String name);
    Collection<TableMetadata> listTables();
    TableMetadata tableMetadata(String name);
    EngineMetrics metrics();
}

interface Table extends AutoCloseable {
    void insert(JlsmDocument doc);
    QueryBuilder query();
    TableMetadata metadata();
    @Override void close();
}
```

### Dual-Mode Implementation

- **Embedded mode:** `Engine` impl is `LocalEngine`, `Table` impl is `LocalTable` wrapping jlsm-table directly. Source identity derived from calling thread.
- **Remote mode:** `Engine` impl is a client proxy (future), `Table` impl serializes operations over the network. Source identity is connection/client ID.
- Caller code is identical in both modes.

### Handle Lifecycle and Leak Protection

**Tracking:**
- Every `getTable()` records: source identity (thread/caller in embedded, connection/client in remote), allocation site (configurable: off, caller-tag-only, or full stack trace), and timestamp
- Engine maintains per-source handle counts: source → set of open handles
- Per-table and engine-wide handle registries

**Limits (all configurable):**
- Max handles per source per table
- Max total handles per table
- Engine-wide handle budget

**Eviction policy — punish the greedy:**
- When handle pressure exceeds a threshold, the engine identifies the source with the most open handles for the affected table
- That source's oldest handles are force-closed first, preserving handles for well-behaved clients
- Eviction is a last resort — well-behaved clients should never see it under normal load

**Client-visible eviction diagnostics:**
- Force-closed handles throw `HandleEvictedException` (or similar) on subsequent use, carrying:
  - Table name
  - Source identifier
  - Handle count at eviction time (this source vs others)
  - Original allocation site (stack trace or caller tag)
  - Reason: eviction / engine shutdown / table dropped
- This gives clients everything needed to diagnose and fix leaks

**Engine shutdown:**
- `engine.close()` force-invalidates all outstanding handles
- Each handle throws a descriptive exception on next use with reason "engine shutdown"

### Interception Points

Future rate limiting, metrics, throttling, backpressure, and cluster routing are added via the decorator pattern on either interface — no API changes needed:

```java
// Example: rate-limited table handle
class RateLimitedTable implements Table {
    private final Table delegate;
    private final RateLimiter limiter;
    // decorates insert(), query(), etc.
}
```

## Rationale

### Why Interface-Based Handle Pattern
- **Dual-mode:** Swap implementations for embedded vs remote. Same caller code.
- **Scale:** Handle is a direct reference — zero routing overhead per operation after initial lookup. No per-call object allocation.
- **Interception:** Decorator pattern gives unlimited interception without API changes.
- **Fluent API:** `Table.query()` returns the existing `QueryBuilder` directly.

### Why not Command/Request Pattern
- **Breaks fluent API:** Queries become data objects instead of builder chains — cannot pass through jlsm-table QueryBuilder.
- **Allocation overhead:** Command object per call at millions QPS.

### Why not Centralized Repository Pattern
- **Unbounded interface growth:** Engine interface grows with every new operation type (insert, update, delete, scan...).
- **Per-operation lookup:** Table name lookup on every call instead of cached handle.
- **Stringly-typed:** Table names as strings — typos are runtime errors.

## Implementation Guidance

**Engine builder:**
```java
Engine engine = LocalEngine.builder()
    .rootDirectory(path)
    .maxHandlesPerSourcePerTable(100)
    .maxHandlesPerTable(10_000)
    .maxTotalHandles(1_000_000)
    .allocationTracking(AllocationTracking.CALLER_TAG) // OFF, CALLER_TAG, FULL_STACK
    .build();
```

**Handle usage pattern:**
```java
try (Table users = engine.getTable("users")) {
    users.insert(doc);
    var results = users.query().where(...).execute();
}
// handle released, tracking decremented
```

**Stale handle behavior:** If a table is dropped while handles are held, those handles throw a descriptive exception on next use (similar to eviction but with reason "table dropped").

**JPMS exports:** The engine module exports the `Engine`, `Table`, `TableMetadata`, and `EngineMetrics` interfaces. Implementations are internal (not exported). This allows future modules (network, cluster) to depend on the interface package only.

**Clustering consideration:** The `createTable()` / `dropTable()` methods on the `Engine` interface are natural interception points for a future cluster layer to route through consensus before reaching the local catalog.

## What This Decision Does NOT Solve
- Handle priority levels (all sources are equal; greediest gets evicted)
- Cross-table handle budgets (per-table limits only for now)
- Automatic handle timeout/TTL (could be added later as a decorator)
- Transaction coordination across tables
- Network serialization protocol for remote mode

## Conditions for Revision
This ADR should be re-evaluated if:
- Handle priority levels become necessary (e.g., system operations vs user queries)
- A TTL-based eviction model proves more effective than count-based
- The query API changes in a way that breaks the pass-through pattern
- Review at 6-month mark regardless

---
*Confirmed by: user deliberation | Date: 2026-03-19*
*Full scoring: [evaluation.md](evaluation.md)*

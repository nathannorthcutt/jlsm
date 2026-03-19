---
problem: "engine-api-surface-design"
evaluated: "2026-03-19"
candidates:
  - path: "general-knowledge"
    name: "Interface-Based Handle Pattern"
  - path: "general-knowledge"
    name: "Command/Request Pattern"
  - path: "general-knowledge"
    name: "Centralized Repository Pattern"
constraint_weights:
  scale: 2
  resources: 2
  complexity: 1
  accuracy: 2
  operational: 3
  fit: 3
---

# Evaluation — engine-api-surface-design

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: No direct KB coverage. Evaluation based on general Java API
  design patterns and the project's existing API conventions (jlsm-table, jlsm-core).

## Constraint Summary
The API must serve two modes — embedded (direct in-process, low overhead) and
remote (client proxy over network) — using the same caller-facing interface.
Future interception points (rate limiting, metrics, cluster routing) must be
insertable without changing the API. The table handle must pass through to the
existing jlsm-table fluent query API. Java 25 sealed interfaces and records
are available.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | API overhead matters at millions QPS but all patterns can achieve low overhead |
| Resources | 2 | Handle memory matters but not the primary differentiator |
| Complexity | 1 | Expert team; interface hierarchies acceptable |
| Accuracy | 2 | Thread-safety and type-safety required but achievable in all patterns |
| Operational | 3 | Dual-mode + future interception is the hardest constraint to satisfy |
| Fit | 3 | Must compose with existing jlsm-table API and Java 25 features; JPMS exports matter |

---

## Candidate: Interface-Based Handle Pattern

Caller obtains an `Engine` interface, calls `engine.getTable(name)` which
returns a `Table` interface (the handle). Table operations happen directly on
the handle. Engine manages lifecycle, Table manages data operations.

```java
interface Engine extends Closeable {
    Table createTable(String name, Schema schema);
    Table getTable(String name);
    void dropTable(String name);
    Collection<TableMetadata> listTables();
}

interface Table {
    void insert(JlsmDocument doc);
    QueryBuilder query();  // pass-through to existing fluent API
    TableMetadata metadata();
}
```

Embedded: `Engine` impl is `LocalEngine`, `Table` impl is `LocalTable` wrapping
jlsm-table directly. Remote: `Engine` impl is `RemoteEngine` (client stub),
`Table` impl is `RemoteTable` (serializes operations over network).

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Handle is a direct reference — no routing overhead per operation. O(1) lookup to get handle, then zero-cost calls. |
| Resources | 2 | 5 | 10 | Handle is a thin wrapper; no per-call allocation. |
| Complexity | 1 | 5 | 5 | Simplest pattern — two interfaces, well-understood in Java ecosystem. |
| Accuracy | 2 | 5 | 10 | Clear ownership: Engine owns lifecycle, Table owns data ops. Type-safe at compile time. |
| Operational | 3 | 5 | 15 | Dual-mode natural: swap Engine/Table impls. Interception via decorator pattern on either interface. Rate limiting decorates Table, cluster routing decorates Engine. |
| Fit | 3 | 5 | 15 | Perfect pass-through: Table.query() returns existing QueryBuilder. Sealed interface optional for Engine (open for extension). Clean JPMS exports. |
| **Total** | | | **65** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Most natural for dual-mode: embedded impl vs remote proxy are just different implementations of the same interfaces
- Decorator pattern gives unlimited interception without API changes
- Pass-through to existing fluent API is trivial (Table.query() delegates to jlsm-table)
- Handles are cheap — one object per active table reference

**Key weaknesses:**
- Stale handle problem: if a table is dropped while a caller holds a Table reference, the handle becomes invalid (mitigation: handle checks liveness on each call)
- No built-in operation batching across tables (not required per brief)

---

## Candidate: Command/Request Pattern

All operations go through the engine as typed command objects. Engine dispatches
commands to the appropriate table. Natural for serialization/network.

```java
interface Engine extends Closeable {
    <R> R execute(Command<R> command);
    Collection<TableMetadata> listTables();
}

sealed interface Command<R> {
    record CreateTable(String name, Schema schema) implements Command<Table> {}
    record Insert(String table, JlsmDocument doc) implements Command<Void> {}
    record Query(String table, Predicate filter) implements Command<QueryResult> {}
    record DropTable(String name) implements Command<Void> {}
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 3 | 6 | Command object allocation per operation; dispatch routing adds overhead at millions QPS. |
| Resources | 2 | 3 | 6 | Command object per call — GC pressure at high throughput. |
| Complexity | 1 | 3 | 3 | Sealed command hierarchy is elegant but unfamiliar to many Java developers. |
| Accuracy | 2 | 5 | 10 | Type-safe via sealed interface + generics. Clear command semantics. |
| Operational | 3 | 5 | 15 | Excellent for interception: middleware/pipeline pattern wraps execute(). Serialization-friendly for remote mode. |
| Fit | 3 | 2 | 6 | Cannot pass through to existing fluent query API — queries must be expressed as Command objects, losing the QueryBuilder fluent chain. Breaks composability with jlsm-table. |
| **Total** | | | **46** | |

**Hard disqualifiers:** Cannot expose the existing jlsm-table fluent query API (QueryBuilder) through command objects without wrapping/duplicating it. Queries become data objects instead of builder chains.

**Key strengths:**
- Natural serialization boundary for network protocol
- Middleware/pipeline interception is built into the pattern
- Sealed interface enables exhaustive pattern matching

**Key weaknesses:**
- Breaks fluent query API pass-through (the brief's requirement)
- Command object allocation overhead at millions QPS
- Over-engineered for embedded mode where direct method calls suffice

---

## Candidate: Centralized Repository Pattern

All operations go through the engine with table name as a parameter. Engine is
always in the call path. No separate table handle.

```java
interface Engine extends Closeable {
    void createTable(String name, Schema schema);
    void dropTable(String name);
    Collection<TableMetadata> listTables();
    void insert(String tableName, JlsmDocument doc);
    QueryBuilder query(String tableName);
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 3 | 6 | Table name lookup on every operation; ConcurrentHashMap lookup is O(1) but adds overhead vs direct handle. |
| Resources | 2 | 4 | 8 | No handle objects; but table name string on every call. |
| Complexity | 1 | 5 | 5 | Simplest API surface — one interface. |
| Accuracy | 2 | 4 | 8 | Thread-safe if engine synchronizes internally. Table name is stringly-typed — typos are runtime errors. |
| Operational | 3 | 4 | 12 | Engine is always in path — natural interception point. But dual-mode requires engine to be an interface; remote proxy must implement all methods including data ops. |
| Fit | 3 | 3 | 9 | query(tableName) can return QueryBuilder for pass-through. But engine interface grows with every new operation type — violates interface segregation. |
| **Total** | | | **48** | |

**Hard disqualifiers:** None, but significant weaknesses.

**Key strengths:**
- Simplest surface — one interface to implement
- Engine is always the interception point (natural for rate limiting)
- No stale handle problem

**Key weaknesses:**
- Interface grows unboundedly as operations are added (insert, update, delete, query, scan, etc.)
- Stringly-typed table names — no compile-time safety for table references
- Remote proxy must implement the full growing interface
- Per-operation table lookup overhead vs cached handle

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Interface-Based Handle | 10 | 10 | 5 | 10 | 15 | 15 | **65** |
| Command/Request | 6 | 6 | 3 | 10 | 15 | 6 | **46** |
| Centralized Repository | 6 | 8 | 5 | 8 | 12 | 9 | **48** |

## Preliminary Recommendation
Interface-Based Handle Pattern wins decisively (65 vs 48 for runner-up). It is
the only pattern that scores 5 across all dimensions — natural dual-mode support,
zero-overhead handles, trivial pass-through to existing fluent API, and unlimited
interception via decorators.

## Risks and Open Questions
- **Stale handle on table drop:** Caller holds Table reference, table is dropped. Mitigation: handle checks liveness (throws descriptive exception). Document in API contract.
- **Handle invalidation on cluster rebalance:** Future concern — when a table migrates to another node, local handles must fail-over. The interface pattern supports this (proxy can redirect).
- **Query API evolution:** If the fluent query API changes, the Table interface pass-through adapts naturally (returns the current QueryBuilder).

---
title: "f04-obligation-resolution--wd-03"
type: feature-footprint
domains: ["engine", "cluster-transport", "partitioning"]
constructs:
  - "QueryRequestPayload"
  - "QueryRequestHandler"
  - "RemotePartitionClient"
  - "ClusteredTable"
  - "ClusteredEngine"
  - "PartitionClient"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/QueryRequestPayload.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/QueryRequestHandler.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RemotePartitionClient.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredTable.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/PartitionClient.java"
related:
  - "patterns/concurrency/unsafe-this-escape-via-listener-registration.md"
  - "patterns/concurrency/timeout-wrapper-does-not-cancel-source-future.md"
  - "patterns/distributed-systems/local-failure-masquerading-as-remote-outage.md"
  - "distributed-systems/cluster-membership"
decision_refs:
  - "transport-abstraction-design"
  - "scatter-gather-query-execution"
spec_refs: ["F04"]
research_status: stable
last_researched: "2026-04-20"
---

# f04-obligation-resolution--wd-03

## What it built

First end-to-end working implementation of cluster scatter-gather queries in the
jlsm engine. Added a shared `QueryRequestPayload` wire format that carries table
name and partition id, a server-side `QueryRequestHandler` that routes inbound
`QUERY_REQUEST` messages to local tables via `Engine.getTable(name)`, and
refactored `ClusteredTable.scan` from a sequential loop into a parallel fanout
via a virtual-thread scatter executor and `CompletableFuture.allOf`. Resolves
F04.R68 and F04.R77 — obligations carried since spec-verify because each needed
broader refactoring than an inline repair could handle.

## Key constructs

- `QueryRequestPayload` — utility class with static `encode*`/`decode*` for the
  `[tableNameLen:i32][tableName UTF-8][partitionId:i64][opcode:i8][body]` format
  across six opcodes (CREATE/GET/UPDATE/DELETE/RANGE/QUERY). Format is the
  contract between remote clients (encoder) and server-side dispatcher (decoder).
- `QueryRequestHandler` — stateless, thread-safe `MessageHandler` registered on
  `ClusterTransport` for `MessageType.QUERY_REQUEST`. Decodes payload header,
  resolves local table, dispatches on opcode, serializes `QUERY_RESPONSE`.
- `RemotePartitionClient` — extended with `String tableName` ctor parameter; all
  payload encoders now delegate to `QueryRequestPayload`. New `getRangeAsync`
  override uses `transport.request(...).orTimeout(...)` with explicit source-
  future cancellation.
- `ClusteredTable.scan` — parallel fanout via static virtual-thread
  `SCATTER_EXECUTOR` to work around InJvmTransport's synchronous delivery
  semantics. Preserves local short-circuit (R60), partial-result metadata (R64),
  ordered k-way merge (R67), per-request timeout (R70), client close on every
  path (R100).
- `ClusteredEngine` — registers handler in ctor after all final fields are
  assigned (unsafe-this-escape fix); symmetric deregister before transport.close
  on shutdown; ctor-failure rollback uses new `MembershipProtocol.removeListener`
  default SPI method.
- `PartitionClient` — new `getRangeAsync` default method added to the interface
  (wraps sync `getRange` in `completedFuture`); remote implementations override
  for true async.

## Adversarial findings

Three cross-domain adversarial patterns were graduated to the KB during the
audit pass on this feature:

- **unsafe-this-escape-via-listener-registration** — ctor registers a listener
  capturing `this` before final fields are assigned → concurrent dispatcher can
  observe `null` handler field through the listener's path. Unified 3 audit
  findings across concurrency + shared_state + resource_lifecycle. →
  [KB entry](../../patterns/concurrency/unsafe-this-escape-via-listener-registration.md)
- **local-failure-masquerading-as-remote-outage** — uniform catch on a
  remote-dispatch path collapses local-origin errors (encoding failures) into
  node-unavailability signals. Unified 3 findings. →
  [KB entry](../../patterns/distributed-systems/local-failure-masquerading-as-remote-outage.md)
- **timeout-wrapper-does-not-cancel-source-future** — `orTimeout` completes a
  wrapper future but leaves the source future uncancelled, leaking server-side
  state. Companion issue: `cancel(true)` on an already-completed future is a
  no-op; cancellation must also interrupt threads blocked inside synchronous
  `transport.request(...)` calls. →
  [KB entry](../../patterns/concurrency/timeout-wrapper-does-not-cancel-source-future.md)

Additional audit findings fixed inline (not graduated — handled by individual
spec requirements in F04 v5, R102–R114):

- Check-then-set race on `volatile boolean closed` (→ `AtomicBoolean.compareAndSet`)
- `whenComplete` close-failure silent under `-da` (→ `System.Logger` diagnostic)
- `mergeOrdered` NPE through heap comparator on null keys (→ explicit ISE)
- `decodeRangeResponsePayload` silently returns empty iterator when schema is
  null on a populated payload (→ `IOException` for malformed input)
- Response encoder unchecked int overflow on large cumulative size (→ `Math.addExact`)
- `join()` rollback swallowing original cause when `deregister` throws checked
  exception (→ catch `Exception`, use `addSuppressed`)

## Pipeline-efficiency observation

This feature ran the full pipeline — planning, test writing, hardening pass,
implementation, refactor, and adversarial audit. The hardening pass produced
24 adversarial tests at the contract-gap level (spec-unspecified behaviors),
while the audit pass found 18 distinct implementation bugs (none of which the
TDD + hardening suite exercised). The two passes are complementary, not
redundant — hardening closes contract gaps **before** implementation; audit
finds implementation bugs **after** it. Future features touching complex
concurrency + resource-lifecycle interactions should expect similar dual-pass
value.

## Cross-references

- **Specs:** [F04 — Engine Clustering](../../../.spec/domains/engine/F04-engine-clustering.md) (requirements R68, R77; v5 added R102–R114 from this feature's audit)
- **Decisions:** [transport-abstraction-design](../../../.decisions/transport-abstraction-design/adr.md), [scatter-gather-query-execution](../../../.decisions/scatter-gather-query-execution/adr.md)
- **Work group:** [`f04-obligation-resolution/WD-03`](../../../.work/f04-obligation-resolution/WD-03.md) (COMPLETE)
- **PR:** https://github.com/nathannorthcutt/jlsm/pull/34
- **Related features:** `engine-clustering` (core implementation this feature completes); WD-05 `fault-tolerance-and-smart-rebalancing` (remaining in group) will build on the now-working scatter-gather foundation

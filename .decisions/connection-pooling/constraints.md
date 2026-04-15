---
problem: "Connection management and pooling for NIO-based transport implementations"
slug: "connection-pooling"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — connection-pooling

## Problem Statement
How should the NIO implementation of `ClusterTransport` manage TCP connections to
peer nodes? The transport SPI (from `transport-abstraction-design`) defines `send()`
(fire-and-forget) and `request()` (request-response with CompletableFuture). The NIO
implementation must map these operations to persistent TCP connections with lifecycle
management, failure detection, and bounded resource usage.

## Constraints

### Scale
Cluster sizes up to 1000 nodes (F04 R35 design target). Each node maintains
connections to monitored peers for membership protocol traffic (K=3-5 via
expander graph, not all-to-all) plus targeted connections for scatter-gather
queries. At 1000 nodes with K=5 monitors, each node maintains 5 persistent
monitoring connections plus on-demand connections to query targets. Scatter-gather
may fan out to all partitions (up to hundreds of peers simultaneously). Peak
concurrent connections per node: up to ~999 during full-cluster queries.
Message rates: membership pings at protocol period frequency (~1-10/s per
monitored peer), scatter-gather bursts during queries.

### Resources
Pure Java library — no Netty, gRPC, or external networking frameworks. Must run in
containers with bounded file descriptors. Memory budget for connection state must be
bounded and configurable. Java 25 with NIO (`java.nio.channels.SocketChannel`,
`Selector`). Virtual threads available for I/O concurrency.

### Complexity Budget
Expert team available across all domains, but the implementation must still be easy
to reason about and debug. Prefer designs that are locally understandable — a
developer reading the connection pool code should grasp the lifecycle without tracing
through multiple abstraction layers. Debuggability over cleverness.

### Accuracy / Correctness
- `send()` is fire-and-forget: connection failure means the message is silently dropped
  (membership protocol handles detection via its own mechanisms)
- `request()` must complete or timeout: connection failure triggers
  CompletableFuture completion with an exception
- Connection state must be consistent: no use-after-close, no double-close, no
  stale connections appearing healthy
- Reconnection must be idempotent: concurrent reconnect attempts to the same peer
  must converge to a single connection
- Lifecycle state transitions (open/closing/closed) must be atomic — concurrent
  close() calls must not result in duplicate cleanup (F04 R87 pattern)
- `send()` failures must be silently absorbed — connection pool must handle broken
  connections during fire-and-forget sends without surfacing errors (F04 R28)

### Operational Requirements
- Configurable timeout on `request()` (from transport ADR)
- Health checking: detect dead connections before they're used (avoid first-failure-on-use)
- Graceful shutdown: drain in-flight requests before closing connections
- Observability: connection state must be inspectable (how many connections, to which
  peers, health status) for debugging

### Fit
- Java 25 NIO (`SocketChannel`, `Selector`, `AsynchronousSocketChannel`)
- Virtual threads for I/O-bound work (per transport ADR threading model)
- Platform threads for encryption/ThreadLocal-dependent work (per transport ADR)
- Must implement `ClusterTransport` interface (send + request + registerHandler)
- Must be `AutoCloseable` with deterministic cleanup

## Key Constraints (most narrowing)
1. **No external dependencies** — eliminates Netty, gRPC, and all existing Java
   connection pool libraries. Must be built from NIO primitives.
2. **Virtual thread compatibility** — the design must work correctly with virtual
   threads. This rules out approaches that depend on thread-per-connection with
   platform threads, and favors approaches where blocking NIO operations are
   mounted on virtual threads.
3. **Debuggability** — despite expert availability, the pool must be locally
   understandable. This favors simpler lifecycle models over maximally efficient
   but opaque designs.

## Unknown / Not Specified
- Exact file descriptor budget (varies by deployment). Assume conservative: pool
  must work within 1024 total FDs for the process.
- Whether connections should support TLS (authenticated-discovery is deferred;
  assume plain TCP initially with TLS as a future layer).
- Maximum message size on a connection (depends on message-serialization-format,
  which is also deferred).

## Constraint Falsification — 2026-04-13

Checked: F04 engine-clustering spec (R27-R32, R35, R86-R87), cluster-membership-protocol
ADR, transport-abstraction-design ADR.

Added:
- Scale upgraded from "tens-to-hundreds" to "up to 1000 nodes" per F04 R35
- Correctness: atomic lifecycle transitions per F04 R87 pattern
- Correctness: silent send() failure absorption per F04 R28

No additional implied constraints found for: Resources, Complexity, Operational, Fit.

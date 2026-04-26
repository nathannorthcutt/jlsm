---
problem: "connection-pooling"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/MultiplexedTransport.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/PeerConnection.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/internal/PendingMap.java"
---

# ADR — Connection Management and Pooling

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Multiplexed Transport Framing | Chosen approach — framing format, correlation dispatch, write serialization, connection lifecycle | [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [transport-abstraction-design](../transport-abstraction-design/adr.md) | Parent — defines `ClusterTransport` SPI that this decision implements |
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Consumer — Rapid uses `send()` for pings, `request()` for view changes |
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Consumer — proxy table uses `request()` for sub-queries |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — NIO transport implementation

## Problem
How should the NIO implementation of `ClusterTransport` manage TCP connections to
peer nodes? The transport SPI defines `send()` (fire-and-forget) and `request()`
(request-response with `CompletableFuture`). The NIO implementation must map these
operations to persistent TCP connections with lifecycle management, failure detection,
message framing, and bounded resource usage — supporting clusters of up to 1000 nodes.

## Constraints That Drove This Decision
- **No external dependencies** — eliminates Netty, gRPC, and all existing Java connection
  pool libraries. Must be built from Java NIO primitives.
- **1000-node scale (F04 R35)** — up to 999 peer connections per node. FD count must
  stay within container limits (~4096 default).
- **Framing is baseline** — any approach needs length-prefixed framing, type tags, and
  correlation IDs to implement the `ClusterTransport` SPI (`request()` needs
  correlation matching, `registerHandler()` needs type dispatch, TCP needs message
  delimitation). This is not optional complexity — it is required infrastructure.

## Decision
**Chosen approach: [Single-Connection Multiplexing](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)**

One TCP connection per peer, all messages multiplexed via a Kafka-style framing protocol.
Each frame carries a 4-byte big-endian length prefix, 1-byte type tag, 4-byte int32 stream
ID, 1-byte flags, then body. A dedicated reader virtual thread per peer blocks on
`SocketChannel.read()`, parses frames, and dispatches — completing `CompletableFuture`s by
stream ID for request-response, or routing to type-registered handlers for fire-and-forget.
Write serialization via `ReentrantLock` per connection. Connections established lazily,
health-checked passively via membership heartbeats, reconnected with exponential backoff.

This design is proven at scale: Kafka uses the same pattern (4-byte length prefix + int32
correlation ID + pipelining). Cassandra CQL v5 uses a similar approach with int16 stream
IDs supporting 32K concurrent streams per connection.

**This decision subsumes the deferred `message-serialization-format` decision** — the
framing protocol IS the message serialization format for the transport layer.

## Rationale

### Why [Single-Connection Multiplexing](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)
- **Scale**: one FD per peer — optimal. At 1000 nodes: 999 FDs. No FD multiplication.
  ([KB: `#tradeoffs > strengths`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md))
- **Concurrent streams**: membership pings and query requests coexist on the same connection
  without blocking each other. No channel exclusivity problem.
  ([KB: `#how-it-works`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md))
- **Foundation for future work**: the framing protocol is the base layer for `scatter-backpressure`
  (per-stream flow control via flags), `transport-traffic-priority` (message type tags already in
  frames), and compression (frame-level, like CQL v5).
  ([KB: `#tradeoffs > strengths`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md))
- **Fit**: maps directly to JDK standard components — `SocketChannel`, `ReentrantLock`,
  `ConcurrentHashMap`, `AtomicInteger`, `CompletableFuture`. Reader virtual thread blocks on
  I/O naturally.
  ([KB: `#code-skeleton`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md))

### Why not Connection-per-Peer (Lazy)
- **Channel exclusivity**: `request()` blocks the channel for full RTT, preventing concurrent
  `send()` (membership pings) to the same peer. Phi accrual depends on timely heartbeats —
  queuing pings behind query responses is a correctness issue. Any fix (second channel, send
  queue) converges toward multiplexing.
- **Same framing work**: still needs length-prefix + type tag + correlation ID for `request()`
  and `registerHandler()`. The "simplicity advantage" is illusory — the complexity delta to
  full multiplexing is one `ConcurrentHashMap` for correlation dispatch.

### Why not VThread-per-Message with Shared Pool
- **Over-engineered**: pool lifecycle (acquire/release, health-check-on-borrow, idle eviction,
  drain-on-departure) for a burst-to-same-peer scenario that rarely occurs in an LSM-tree
  transport. Scatter-gather fans out to different peers, not concurrent requests to the same peer.
- **FD multiplication**: at pool_size=2 with 1000 peers, 1998 FDs — twice the multiplexing approach.

### Why not Elastic Pool per Peer
- **FD multiplication**: at 1000 nodes with max=4, up to 4000 FDs. Near-disqualifying at scale.
- **Wrong abstraction**: borrow/return lifecycle doesn't map naturally to message transport where
  sends are brief. HikariCP-level complexity for zero benefit over multiplexing.

## Implementation Guidance
Key parameters from [`multiplexed-transport-framing.md#key-parameters`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md#key-parameters):
- Length prefix: 4-byte big-endian int32 (max frame ~2 GiB)
- Stream ID: 4-byte int32 via `AtomicInteger` counter (Kafka-style; CQL's int16 is too small for full-cluster scatter-gather)
- Header: 10 bytes total (4 length + 1 type + 4 streamId + 1 flags)
- Byte order: big-endian throughout (network order)
- Write lock: `ReentrantLock` per connection (virtual-thread-safe since JDK 21)
- Fire-and-forget: stream ID 0 signals "no response expected"

Known edge cases from [`multiplexed-transport-framing.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md#edge-cases-and-gotchas):
- Partial reads/writes: loop until buffer complete, hold write lock for entire frame
- Orphaned futures: enforce timeout via `CompletableFuture.orTimeout()`, clean up pending map entry
- Stale pending futures on reconnect: drain and fail all pending before creating new connection
- Head-of-line blocking: bounded for small cluster messages; SSTable bulk transfer needs separate mechanism

Connection lifecycle from [`multiplexed-transport-framing.md#connection-lifecycle`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md#connection-lifecycle):
- Establishment: lazy on first send/request, blocking connect on virtual thread
- Health: passive — membership heartbeats serve as implicit health checks
- Reconnection: exponential backoff (100ms→30s), CAS-based to prevent races
- Shutdown: set closed flag → fail all pending futures → close channels → interrupt readers

Full implementation detail: [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)
Code scaffold: [`multiplexed-transport-framing.md#code-skeleton`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md#code-skeleton)

## What This Decision Does NOT Solve
- Connection encryption/TLS (authenticated-discovery is deferred; assume plain TCP initially)
- Backpressure when a peer connection is saturated (scatter-backpressure — the framing layer enables it via flags)
- Traffic priority between membership and query messages (transport-traffic-priority — type tags in frames enable it)
- Large payload transfer like SSTable replication (needs dedicated channels or chunked framing)

## Conditions for Revision
This ADR should be re-evaluated if:
- TCP head-of-line blocking causes measurable latency impact on membership pings during heavy query load
- Write lock contention under scatter-gather burst load becomes a bottleneck (upgrade path: write queue with dedicated writer virtual thread)
- SSTable bulk transfer needs to share the cluster transport (may require streaming/chunked framing extension)
- Virtual thread pinning on SocketChannel I/O is observed on the target JDK/platform (test under sustained load)
- QUIC or another multiplexed transport becomes practical for Java without external dependencies

---
*Confirmed by: user deliberation (3 rounds) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*

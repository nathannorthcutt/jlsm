---
problem: "connection-pooling"
evaluated: "2026-04-13"
candidates:
  - name: "Connection-per-Peer (Lazy)"
  - name: "Elastic Pool per Peer"
  - name: "Single-Connection Multiplexing"
  - name: "Virtual-Thread-per-Message with Shared Pool"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 2
  accuracy: 2
  operational: 2
  fit: 3
---

# Evaluation — connection-pooling

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: none (general knowledge — no KB entry covers connection pooling)

## Constraint Summary

The binding constraints demand a connection management strategy built entirely from
Java NIO primitives (no Netty/gRPC) that scales to 1000 nodes (~999 potential peer
connections), works correctly with virtual threads, and is simple enough to debug
locally. The transport ADR's `send()` (fire-and-forget) and `request()`
(CompletableFuture) patterns require both async and blocking I/O paths. Silent failure
absorption on `send()` and atomic lifecycle transitions are correctness requirements.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | 1000-node design target per F04 R35 — primary differentiator between approaches |
| Resources | 2 | Pure library, bounded FDs, but exact budget is unknown |
| Complexity | 1 | Expert team available; debuggability valued but not a constraint that narrows the space (REVISED from 2 per user input) |
| Accuracy | 2 | Correctness matters (no stale connections, atomic lifecycle) but failure detection is in the membership layer above |
| Operational | 2 | Timeouts and health checks needed, but observability is a quality concern not a disqualifier |
| Fit | 3 | Java 25 NIO + virtual threads is non-negotiable — approaches that fight virtual threads lose hard |

---

## Candidate: Connection-per-Peer (Lazy)

**Source:** General knowledge — standard pattern in distributed systems (Cassandra, Akka Cluster)

One `SocketChannel` per known peer node, stored in a `ConcurrentHashMap<NodeAddress, PeerConnection>`.
Connections created lazily on first `send()` or `request()` to that peer. Broken connections
detected on I/O failure and reconnected with exponential backoff. The `PeerConnection` wrapper
manages channel lifecycle, write serialization (one writer at a time per channel), and
pending-request tracking (correlation ID → CompletableFuture map).

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 3 | 9 | At 1000 nodes: 999 channels. FD count = peer count. No way to bound below peer count. |
|        |   |   |   | **Would be a 2 if:** FD budget is under 1024 and cluster exceeds ~900 nodes |
| Resources | 2 | 3 | 6 | One channel + one read buffer per peer. At 1000 peers with 4KB read buffer: ~4MB. Acceptable but not minimal. |
|           |   |   |   | **Would be a 2 if:** per-connection memory exceeds 64KB (TLS buffers, large write queues) |
| Complexity | 2 | 5 | 10 | Simplest possible model. Map lookup → channel. One connection per peer, no pool sizing decisions. |
|            |   |   |   | **Would be a 2 if:** reconnection logic requires distributed coordination (it doesn't — each side reconnects independently) |
| Accuracy | 2 | 4 | 8 | Simple lifecycle: connected/disconnected/reconnecting. Atomic transitions via CAS on state enum. Silent send failure via try/catch in send path. |
|          |   |   |   | **Would be a 2 if:** write serialization per channel races under concurrent virtual threads without synchronization |
| Operational | 2 | 4 | 8 | Health checking: periodic heartbeat or TCP keepalive. Connection state directly inspectable per peer. |
|             |   |   |   | **Would be a 2 if:** reconnection storms under network partition overwhelm the node |
| Fit | 3 | 5 | 15 | NIO SocketChannel, virtual threads block on read/write naturally. No selector needed for blocking mode with virtual threads. |
|     |   |   |   | **Would be a 2 if:** virtual threads can't pin to SocketChannel blocking ops (they can in Java 21+) |
| **Total** | | | **56** | |

**Hard disqualifiers:** None.

**Key strengths:** Maximum simplicity. One connection = one peer = one map entry. Virtual threads
block on SocketChannel I/O naturally (no Selector complexity). Debuggability is excellent —
connection state is a simple per-peer map.

**Key weaknesses:** FD count equals peer count with no way to bound below it. At 1000 nodes this
uses 999 FDs. Single connection per peer means a slow write blocks all messages to that peer.
No burst capacity — one channel handles both membership pings and scatter-gather traffic.

---

## Candidate: Elastic Pool per Peer

**Source:** General knowledge — HikariCP model adapted to NIO channels

Min/max connections per peer. Pool creates connections on demand up to max, evicts idle
connections down to min. Connections borrowed and returned. Each connection independently
handles one message at a time.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 2 | 6 | At 1000 nodes with min=1 max=4: 999-3996 FDs. Dramatically worse than 1-per-peer. |
|        |   |   |   |  |
| Resources | 2 | 2 | 4 | Memory proportional to max×peers. At 1000 peers, max=4, 4KB buf: 16MB just for read buffers. |
|           |   |   |   |  |
| Complexity | 2 | 2 | 4 | Pool lifecycle is inherently complex: borrow/return, idle eviction timers, max-waiters, poisoned-connection detection. This is what HikariCP takes 10K lines to do correctly. |
|            |   |   |   |  |
| Accuracy | 2 | 4 | 8 | Well-understood correctness model. But more states = more edge cases (borrowed, idle, evicting, poisoned). |
|          |   |   |   | **Would be a 2 if:** borrow timeout interacts badly with virtual thread scheduling |
| Operational | 2 | 3 | 6 | More metrics (pool utilization, wait time, eviction rate) but also more to monitor. |
|             |   |   |   |  |
| Fit | 3 | 3 | 9 | Works with NIO, but the borrow/return model is a database connection pool pattern — awkward fit for message-oriented transport where sends are brief. |
|     |   |   |   |  |
| **Total** | | | **37** | |

**Hard disqualifiers:** None, but scale score is near-disqualifying at 1000 nodes.

**Key strengths:** Burst capacity — multiple connections per peer handle concurrent scatter-gather.
Well-understood pattern from JDBC world.

**Key weaknesses:** Massive FD multiplication at 1000 nodes. Complexity is high for the marginal
benefit. The borrow/return lifecycle doesn't map naturally to a message transport — messages
are typically small and fast, not long-lived borrows.

---

## Candidate: Single-Connection Multiplexing

**Source:** General knowledge — HTTP/2 model, gRPC channel architecture

One TCP connection per peer. All messages multiplexed over it via correlation IDs. Framing
protocol on the wire (length-prefix + type tag + correlation ID) separates concurrent messages.
A dedicated reader virtual thread per peer demultiplexes incoming frames to waiting
CompletableFutures by correlation ID.

**REVISED NOTE (deliberation round 2):** Framing (length-prefix + type tag + correlation ID)
is baseline work required by ALL approaches — Connection-per-Peer needs it too to implement
`request()` with CompletableFuture matching and `registerHandler()` with type dispatch. The
incremental complexity of multiplexing over that baseline is a ConcurrentHashMap for correlation
dispatch. The original Complexity score of 2 was based on the false premise that framing was
unique to this approach. Additionally, the deferred decisions (message-serialization-format,
scatter-backpressure, transport-traffic-priority) all build on the framing layer — this approach
solves them structurally rather than deferring rework. Virtual threads are used for the per-peer
reader loop, which blocks on channel.read() — they are useful here, not "less useful."

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 5 | 15 | Minimal FDs: exactly one per peer. At 1000 nodes: 999 FDs. Optimal. |
|        |   |   |   | **Would be a 2 if:** single connection becomes a throughput bottleneck (TCP window saturated) |
| Resources | 2 | 5 | 10 | One channel, one read buffer per peer. Minimal memory. Correlation map is small (bounded by in-flight requests). |
|           |   |   |   | **Would be a 2 if:** in-flight request count per peer exceeds thousands (unlikely for LSM-tree transport) |
| Complexity | 1 | 3 | 3 | Framing is baseline (all approaches need it). Incremental complexity: correlation ID dispatch (ConcurrentHashMap), write serialization (one writer at a time via lock or queue). Not trivial but well-understood. (REVISED from 2) |
|            |   |   |   | **Would be a 2 if:** framing protocol needs stream-level flow control (HTTP/2 complexity) — it doesn't for this use case |
| Accuracy | 2 | 4 | 8 | Head-of-line blocking at TCP level is real but bounded: messages are small (membership pings, query requests/responses). Correlation ID dispatch is simple with atomic counter. No orphaned futures with timeout enforcement. |
|          |   |   |   | **Would be a 2 if:** messages are large enough to saturate TCP window and block other streams (unlikely — SSTable transfer is a separate concern) |
| Operational | 2 | 4 | 8 | Per-peer metrics: in-flight count, latency histogram per message type. Wire captures show framed messages (length-prefixed), not raw byte soup. |
|             |   |   |   | **Would be a 2 if:** interleaved partial frames make wire debugging hard (mitigated by length-prefixed framing — each frame is self-contained) |
| Fit | 3 | 4 | 12 | NIO SocketChannel in blocking mode. Reader virtual thread per peer blocks on read(). Writer serialization via ReentrantLock or write queue. Framing IS message-serialization-format — this decision resolves that dependency structurally. |
|     |   |   |   | **Would be a 2 if:** the framing protocol conflicts with a future message-serialization-format decision (it won't — this IS that decision) |
| **Total** | | | **56** | |

**Hard disqualifiers:** None.

**Key strengths:** Optimal FDs. One connection per peer handles all traffic (membership + queries)
concurrently. No channel exclusivity problem — multiple virtual threads can have in-flight
requests on the same peer simultaneously. Framing layer is foundation for deferred decisions
(backpressure, priority, serialization). Reader virtual thread per peer is natural for virtual
threads.

**Key weaknesses:** Head-of-line blocking at TCP level (one slow frame delays others on same
connection). Write serialization needed (only one thread writes to a SocketChannel at a time).
More moving parts than Connection-per-Peer — but the delta is smaller than originally assessed
because framing is baseline.

---

## Candidate: Virtual-Thread-per-Message with Shared Pool

**Source:** General knowledge — Loom-native pattern (Project Loom blog posts, JEP 444 guidance)

A bounded pool of `SocketChannel`s per peer (e.g., 1-2 channels per peer). Each message
`send()` or `request()` spawns a virtual thread that acquires a channel from the pool, performs
the blocking I/O, and returns the channel. The pool is a simple bounded `LinkedBlockingQueue`
per peer. Virtual threads park cheaply while waiting for a channel.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 4 | 12 | At 1000 nodes with pool size 2: 1998 FDs. Higher than 1-per-peer but bounded and configurable. Pool size 1 degrades to connection-per-peer. |
|        |   |   |   | **Would be a 2 if:** pool size must exceed 2 per peer for acceptable throughput, pushing FDs past 3000 |
| Resources | 2 | 4 | 8 | Bounded per peer. Virtual threads are cheap (~1KB stack). Channel count = pool_size × peer_count. |
|           |   |   |   | **Would be a 2 if:** virtual thread count under scatter-gather burst reaches millions (it won't — bounded by message count) |
| Complexity | 2 | 4 | 8 | Simpler than elastic pool — no idle eviction, no borrow timeouts. Queue is the pool. Virtual threads handle concurrency naturally. |
|            |   |   |   | **Would be a 2 if:** channel return-to-pool semantics interact badly with error handling (partial writes, channel poisoning) |
| Accuracy | 2 | 4 | 8 | Each virtual thread owns its channel for the duration of the I/O. No sharing within a single operation. Channel health checked on acquire. |
|          |   |   |   | **Would be a 2 if:** a virtual thread holding a channel gets preempted for long enough that the channel times out server-side |
| Operational | 2 | 4 | 8 | Pool metrics are simple (available/in-use per peer). Virtual thread dumps show which messages are in flight. |
|             |   |   |   | **Would be a 2 if:** virtual thread dumps become unreadable at high concurrency (JDK tooling handles this) |
| Fit | 3 | 5 | 15 | Purpose-built for Java 21+ virtual threads. Blocking NIO is the natural I/O model. No Selector needed. LinkedBlockingQueue is JDK standard. |
|     |   |   |   | **Would be a 2 if:** SocketChannel blocking ops pin carrier threads (they don't since Java 21 — JEP 444) |
| **Total** | | | **59** | |

**Hard disqualifiers:** None.

**Key strengths:** Natural fit for Java 25 virtual threads. Simple pool model (queue of channels).
Configurable pool size per peer allows trading FDs for throughput. With pool_size=1 it degrades
gracefully to connection-per-peer. Each virtual thread owns its channel — no concurrent writes
on the same channel.

**Key weaknesses:** More FDs than multiplexing (but bounded). Pool sizing is a tunable that must
be chosen. Slightly more complex than bare connection-per-peer.

---

## Comparison Matrix (pre-falsification)

| Candidate | Scale (×3) | Resources (×2) | Complexity (×2) | Accuracy (×2) | Operational (×2) | Fit (×3) | Weighted Total |
|-----------|-----------|----------------|-----------------|---------------|-----------------|---------|----------------|
| Connection-per-Peer (Lazy) | 9 | 6 | 10 | 8 | 8 | 15 | **56** |
| Elastic Pool per Peer | 6 | 4 | 4 | 8 | 6 | 9 | **37** |
| Single-Connection Multiplexing | 15 | 10 | 4 | 6 | 6 | 9 | **50** |
| VThread-per-Message + Shared Pool | 12 | 8 | 8 | 8 | 8 | 15 | **59** |

## Falsification Results

Falsification weakened 5 of 6 scores on VThread+Pool:
- Scale 4→3: 2× FDs without idle eviction; "bounded by config" ≠ "appropriate"
- Resources 4→3: kernel TCP buffers (~256KB/connection) not accounted for
- Complexity 4→3: production-correct impl needs health checks, idle eviction,
  peer departure draining — "queue is the pool" understates real work
- Operational 4→3: requires explicit instrumentation; not free out of box
- Fit 5→4: SocketChannel pinning behavior must be verified for JDK 25, not assumed

Strongest counter-argument: for LSM-tree cluster transport, concurrent requests
to the SAME peer are rare. Scatter-gather fans out to DIFFERENT peers. Pool's
burst-to-same-peer benefit is marginal. Connection-per-Peer has fewer bug surfaces.

Missing candidate identified: Reactor pattern (NIO Selector + non-blocking channels).
Not evaluated because: same FD count as connection-per-peer, requires framing protocol
(same complexity as multiplexing), doesn't leverage virtual threads. Would not change
revised ranking.

## Deliberation Round 2 — Framing is Baseline

User challenge: framing (length-prefix + type tag + correlation ID) is required by ALL
approaches to implement the ClusterTransport SPI — request() needs correlation ID matching,
registerHandler() needs type dispatch, TCP needs message delimitation. The original evaluation
treated framing as extra complexity unique to multiplexing, which was incorrect.

This changes:
- Connection-per-Peer Complexity: 5→4 (still needs framing; still simpler than multiplexing
  but the delta is one correlation dispatch map, not "building a framing protocol from scratch")
- Multiplexing Complexity: 2→3 (framing is baseline, incremental work is correlation dispatch)
- Multiplexing Accuracy: 3→4 (head-of-line blocking bounded by small message sizes)
- Multiplexing Operational: 3→4 (framed wire captures are readable; per-type metrics)
- Multiplexing Fit: 3→4 (reader virtual thread per peer is idiomatic; framing IS
  message-serialization-format, resolving a dependency structurally)
- Complexity weight: 2→1 (expert team, user confirmed complexity not a narrowing constraint)

Additionally: Connection-per-Peer has a channel exclusivity problem — request() blocks the
channel for full RTT, preventing concurrent send() (membership pings) to the same peer.
This is a correctness issue for phi accrual timing. Adjustments:
- Connection-per-Peer Accuracy: 4→3 (channel exclusivity blocks membership pings during queries)
- Connection-per-Peer Fit: 5→4 (the exclusive-channel model requires either a second channel
  or queuing sends behind requests — either way, it grows toward multiplexing)

## Comparison Matrix [REVISED — deliberation round 2]

| Candidate | Scale (×3) | Resources (×2) | Complexity (×1) | Accuracy (×2) | Operational (×2) | Fit (×3) | Weighted Total |
|-----------|-----------|----------------|-----------------|---------------|-----------------|---------|----------------|
| Connection-per-Peer (Lazy) | 9 | 6 | 4 | 6 | 8 | 12 | **45** |
| Elastic Pool per Peer | 6 | 4 | 2 | 8 | 6 | 9 | **35** |
| **Single-Connection Multiplexing** | 15 | 10 | 3 | 8 | 8 | 12 | **56** |
| VThread-per-Message + Shared Pool | 9 | 6 | 3 | 8 | 6 | 12 | **44** |

## Recommendation [REVISED — deliberation round 2]
Single-Connection Multiplexing (56) wins after correcting the framing-is-baseline error
and reweighting Complexity to 1. The key insights from deliberation:

1. Framing is baseline for ALL approaches — the complexity delta to multiplexing is one
   correlation dispatch map, not "half of HTTP/2"
2. Connection-per-Peer has a channel exclusivity problem that blocks membership pings
   during query RTT — a correctness issue for phi accrual
3. The deferred decisions (message-serialization-format, scatter-backpressure,
   transport-traffic-priority) all build on the framing layer — multiplexing resolves
   them structurally rather than deferring rework
4. Expert team makes the remaining complexity delta (correlation dispatch) non-constraining

## Research — KB Evidence (2026-04-13)

Research completed. KB entry: [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)

Sources: Cassandra CQL v5 specification, Kafka binary protocol guide, muxable Java NIO library.

KB-backed findings that strengthen the Multiplexing recommendation:

1. **Framing format**: Kafka-style 4-byte big-endian length prefix is the proven pattern.
   Simple, minimal overhead (10-byte header total), battle-tested at massive scale.
   (KB: `#framing-formats`)
2. **Correlation dispatch**: int32 stream IDs with `AtomicInteger` counter. Kafka uses int32
   correlation IDs with pipelining support. CQL uses int16 (32K streams) — int32 preferred
   for jlsm to avoid stream ID exhaustion during full-cluster scatter-gather.
   (KB: `#correlation-dispatch`)
3. **Write serialization**: `ReentrantLock` per connection — virtual-thread-safe since JDK 21.
   Alternative: write queue with dedicated writer virtual thread for high-throughput paths.
   (KB: `#write-serialization`)
4. **Connection lifecycle**: lazy establishment, passive health checking via membership protocol
   heartbeats (no extra traffic), CAS-based reconnection to prevent races.
   (KB: `#connection-lifecycle`)
5. **Head-of-line blocking**: bounded for small cluster messages. Cassandra mitigates by capping
   frame size to 128 KiB. For jlsm: cluster messages are small; SSTable bulk transfer needs a
   separate mechanism. (KB: `#edge-cases-and-gotchas`)

Score adjustments after research (multiplexing candidate):
- Accuracy: 4 confirmed — correlation dispatch is simple, orphaned futures prevented by
  `orTimeout()` + cleanup. Backed by Kafka and CQL production use.
- Fit: 4→5 — the KB code skeleton shows the design maps directly to Java 25 NIO +
  virtual threads (blocking reader loop, ReentrantLock writer, ConcurrentHashMap pending map).
  All JDK standard library components.

## Comparison Matrix [FINAL — after KB research]

| Candidate | Scale (×3) | Resources (×2) | Complexity (×1) | Accuracy (×2) | Operational (×2) | Fit (×3) | Weighted Total |
|-----------|-----------|----------------|-----------------|---------------|-----------------|---------|----------------|
| Connection-per-Peer (Lazy) | 9 | 6 | 4 | 6 | 8 | 12 | **45** |
| Elastic Pool per Peer | 6 | 4 | 2 | 8 | 6 | 9 | **35** |
| **Single-Connection Multiplexing** | 15 | 10 | 3 | 8 | 8 | 15 | **59** |
| VThread-per-Message + Shared Pool | 9 | 6 | 3 | 8 | 6 | 12 | **44** |

## Final Recommendation
Single-Connection Multiplexing (59) wins decisively after KB research confirmed the design
with production evidence from Cassandra CQL v5 and Kafka. Fit score upgraded to 5 — the
design maps directly to JDK standard components (SocketChannel, ReentrantLock, ConcurrentHashMap,
AtomicInteger, CompletableFuture). Confidence upgraded from Medium to High.

## Risks and Open Questions
- Head-of-line blocking at TCP level: bounded for small cluster messages. SSTable
  bulk transfer should use a separate mechanism (dedicated channel or chunked framing).
- Write lock contention: under extreme scatter-gather burst, the per-connection
  ReentrantLock may become a bottleneck. Upgrade path: write queue with dedicated
  writer virtual thread. Profile before optimizing.
- This decision subsumes `message-serialization-format` — the framing protocol IS
  the message serialization format for the transport layer.

---
{
  "id": "transport.multiplexed-framing",
  "version": 3,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "transport"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [
    "connection-pooling",
    "transport-abstraction-design"
  ],
  "kb_refs": [
    "distributed-systems/networking/multiplexed-transport-framing"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F19"
  ]
}
---
# transport.multiplexed-framing — Multiplexed Transport Framing

## Requirements

### Framing Protocol

R1. Each frame on the wire must consist of a 4-byte big-endian signed int32
length prefix followed by a 14-byte header and a variable-length body. The
length prefix must encode the combined size of header + body (not including the
length prefix itself).

R2. The 14-byte frame header must contain, in order: 1-byte type tag, 4-byte
big-endian int32 stream ID, 1-byte flags field, 8-byte big-endian int64
sequence number.

R2a. The type tag byte value must map to `MessageType` enum constants as
follows: PING=0x00, ACK=0x01, VIEW_CHANGE=0x02, QUERY_REQUEST=0x03,
QUERY_RESPONSE=0x04, STATE_DIGEST=0x05, STATE_DELTA=0x06. These values are
stable and must not be reordered. A received type tag that does not map to a
known `MessageType` must be treated as a corrupt frame per R4.

R2b. A deserialized sequence number that is negative (bit 63 set) must be
treated as a corrupt frame per R4.

R3. All multi-byte integer fields in frame headers must use big-endian byte
order.

R4. A frame with a length prefix value that is negative (signed int32) or less
than 14 (minimum header size) must be treated as a corrupt frame. The transport
must close the connection and fail all pending futures.

R5. A single frame with a length prefix exceeding a configurable maximum frame
size (default 2 MiB) must be treated as corrupt. The transport must close the
connection and fail all pending futures. This limit applies to individual
frames, not reassembled logical messages.

### Stream Multiplexing

R6. Stream IDs must be allocated per-connection from a monotonically increasing
atomic counter, starting at 1. On wraparound past `Integer.MAX_VALUE`, the
counter must reset to 1, skipping 0 and all negative values. With at most R6a
pending entries and a (2^31 - 1) ID range, an ID returned to the pool by
request completion is unique against any newly-allocated ID for at least 32766
subsequent allocations under default config. After wraparound to 1, before
reusing IDs in the [1, R6a-cap] range, the implementation must verify each
candidate is not in the pending map; verification must be bounded by the R6a
cap. If the pending map is at capacity (R6a), allocation fails per R6a
regardless of the counter value.

R6a. The pending map must have a configurable maximum size (default 65536). If
`request()` is called when the pending map is at capacity, it must return a
future completed exceptionally with `IOException` indicating stream ID
exhaustion.

R7. Fire-and-forget messages (via `send()`) must use stream ID 0, signaling no
response expected.

R8. Request-response messages (via `request()`) must use a positive non-zero
stream ID. The pending `CompletableFuture` must be registered in the pending
map keyed by stream ID before the frame is written to the channel.

### Flags

R9. Bit 0 (0x01) of the flags byte is the MORE_FRAMES flag. When set on a
frame with a positive non-zero stream ID, it indicates additional frames with
the same stream ID follow and must be concatenated. The last frame in a
sequence has MORE_FRAMES cleared. A frame with stream ID 0 and MORE_FRAMES set
must be treated as a corrupt frame per R4 — fire-and-forget messages must be
single-frame.

R10. Bit 1 (0x02) of the flags byte is the RESPONSE flag. Response frames must
set this bit. Request and fire-and-forget frames must not.

### Receive Dispatch

R35a. Frame reassembly (R35) must occur before dispatch (R11-R14). When a
frame with MORE_FRAMES set arrives, it must be buffered regardless of other
flags. Only when reassembly is complete (final frame with MORE_FRAMES cleared)
must the reassembled message be dispatched per R11-R14 based on the flags of
the final frame.

R11. On receiving a complete (possibly reassembled) message with the RESPONSE
flag set and non-zero stream ID: look up the stream ID in the pending map. If
found, remove and complete the `CompletableFuture`. If not found (orphaned
response), discard and increment the orphaned-response counter.

R12. On receiving a complete message without the RESPONSE flag and with stream
ID 0 (fire-and-forget): invoke the handler registered for the type tag. If no
handler registered, discard and increment no-handler counter. Handler return
value must be discarded — no response is sent for fire-and-forget messages.

R13. On receiving a complete message without the RESPONSE flag and with
non-zero stream ID (incoming request): invoke the handler registered for the
type tag. On handler success, send response with same stream ID and RESPONSE
flag (0x02) set. Response frames that exceed max frame size must be chunked per
R43.

R14. If handler completes exceptionally for an incoming request, no response
frame is sent. The requesting peer's timeout handles the failure.

### Write Integrity

R15. Concurrent writes to the same connection must be serialized such that no
partial or interleaved frames appear on the wire.

R15a. For multi-frame messages, the write lock must be held for the entire
sequence of chunks. No other frame may be interleaved between chunks.
Implementations should note this causes head-of-line blocking for other writers
on the same connection; the configurable max frame size bounds worst-case delay
for single-frame writers.

R16. Write serialization must use `ReentrantLock` (not `synchronized`). This
provides explicit lock API benefits (tryLock, fairness) and avoids virtual
thread pinning on JDK 21-23.

R17. When a channel write produces fewer bytes than requested, the transport
must loop until all bytes are written while continuing to hold the write lock.

### Reader Loop

R18. Each active peer connection must have a dedicated virtual thread that
continuously reads frames from the channel and dispatches them per R11-R14.

R19. The reader must handle partial TCP reads by accumulating bytes until a
complete length prefix (4 bytes) is read, then accumulating until the full
frame (length-prefix-value bytes) is read.

R19a. The transport must use a bounded allocation strategy for frame read
buffers. Total memory for in-flight frame reads across all connections must be
bounded. Default global read buffer budget: 64 MiB, configurable. When
exhausted, reader threads must pause reads until buffer space is released.

R20. On read failure (IOException, end-of-stream indicated by read returning
-1), the reader must: mark the connection as dead, complete all pending
`CompletableFuture`s exceptionally with IOException, and schedule reconnection
per R24. Closing a connection must interrupt and join the reader virtual thread.
No frames from a closed connection may be dispatched after close completes.
Handler dispatches already in progress at the time of read failure (per R34)
must complete normally per R34a. Their attempted response writes will fail per
R29's post-dead-channel semantics; each such failure must increment R45(f) and
be logged at debug level. The R34b queue contents for the dead connection
must be drained and discarded; queue-discard counts must increment R45(h).
Drain may be lazy through the consumer path: each R34b queue task must carry
a reference to its originating connection (or an opaque connection epoch
token); the reader-thread join (R20) must mark the connection dead in O(1);
the handler-dispatch consumer side, on dequeue, must check the task's
connection state and discard with R45(h) increment if dead. Synchronous walk
of the queue is permitted but not required, provided that the dispatched-task
count for the dead connection reaches zero within a bounded time after
read-loop join (default 5 seconds, equal to handshake timeout).

### Connection Lifecycle

R21. Connections must be established lazily — no TCP connection is created
until the first `send()` or `request()` to a given peer.

R22. Connection establishment must use blocking `SocketChannel.open()` +
`connect()` on a virtual thread.

R22a. A connection is not considered active until the bidirectional handshake
(R40, R40-bidi) completes successfully. `send()` and `request()` calls
targeting a peer whose connection is in the handshake phase must block until
the handshake completes or fails. If the handshake fails, these calls fail per
R24a.

R23. The connection registry must prevent duplicate connections to the same
peer. If two threads trigger first-access to the same peer concurrently, only
one connection must be created.

R23a. The connection registry must use per-peer locking keyed by remote
`nodeId`. From the moment the handshake `nodeId` field is parsed, the per-peer
slot must be locked through registration completion and tie-break resolution.
Concurrent handshakes for distinct peers must proceed in parallel without
mutual blocking. The registry's top-level data structure access must be
lock-free or use a lock granularity (e.g., `ConcurrentHashMap` segment) that
does not serialize unrelated peers. The per-peer lock object identity must be
stable for the lifetime of the JVM for any nodeId that has ever been observed:
implementations must not evict per-peer Lock entries from the lock map. If
memory pressure on the lock map is a concern (e.g., 1000-node clusters with
churn), the implementation must use a fixed-size striped lock (e.g.,
`Striped<Lock>` style hash partitioning) so that lock identity is determined
by hash partition, not by registry presence. R30 step (1) must NOT remove the
per-peer Lock from the lock map; only the registry-value entry is removed.
When both peers independently connect to each other, the node with the
lexicographically smaller `nodeId` keeps its outbound connection; the other
node closes its outbound and uses the accepted inbound. If the losing
connection has pending futures, they must be failed before the per-peer lock
is released. Tie-break applies only to connections that have completed the
bidirectional handshake. If one connection is still handshaking when the
other completes, the completed connection wins regardless of `nodeId`
ordering, and the handshaking connection is aborted.

R23b. Any number of pending connections to the same peer may be in the
handshake phase simultaneously; the per-peer queue under the per-peer
registry lock (R23a) must hold all of them. When the FIRST one completes its
handshake, it wins immediately and ALL others are aborted with `IOException`
indicating duplicate-connection-rejected. After the winning connection is
registered, any subsequent handshake completion for the same peer (e.g., a
connection that was in flight but not yet in the queue at the moment of the
win) must be treated per R40a: rejected with handshake-failure counter
increment, regardless of `nodeId` ordering. R23a's tie-break clause applies
only when two connections complete handshake within the same atomic critical
section — if one is registered and the other arrives later, R40a takes
precedence. If all queued handshakes fail to complete within R40's 5s
timeout, all are aborted and the handshake-failure counter is incremented
once per aborted connection.

R24. On connection failure, the transport must reconnect with exponential
backoff: initial delay 100ms, doubling, capped at 30s. Only one reconnect
attempt per peer at a time.

R24a. `send()` or `request()` to a peer in reconnecting state: `send()` must
throw `IOException`, `request()` must return a future completed exceptionally,
both with descriptive error indicating peer temporarily unreachable. Caller is
responsible for retry.

R25. Before creating a replacement connection after failure, all pending
`CompletableFuture`s from the previous connection must be completed
exceptionally.

### Server Socket and Handshake

R39. The transport must bind a `ServerSocketChannel` on the local node's port
(from its `NodeAddress`) before any inbound connection can be accepted. The
accept-loop virtual thread must be started only after the transport's
constructor has completed and all final fields are safely published.
Implementations must choose ONE of:
- **(a)** bind the `ServerSocketChannel` in the constructor but defer
  accept-loop start to an explicit `start()` call invoked by the caller after
  construction; or
- **(b)** bind and start atomically inside a static factory method that
  returns the fully-constructed transport; or
- **(c)** defer the `bind()` call itself to `start()`, eliminating the
  listen-without-accept window entirely.

Direct accept-loop start from within the constructor is forbidden. Option (a)
implementations must additionally: (i) configure the `ServerSocketChannel`
backlog parameter explicitly to at least 1024 via `bind(addr, backlog)`;
(ii) document a recommended construction-to-`start()` budget of <100ms with
the warning that exceeding it risks OS-backlog overflow; (iii) on `start()`,
drain any already-pending connections from the OS backlog with a non-blocking
accept loop until empty before entering the steady-state accept loop, so that
the handshake-read clock is armed close to TCP-accept time. The implementation
must document its choice (a, b, or c) in the transport class javadoc.

R39a. If `accept()` throws an IOException and the transport is not closed, the
accept loop must log the error, increment the handshake-failure counter, and
continue. To prevent tight error loops (e.g., FD exhaustion), a brief backoff
(100ms) must be applied after a failed accept before retrying.

R40. On establishing a connection, the connecting node must send a handshake as
the first data. Wire format: 1-byte protocol version (version 1 for this
spec), then 4-byte big-endian int32 total-length, then UTF-8 nodeId (4-byte
length-prefixed), UTF-8 host (4-byte length-prefixed), 4-byte big-endian int32
port. On reading the version byte: if it does not equal 1, the implementation
must close the connection without reading further bytes, increment the
handshake-failure counter R45(e), and log the rejected version. Version
validation must occur before any payload bytes are read, before total-length
validation. The implementation must read the total-length field next and
reject the handshake if total-length exceeds 4 KiB, before reading any payload
bytes. Individual string length prefixes must be validated against remaining
bytes before allocating read buffers. The nodeId field must be non-empty
(length > 0) and must not equal the receiving node's own nodeId. Length must
be in [1, 256] bytes — this check must occur on the raw 4-byte wire
length-prefix BEFORE allocating a read buffer for the bytes. After reading
the bytes, the implementation must (not should) validate the byte sequence
is well-formed UTF-8 per RFC 3629, rejecting overlong encodings and unpaired
surrogates. Equivalence of `nodeId` for R40a duplicate detection and R23a
tie-break is byte-equivalence on the validated wire bytes (NOT
`String.equals` on decoded strings, to avoid normalization-form ambiguity).
Maximum handshake size: 4 KiB. Handshake read
timeout: 5 seconds. On malformed handshake (including version mismatch and
invalid nodeId): close connection, increment handshake-failure counter. On
timeout: close connection, increment counter.

R40-bidi. The accepting node must send its `NodeAddress` back in the identical
wire format as R40, including the protocol version byte. The connecting node
must validate that the received `NodeAddress` matches the intended target (same
nodeId, host, port). Both sides must validate that the received protocol
version matches. On mismatch: close connection, log error, increment counter.

R40a. If the accepting node receives a handshake with a `nodeId` that matches
an existing connection at a different host:port, the new connection must be
rejected (closed immediately) and the handshake-failure counter incremented.
`nodeId` must be unique per peer in the connection registry.

R41. Each connection must be associated with the full `NodeAddress` of the
remote peer. The receiver must use this associated `NodeAddress` as the
`sender` field when constructing `Message` records from received frames.

R42. If the accepting node already has a connection to the handshake peer,
resolve per R23a.

### Timeout and Resource Safety

R26. Every `CompletableFuture` from `request()` must have a timeout (default
30 seconds, configurable via builder). On timeout: complete exceptionally with
`TimeoutException`, remove pending map entry.

R26a. The timeout must not begin until the complete frame (or all chunks of a
multi-frame message) has been successfully written to the channel. If the write
fails, the future must be completed exceptionally with `IOException`
immediately, without waiting for timeout.

R26b. On write failure as defined in R26a, the pending-map entry registered by
R8 must be removed in the same step as future completion. The removal must
occur even if the connection is not yet marked dead. Arming the timeout per
R26a and registering the pending-map removal callback must be performed in a
single step relative to the future's completion. The timeout-arming mechanism
must be idempotent against R20's pending-map sweep: re-completion of an
already-terminal future and re-removal of an already-removed map entry must
both be no-ops. All pending-map removals (R11, R26, R26b, R20/R25) must use
value-conditional removal (`ConcurrentMap.remove(streamId, futureRef)` or
equivalent) so that removal succeeds only when the map entry's value identity
matches the future being cleaned up. If a different future is present (e.g.,
post-R6-wrap reuse), the removal must be a true no-op. The future reference
captured at R8 registration must be carried through to all cleanup paths for
value-equality comparison. If the timeout-arming step itself fails (e.g.,
`RejectedExecutionException` from a shutting-down scheduler), the
implementation must (a) immediately complete the future exceptionally with
`IOException(transport unavailable)`, (b) remove the pending-map entry by
value-conditional remove, (c) increment R45(f), and (d) propagate the failure
to the caller of `request()` if the call site is still on the calling thread.

R27. The pending map must not leak entries. Every entry must be removed exactly
once: by response completion (R11), timeout (R26), connection failure cleanup
(R20/R25), or write-failure cleanup (R26b).

### Send-Side Validation and Chunking

R43. When encoding a message whose body exceeds the maximum frame size minus 14
bytes header overhead, the transport must automatically split the body into
chunks. Each chunk is sent as a separate frame with the same stream ID. All
frames except the last must set the MORE_FRAMES flag (0x01). Chunk size must be
at most (max frame size - 14 bytes header). Write lock held for entire sequence
(R15a). This chunking requirement applies to ALL frame writes, including
response frames sent on behalf of handlers (R13).

R43a. In a multi-frame message, all chunks must carry the same type tag and
sequence number as the first chunk. On reassembly, the type tag and sequence
number from the first chunk are used to construct the `Message`. If a
non-first chunk's type tag differs from the first chunk's, the transport must
treat it as a corrupt frame per R4.

R44. Fire-and-forget messages (stream ID 0) that exceed the maximum frame size:
`send()` must throw `IllegalArgumentException`. Fire-and-forget cannot be
chunked (R9).

### Multi-frame Reassembly

R35. When a frame with MORE_FRAMES set arrives, the transport must buffer the
body and continue reading subsequent frames with the same stream ID until a
frame with MORE_FRAMES cleared arrives. The concatenated bodies form the
complete logical message.

R36. The transport must reassemble multi-frame messages transparently —
handlers and pending `CompletableFuture`s receive complete reassembled
messages, never individual chunks.

R37. The transport must enforce a configurable maximum reassembled message size
(default 64 MiB). If reassembly exceeds this limit, the transport must discard
the partial buffer and enter a drain state for that stream ID. Drain state is
per-stream-ID (the implementation must maintain a set of draining stream IDs
and check membership on each received frame). Frames for other stream IDs must
be processed normally during drain. All subsequent frames with the draining
stream ID and MORE_FRAMES set must be discarded without buffering. When a frame
with MORE_FRAMES cleared arrives for that stream ID, drain ends and the final
chunk is also discarded. For an outbound request whose response is being
reassembled and exceeds the limit: complete the pending future from R8
exceptionally with `IOException` indicating the message exceeded the size
limit. For an inbound request whose body is being reassembled and exceeds the
limit: increment R45's `(l) request-too-large-discarded` counter, do not
invoke any handler, and send no response (the originating peer's R26 timeout
handles the failure). The handler-dispatch path is bypassed in this case.

R37c. Each connection must track consecutive R37 inbound size-limit
violations. If a single connection produces more than `N` (default 4,
configurable) such violations within a sliding window (default 60 seconds,
configurable), the connection must be closed per R5's failure semantics
(close, fail pending futures, schedule reconnection per R24) and R45(c) must
increment in addition to R45(l). The implementation must also emit a
structured log entry naming the offending peer's `nodeId`. This bounds the
denial-of-service surface introduced by R37's silent-drop discipline for
inbound oversized requests.

R37a. Total memory used for in-progress reassembly buffers across all
connections and stream IDs must be bounded (default 64 MiB, independently
configurable from R19a). Before allocating the first reassembly buffer for a
multi-frame message (a frame with MORE_FRAMES set on a stream-id with no
existing reassembly state), the transport must check that the post-allocation
total memory across all in-progress reassemblies will not exceed the global
reassembly budget. If it would, the stream must be entered into drain state
(R37) before the first chunk's body is buffered. Single-frame messages
(MORE_FRAMES cleared on first frame) do not consume reassembly-budget memory
and are bounded only by R5's max-frame-size. Reassembly buffers are separate
from and additional to the R19a read buffer budget; the builder must expose
both limits.

R38. Multi-frame reassembly must be isolated per stream ID. Chunks for stream
ID 5 must not interfere with reassembly of stream ID 7.

### Shutdown

R28. On `close()`, the transport must: (1) set a closed flag preventing new
`send()`/`request()` calls, (2) complete all pending `CompletableFuture`s
exceptionally across all connections, (3) close all TCP channels, (4) close the
server socket, (5) terminate all reader virtual threads and the accept loop.
The accept loop must check the closed flag after each `accept()` — if closed,
close any newly accepted channel immediately without handshake processing.
`close()` must interrupt and join the accept-loop virtual thread (bounded
timeout, 5 seconds) to ensure no new connections are accepted after close
completes. In-progress handler invocations dispatched per R34 must complete
normally per R34a's non-interruption rule; any response they attempt to send
after step (1) must be discarded silently. The transport must increment
R45's `(k) post-close-response-discards` counter for each such drop,
distinguishing it from R45(f) write-failures and R34b overflows.

R29. After `close()`: `send()` must throw `IOException` with a message
indicating the transport is closed. `request()` must throw
`IllegalStateException`. `registerHandler()` and `deregisterHandler()` must
throw `IllegalStateException`.

### Peer Departure

R30. On peer-departure notification, the transport must (1) atomically under
the per-peer registry lock from R23a: install a `cleanupBarrier`
(`CompletableFuture<Void>` or equivalent) into the per-peer slot, mark the
peer as DEPARTED, remove the registry entry, and cancel any in-progress
reconnect task; (2) outside the registry lock and on a dispatched virtual
thread: close the channel, complete pending futures exceptionally, and as the
final action complete the `cleanupBarrier`. The slot identity (per-peer Lock
plus barrier slot) must persist across the cleanup window per R23a's lock
lifetime rule. The DEPARTED mark, registry removal, and barrier installation
in step (1) must all be visible to any subsequent `send()`/`request()` per
R30a before step (2) begins. Step (2) must not block the notification
thread — implementations must dispatch step (2) to a virtual thread.

R30a. The reconnection loop must check that the peer has not departed before
establishing a new connection. Subsequent `send()`/`request()` to a re-joining
peer must acquire the per-peer registry lock and check for in-progress
cleanup before creating a new connection. The check is: `cleanupBarrier ==
null || cleanupBarrier.isDone()`. If the barrier exists and is not done, the
caller must `await` the barrier (with a timeout matching R26's request
timeout) under the per-peer lock; on timeout the new connection attempt is
abandoned with `IOException(cleanup-timeout)`. If cleanup is in progress, the
new connection must wait for cleanup (the barrier) to complete before
proceeding.


### Scale

R31. The transport must use exactly one TCP connection per peer, supporting
clusters of up to 1000 nodes (at most 999 simultaneous connections, 999 reader
virtual threads, plus the accept-loop virtual thread).

### Health

R32. Connection health must be monitored passively via membership protocol
heartbeats. The transport must not generate its own health-check or keep-alive
traffic.

### Concurrency

R33. The NIO transport implementation must be thread-safe. Multiple threads may
concurrently call `send()`, `request()`, `registerHandler()`, and
`deregisterHandler()`.

R34. The transport must dispatch handler invocations on a virtual thread
separate from the reader thread. The reader thread's sole responsibility is
frame parsing, reassembly, and dispatch initiation — it must never block on
handler execution. Each incoming request or fire-and-forget message spawns (or
submits to) a virtual thread for handler execution. Handler implementations may
perform blocking I/O, acquire locks, or take arbitrarily long without affecting
frame parsing on the connection.

R34a. `deregisterHandler()` takes effect immediately — subsequent incoming
messages of that type are discarded. Handler invocations already in progress at
the time of deregistration are not interrupted and may complete normally.

R34b. The transport must limit concurrent handler virtual threads via a
configurable bounded semaphore (default 256 globally). When the semaphore is
full, incoming messages are queued in a bounded queue (default 1024). When the
queue is full, incoming messages are discarded and the overflow counter
incremented. When a discarded message has a non-zero stream ID (incoming
request), no response is sent; the remote peer's future will complete via
timeout (R26). This is intentional — the transport provides no explicit
backpressure signal to remote peers.

R34c. Response messages (RESPONSE flag set) must bypass the R34b semaphore and
queue. Responses are completed directly on the reader virtual thread by
removing the entry from the pending map (R11) and completing the future.
Handler dispatch (R12, R13) is the only path subject to R34b limits. The
reader virtual thread must never block on R34b semaphore acquisition for
response messages. This requirement prevents handler-callback deadlock when a
handler in the bounded pool issues `request()` and then awaits the response —
the response cannot be enqueued behind handler-bound traffic. After R28
step (1) sets the closed flag, the reader thread must check the closed flag
before completing any in-flight response future. If closed, the response must
be discarded and the future must NOT be completed (it will be completed
exceptionally by R28 step (2)). Implementations must order step (1)'s flag
set and step (2)'s pending iteration with a memory barrier such that any
reader thread observing the flag set will not subsequently `complete(value)`
a future that step (2) has already completed exceptionally. Future completion
on the reader thread must use `complete()` (not `obtrudeValue()`); use of
`obtrudeValue` is forbidden.

R34d. Handler completion is defined as: for synchronous handlers, the moment
the handler method returns or throws; for asynchronous handlers (those that
return `CompletableFuture<Message>` or equivalent), the moment the returned
future transitions to a terminal state. The R34b semaphore must remain held
until handler completion under this definition, NOT merely until the method
returns. R45(m) must increment on either a synchronous throw or an
asynchronous exceptional completion. R34a's "in-progress" handler is defined
identically: a handler is in progress until the returned future completes.

### Observability

R45. The transport must maintain queryable counters for: (a) orphaned
responses, (b) no-handler discards, (c) corrupt frame disconnections, (d)
reassembly limit exceeded, (e) handshake failures (malformed, timeout,
version mismatch, invalid nodeId, duplicate nodeId, accept errors), (f) write
failures, (g) reconnection attempts, (h) handler dispatch overflow, (i)
stream ID exhaustion, (j) handshake-blocked send/request count (R22a wait
events), (k) post-close response discards (R28), (l)
request-too-large-discarded (R37 inbound size-limit), (m) handler exceptions
on incoming requests (R14). These counters must be accessible via a
metrics/stats method.

---

## Design Narrative

### Intent

Implement the `ClusterTransport` SPI as a single-connection-per-peer
multiplexed NIO transport using Kafka-style binary framing. This is the
foundational networking layer for all cluster communication — membership
heartbeats, query scatter-gather, metadata replication, and future bulk
transfer all share the same framing protocol.

### Why this approach

The connection-pooling ADR chose single-connection multiplexing over connection
pools or connection-per-request because it minimizes file descriptor usage (1
FD/peer, critical at 1000 nodes), avoids channel exclusivity problems (PING
can't be blocked behind QUERY_RESPONSE), and provides the framing base layer
that traffic priority (DRR) and backpressure (credit-based Flow API) build on.

The incremental complexity over bare TCP (correlation dispatch, pending map,
handshake, reassembly) is unavoidable — even connection-per-peer needs framing
for `request()` semantics.

The wire format is binary framing inspired by Kafka's 4-byte length prefix +
fixed-position header pattern, but with three deliberate divergences:
(1) per-direction stream-id allocation requiring a RESPONSE flag (R10);
(2) an 8-byte sequence number for membership protocol use (no Kafka analogue);
(3) bidirectional handshake with version validation (R40-bidi).
Implementers should not assume Kafka wire-protocol compatibility — the
spec, not Kafka, is authoritative.

### What was ruled out

- **Elastic pool per peer**: FD multiplication (4000 FDs at 1000 nodes)
- **Connection-per-peer with exclusive channel**: membership ping starvation
  during long query responses
- **HTTP/2 / QUIC**: requires complex implementation without external
  dependencies
- **VThread-per-message with shared pool**: over-engineered for
  message-oriented transport

### Key design decisions not in the original ADR

**RESPONSE flag (R10):** Added during spec authoring. Both peers independently
allocate stream IDs, so a direction indicator is needed to prevent the receiver
from confusing an incoming request with a response to its own outbound request.

**Bidirectional handshake (R40-bidi):** Added during adversarial review. Without
mutual identification, a DNS misdirection could route connections to the wrong
node with no detection. The acceptor sends its NodeAddress back for the
connector to validate.

**14-byte header (R2):** Expanded from 6 bytes to include the 8-byte sequence
number, which is needed for deduplication and ordering in the membership
protocol. The alternative (sequence number in the body) would corrupt the
payload boundary visible to handlers.

**Handler thread bounding (R34b):** Added during adversarial review. Without a
limit, a message flood could spawn millions of virtual threads, exhausting
memory. The bounded semaphore + queue provides graceful degradation.

**Reassembly memory budget (R37a):** Added during adversarial review. Without a
global cap, an attacker could open reassembly streams on many connections
simultaneously, consuming unbounded memory.

### Hardening summary

This spec was refined through four adversarial falsification rounds:
- Round 1: 20 findings — server socket, handshake, sender identity, sequence
  number, bidirectional connections, stream ID wraparound, type tag encoding,
  send-side validation
- Round 2: 18 findings — handshake format, handler threading, negative sequence
  number, reassembly drain, timeout/write interaction, connection state machine,
  read buffer budget
- Round 3: 7 findings — reverse handshake format, chunk header consistency,
  handshake state, reassembly memory budget, accept loop resilience, handshake
  field validation
- Round 4 (v2): 16 findings — pending-map cleanup on write failure (R26b),
  peer-departure cleanup atomicity (R30 split into atomic-mutation + dispatched
  side-effects), handler-callback dispatch deadlock (R34c response bypass),
  reassembly limit inbound vs outbound disambiguation (R37 split), per-peer
  registry locking (R23a generalised), dual-handshake-in-flight resolution
  (R23b), accept-loop safe publication (R39), handshake version validation
  and nodeId well-formedness (R40), reassembly budget first-chunk check (R37a),
  stream-id wrap clarification (R6), in-flight handler dispatch on dead
  connection (R20 extension), post-close handler response disposition (R28
  extension), observability counters extended (R45 j/k/l/m), Kafka-divergence
  narrative clarification.
- Round 5 (v3, depth pass — fix-consequence bugs from Round 4): 11 findings —
  R30 cleanupBarrier + R30a wait semantics for the dispatched-cleanup window,
  R23a per-peer Lock object lifetime stability (no eviction, or stripe by
  hash), R26b value-conditional pending-map removal + scheduler-failure
  handling, R37c per-connection abuse threshold for repeated inbound
  size-limit violations, R34c reader-thread closed-flag check before
  completing in-flight responses (forbid `obtrudeValue`), R39 explicit
  backlog-and-budget discipline for option (a) plus option (c) for
  bind-on-start, R20 lazy queue drain via per-task connection epoch (avoids
  O(N) iteration under R34b lock), R23b N≥3 connection generalisation
  (R23a-vs-R40a precedence), R34d handler completion semantics for
  synchronous vs asynchronous handlers, R40 nodeId length-prefix validation
  on raw wire bytes (UTF-8 well-formedness MUST not should), Kafka-divergence
  already addressed in Round 4.

### Known uncertain area (carried forward, not normative)

UF4 — R34c reader-thread response completion may execute caller-supplied
synchronous callbacks (`.thenApply` / `.whenComplete` chains) on the reader
thread, blocking frame parsing for the duration of those callbacks. R34c
prevents handler-callback deadlock by bypassing R34b for responses, but does
not bind the caller's chained-callback execution policy. Callers that need
to perform blocking work in response to a request future must use
`.thenApplyAsync(executor)` to offload. This is a usage discipline, not a
spec requirement; the spec author may choose to tighten R34c to mandate
`completeAsync(value, dispatchExecutor)` on the reader thread if profiling
demonstrates reader starvation. Not normative pending real-world signal.

## Verification Notes

This spec was authored through `/spec-author` with three formal adversarial
falsification rounds (the "Hardening summary" Round 4 + Round 5 in this
narrative; Rounds 1-3 were the pre-migration F19 hardening). All findings
were applied; the v3 spec carries an ambiguity score of 0.00 (no
[UNVERIFIED], [UNRESOLVED], or [CONFLICT] markers across 69 requirement
clauses). The spec has zero `open_obligations` at registration.

Direct implementation and test annotations will be added during the
`/work-start` implementation phase per the WD-01 acceptance criterion
"transport.multiplexed-framing R1-R∞ all have direct impl + test
annotations". The new requirements introduced in v2/v3 (R23b, R26b, R34c,
R34d, R37c) and the amended requirements (R6, R20, R23a, R27, R28, R30,
R30a, R34c, R37, R37a, R39, R40, R45) all require corresponding test
coverage during `/work-start` `/feature-test` and `/feature-implement`
stages.

---
{
  "id": "F19",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["cluster-transport"],
  "requires": [],
  "invalidates": [],
  "decision_refs": ["connection-pooling", "transport-abstraction-design"],
  "kb_refs": ["distributed-systems/networking/multiplexed-transport-framing"],
  "open_obligations": []
}
---

# F19 — Multiplexed Transport Framing

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
counter must reset to 1, skipping 0 and all negative values. The
implementation must ensure no stream ID currently in the pending map is reused.

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

R23a. When both peers independently connect to each other, the node with the
lexicographically smaller `nodeId` keeps its outbound connection; the other
node closes its outbound and uses the accepted inbound. The connection registry
must be locked from handshake validation through registration and tie-break
resolution. If the losing connection has pending futures, they must be failed
before the lock is released. Tie-break applies only to connections that have
completed the bidirectional handshake. If one connection is still handshaking
when the other completes, the completed connection wins regardless of nodeId
ordering, and the handshaking connection is aborted.

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
(from its `NodeAddress`) at construction time. A dedicated virtual thread must
run the accept loop.

R39a. If `accept()` throws an IOException and the transport is not closed, the
accept loop must log the error, increment the handshake-failure counter, and
continue. To prevent tight error loops (e.g., FD exhaustion), a brief backoff
(100ms) must be applied after a failed accept before retrying.

R40. On establishing a connection, the connecting node must send a handshake as
the first data. Wire format: 1-byte protocol version (version 1 for this
spec), then 4-byte big-endian int32 total-length, then UTF-8 nodeId (4-byte
length-prefixed), UTF-8 host (4-byte length-prefixed), 4-byte big-endian int32
port. The implementation must read the total-length field first and reject the
handshake if total-length exceeds 4 KiB, before reading any payload bytes.
Individual string length prefixes must be validated against remaining bytes
before allocating read buffers. Maximum handshake size: 4 KiB. Handshake read
timeout: 5 seconds. On malformed handshake: close connection, increment
handshake-failure counter. On timeout: close connection, increment counter.

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

R27. The pending map must not leak entries. Every entry must be removed exactly
once: by response completion (R11), timeout (R26), or connection failure
cleanup (R20/R25).

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
chunk is also discarded. For request-response: complete the pending future
exceptionally with `IOException` indicating the message exceeded the size
limit.

R37a. Total memory used for in-progress reassembly buffers across all
connections and stream IDs must be bounded (default 64 MiB, independently
configurable from R19a). When this global reassembly budget is exhausted, new
multi-frame streams must enter drain state (R37) immediately. Reassembly
buffers are separate from and additional to the R19a read buffer budget; the
builder must expose both limits.

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
completes.

R29. After `close()`: `send()` must throw `IOException` with a message
indicating the transport is closed. `request()` must throw
`IllegalStateException`. `registerHandler()` and `deregisterHandler()` must
throw `IllegalStateException`.

### Peer Departure

R30. On peer departure notification: close connection, complete pending futures
exceptionally, remove from registry, cancel in-progress reconnection. Departure
cleanup must atomically remove the connection and mark the peer as departed.
Must not block the notification thread.

R30a. The reconnection loop must check that the peer has not departed before
establishing a new connection. Subsequent `send()`/`request()` to a re-joining
peer must acquire the registry lock and check for in-progress cleanup before
creating a new connection. If cleanup is in progress, the new connection must
wait for cleanup to complete.

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

### Observability

R45. The transport must maintain queryable counters for: (a) orphaned
responses, (b) no-handler discards, (c) corrupt frame disconnections, (d)
reassembly limit exceeded, (e) handshake failures (malformed, timeout,
duplicate nodeId, accept errors), (f) write failures, (g) reconnection
attempts, (h) handler dispatch overflow, (i) stream ID exhaustion. These
counters must be accessible via a metrics/stats method.

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

This spec was refined through three adversarial falsification rounds:
- Round 1: 20 findings — server socket, handshake, sender identity, sequence
  number, bidirectional connections, stream ID wraparound, type tag encoding,
  send-side validation
- Round 2: 18 findings — handshake format, handler threading, negative sequence
  number, reassembly drain, timeout/write interaction, connection state machine,
  read buffer budget
- Round 3: 7 findings — reverse handshake format, chunk header consistency,
  handshake state, reassembly memory budget, accept loop resilience, handshake
  field validation

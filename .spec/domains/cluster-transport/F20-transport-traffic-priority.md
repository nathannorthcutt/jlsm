---
{
  "id": "F20",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["cluster-transport"],
  "requires": ["F19"],
  "invalidates": ["F19.R15", "F19.R15a", "F19.R16"],
  "amends": ["F19.R43", "F19.R26a", "F19.R44"],
  "decision_refs": ["transport-traffic-priority", "connection-pooling"],
  "kb_refs": ["distributed-systems/networking/transport-traffic-priority"],
  "open_obligations": []
}
---

# F20 — Transport Traffic Priority

## Requirements

### Traffic Classes

R1. The transport must define five traffic classes, ordered by priority:
CONTROL, METADATA, INTERACTIVE, STREAMING, BULK.

R2. Each traffic class must have a configurable weight (positive integer).
Default weights: CONTROL=3, METADATA=2, INTERACTIVE=4, STREAMING=3, BULK=1.
Weights are relative — they determine the proportion of send bandwidth each
class receives when multiple classes are active.

R3. Each `MessageType` must be assigned to exactly one traffic class. Default
mapping:

| MessageType | Traffic Class |
|-------------|--------------|
| PING | CONTROL |
| ACK | CONTROL |
| VIEW_CHANGE | METADATA |
| STATE_DIGEST | METADATA |
| STATE_DELTA | METADATA |
| QUERY_REQUEST | INTERACTIVE |
| QUERY_RESPONSE | INTERACTIVE |

The mapping must be configurable to accommodate future message types.
VIEW_CHANGE is classified as METADATA (not CONTROL) because view change
messages can be large during mass rejoin events, violating the small/rate-
limited assumption that makes CONTROL bypass safe.

R4. Traffic class assignment must occur at enqueue time based on the message's
`MessageType`. The assignment must not change after enqueue.

### DRR Scheduler

R5. The transport must use a Deficit Round Robin (DRR) scheduler for outgoing
frames. Each traffic class maintains a FIFO queue and two counters: quantum
(bytes granted per round, equal to weight x base quantum) and deficit
(accumulated unused bytes from prior rounds).

R6. The DRR base quantum must be configurable (default 16 KiB). Each class's
quantum equals its weight x base quantum. With default weights and 16 KiB
base: CONTROL=48 KiB, METADATA=32 KiB, INTERACTIVE=64 KiB, STREAMING=48 KiB,
BULK=16 KiB.

R7. The DRR algorithm per write cycle:

```
drain CONTROL queue completely (R9 bypass)
for each non-CONTROL class in round-robin order:
    if writer is mid-unpack of a ChunkedMessage wrapper (R13a):
        write next chunk, deficit -= chunk size
        if deficit exhausted or wrapper complete: continue to next class
    deficit += quantum
    while queue non-empty AND head entry size <= deficit:
        dequeue entry
        if entry is a single frame:
            write to channel, deficit -= frame size
        if entry is a ChunkedMessage wrapper:
            begin unpacking (R13a)
    if queue is empty:
        deficit = 0    // no credit banking when idle
```

R8. When a class's queue is empty, its deficit must reset to zero. This
prevents credit accumulation during idle periods from causing burst sends when
traffic resumes.

### CONTROL Bypass

R9. CONTROL traffic must bypass the DRR round entirely. Before each DRR cycle,
the scheduler must drain the CONTROL queue completely. This guarantees zero
scheduling delay for heartbeats. When the connection-dead flag is set (R28),
CONTROL bypass must not write to the channel — all CONTROL messages dequeued
during connection failure cleanup are discarded silently (heartbeats to a dead
peer are meaningless).

R10. CONTROL bypass safety: CONTROL messages are PING and ACK only — small
(~100 bytes), rate-limited (~1-5/s/peer). The bypass cannot starve other
classes because CONTROL volume is negligible relative to link capacity. No
additional rate limiting on CONTROL beyond bypass drain.

### Message Chunking for Interleaving

R11. Request-response messages (non-zero stream ID) whose encoded body exceeds
a configurable chunk size (default 64 KiB) must be split into chunks before
enqueueing. Each chunk becomes a frame with the MORE_FRAMES flag (F19 R9) set
on all but the last. Fire-and-forget messages (stream ID 0) are NOT chunked
and must not exceed the DRR chunk size. `send()` must throw
`IllegalArgumentException` for fire-and-forget messages whose encoded body
exceeds the chunk size. This ensures the worst-case blocking duration for any
single write is bounded by the chunk size.

R12. The DRR chunk size (default 64 KiB) is independent of F19's maximum frame
size (default 2 MiB). The DRR chunk size must be less than or equal to F19's
max frame size.

R13. All chunks of a multi-frame message must be enqueued as a single
`ChunkedMessage` wrapper object via a single queue `offer()`. The wrapper
preserves lock-free single-offer semantics while maintaining chunk ordering
within a stream.

R13a. The writer thread unpacks `ChunkedMessage` wrappers during drain. Deficit
is checked against individual chunk size, not total wrapper size. The writer
writes chunks one at a time, decrementing deficit per chunk. When deficit is
exhausted mid-wrapper, the remaining chunks are held by the writer (not
re-queued) and written at the start of the next round for that class — before
checking the queue head. This allows interleaving across classes while ensuring
all chunks of a message are eventually written in order.

R14. Chunking for DRR interleaving replaces F19 R15a. Chunks from different
streams and classes are intentionally interleaved. F19 R38 (per-stream-ID
reassembly isolation) ensures correct receiver-side reassembly despite
interleaving.

### Writer Thread Model

R15. Each connection must have a single dedicated writer virtual thread that
runs the DRR loop. This thread is the sole writer to the `SocketChannel`.
This replaces F19 R15/R16's write lock model — frame atomicity is guaranteed
because only one thread writes.

R16. The enqueue path must be lock-free. Each traffic class queue must be a
`ConcurrentLinkedQueue` or equivalent lock-free FIFO. Each class has a
configurable byte budget (default per-class: 8 MiB, total across all classes:
32 MiB). When a class exceeds its budget: `send()` must throw `IOException`,
`request()` must return a future completed exceptionally, both with a
descriptive error indicating queue backpressure. The budget check and increment
must be a single atomic operation (CAS loop) to prevent TOCTOU races from
concurrent senders. Byte budget is decremented (via atomic counter) when the
writer dequeues.

R17. The writer thread must park (via `LockSupport.park()`) when all queues are
empty. The enqueue path must call `LockSupport.unpark(writerThread)` after a
successful offer. The writer thread must not busy-spin when idle.

R18. The `SocketChannel` must be in blocking mode for the writer thread. When
the channel's send buffer is full, `channel.write()` blocks naturally — the
virtual thread parks on the carrier. This is consistent with F19's reader
thread model (F19 R18).

### Handler Response Path

R27. Handler response frames (from F19 R13) must be enqueued into the DRR
queue for the traffic class determined by the response's `MessageType`.
Handler threads must never write to the channel directly. Responses follow the
same chunking rules as outbound messages (R11 — chunked if exceeding chunk
size, since handler responses use non-zero stream IDs).

R27a. Handler responses bypass per-class byte budget (R16). They represent
completed work — rejecting a response after the handler has already processed
the request would silently waste computation and cause the caller to
experience a misleading timeout. The global byte budget still applies as a
safety net. If the global budget rejects a handler response, the transport
must increment a distinct "handler-response-rejected" counter (R26) so
operators can distinguish this from normal queue backpressure.

### Connection Failure Handling

R28. On connection drop (detected by writer thread via write IOException, or
signaled by reader thread via shared connection-dead flag), the writer thread
must: (1) set the connection-dead flag (if not already set), (2) drain all DRR
queues (including CONTROL), (3) for each dequeued entry with a non-zero stream
ID not in the cancelled set (R29): look up the stream ID in the pending map
and complete the future exceptionally with IOException, (4) discard fire-and-
forget frames and already-cancelled stream frames silently. R28 supersedes R9
— no writes to a dead channel.

### Timeout Integration

R20. Timeout (F19 R26) starts at enqueue time — when `request()` is called
and the future is created. Queue delay consumes part of the timeout budget. If
the timeout fires while chunks are still queued or mid-write, the stream ID
is added to the cancelled set (R29) and the pending future is completed
exceptionally with `TimeoutException`.

R29. The transport must maintain a cancelled set of stream IDs whose futures
have been completed (by timeout or other means) before their frames were fully
written. The writer thread must check this set before writing each frame or
chunk. If a frame's stream ID is in the cancelled set, the frame (or remaining
wrapper chunks) must be discarded without writing. The cancelled set entry
must be removed after the writer encounters and discards the entry. The
enqueue path must also check the cancelled set — if the stream is already
cancelled at enqueue time, skip the enqueue.

### Reassembly Idle Timeout (F19 amendment)

R30. F19 must be amended to add a per-stream reassembly idle timeout (default
60 seconds — 2x the default request timeout). If a reassembly buffer receives
no new chunks within this timeout, the buffer must be discarded and any
associated pending future completed exceptionally with IOException indicating
stale reassembly. This prevents receiver-side memory leaks when a sender
times out and discards remaining chunks of a partially-written multi-frame
message.

### Interaction with F19

R19. F20 amends F19's write path:
- F19 R15: replaced by single-writer-thread (R15)
- F19 R15a: replaced by R14 (chunks interleave across classes)
- F19 R16: no longer needed (lock-free enqueue + single writer)
- F19 R17: still applies (writer thread loops on partial writes within a
  single frame write)
- F19 R43: chunking threshold becomes DRR chunk size (R11) for request-
  response messages; fire-and-forget limited to chunk size
- F19 R26a: replaced by R20 (timeout starts at enqueue time)
- F19 R44: fire-and-forget size limit reduced to DRR chunk size (R11)

### Channel Write Batching

R21. The writer thread should use `GatheringByteChannel.write(ByteBuffer[])`
to batch the frame header and body into a single syscall when possible,
reducing per-frame system call overhead.

### Worst-case CONTROL Delay

R22. The worst-case delay for a CONTROL message is bounded by one chunk write
(default 64 KiB). At 1 Gbps, this is ~0.5 ms. CONTROL bypass (R9) drains the
CONTROL queue before each DRR round, so CONTROL waits at most for one in-
progress blocking write of one chunk to complete. Fire-and-forget messages are
capped at chunk size (R11), so no single-frame write exceeds this bound.

### Concurrency

R23. DRR state (deficit counters, round-robin position, mid-unpack state) is
single-threaded (the writer thread). No synchronization is needed for DRR
internal state. Thread safety is provided by lock-free enqueue queues (R16)
and the cancelled set (R29, concurrent read/write from timeout threads and
writer thread — must be a ConcurrentHashMap.KeySetView or equivalent).

R24. The writer thread must be started when the connection becomes active
(after handshake, per F19 R22a) and terminated on connection close, failure,
or transport shutdown. On graceful close (F19 R28), the writer thread must
drain all queues with a bounded timeout (5 seconds). After the timeout,
remaining frames are discarded: pending futures completed exceptionally,
fire-and-forget frames dropped. The writer thread must then terminate.

### Configuration

R25. All DRR parameters must be configurable via the transport builder:
traffic class weights, base quantum, chunk size, MessageType-to-traffic-class
mapping, per-class byte budget, total byte budget.

### Observability

R26. The transport must maintain per-traffic-class queryable counters for:
(a) frames enqueued, (b) frames written, (c) bytes written, (d) current queue
depth (bytes — ChunkedMessage wrappers count as total byte size), (e) frames
rejected (per-class budget exceeded), (f) frames dropped on disconnect,
(g) queuing latency (max time-in-queue gauge per class), (h) chunks discarded
due to stream timeout (R29), (i) handler-response-rejected (R27a — distinct
from normal rejected), (j) bytes pending write (partially-unpacked wrappers
held by writer thread). These counters must be accessible via the same
metrics/stats method as F19 R45.

---

## Design Narrative

### Intent

Layer a weighted priority scheduler on top of F19's multiplexed framing to
prevent heartbeat starvation and provide proportional bandwidth allocation
across traffic types on the single-connection-per-peer transport.

### Why DRR

O(1) per dequeue, inherently starvation-free, work-conserving. The 5 fixed
traffic classes make O(1) iteration trivial. Full WFQ adds O(log N) virtual
clock bookkeeping for no practical benefit at 5 classes. EDF requires deadline
propagation end-to-end — cross-cutting complexity for marginal latency benefit.

### Why CONTROL bypass

Heartbeats are correctness-critical — phi accrual failure detection depends on
timely delivery. The bypass adds zero scheduling delay. CONTROL traffic is
limited to PING and ACK (small, rate-limited), so bypass cannot starve other
classes. VIEW_CHANGE was moved to METADATA during adversarial review because
view change messages during mass rejoin can be kilobytes, violating the
small/rate-limited assumption.

### Why chunk at 64 KiB

Interleaving granularity. A 2 MiB frame blocks all other classes for ~16 ms at
1 Gbps. A 64 KiB chunk blocks for ~0.5 ms. Finer chunking improves latency
fairness at the cost of more frames (each with 14-byte header overhead). 64 KiB
matches HTTP/2's default max frame size. Fire-and-forget messages are also
capped at chunk size (discovered during adversarial review — a 2 MiB single-
frame fire-and-forget would violate the CONTROL delay bound).

### Amendment to F19

F20 replaces F19's write-lock model with a queue-based single-writer-thread.
This is a deliberate architectural change: the write lock model is correct for
a transport without priority scheduling, but DRR requires the scheduler (not
the caller) to decide write order. F19's framing, dispatch, handshake, and all
receive-side requirements are unchanged.

F20 also amends F19's timeout model: timeout starts at enqueue time (not after
write), so queue delay correctly consumes the caller's timeout budget. And it
adds a reassembly idle timeout to F19 (R30) to prevent receiver-side memory
leaks from partially-written multi-frame messages.

### Hardening summary

This spec was refined through two adversarial falsification rounds:
- Round 1: 11 findings — unbounded queue memory, queued frame fate on
  disconnect, handler response path, timeout semantics, CONTROL bypass
  safety (VIEW_CHANGE reclassified), fire-and-forget chunking contradiction,
  atomic multi-chunk enqueue, blocking vs non-blocking channel mode
- Round 2: 11 findings — receiver-side orphaned reassembly on sender timeout,
  timeout/failure double-completion race, TOCTOU byte budget, ChunkedMessage
  deficit accounting, handler response budget bypass, fire-and-forget HOL
  blocking, graceful shutdown drain, cancelled-set lifecycle

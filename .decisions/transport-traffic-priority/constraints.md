---
problem: "Transport traffic priority — how to schedule outgoing messages by priority class on the multiplexed single-connection transport"
slug: "transport-traffic-priority"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — transport-traffic-priority

## Problem Statement
On the single multiplexed TCP connection per peer (decided in connection-pooling ADR),
different traffic types compete for send bandwidth. Without scheduling, a large bulk
transfer or burst of query responses can starve membership heartbeats — a correctness
issue since phi accrual failure detection depends on timely heartbeat delivery.
The transport needs a send-side scheduler that prioritizes traffic classes while
preventing starvation and keeping implementation complexity low.

## Constraints

### Scale
Up to 1000 nodes, 999 peer connections. Mixed traffic: heartbeats (1-5/s per peer,
~100 bytes), query scatter-gather (burst, variable size), replication/compaction
sync (steady throughput, large payloads), bulk snapshot transfer (rare, very large).
All traffic multiplexed on a single TCP connection per peer.

### Resources
Pure Java NIO — no external dependencies, no OS-level QoS, no kernel bypass.
Constrained file descriptors (~4096 container default). Writer thread per connection
is the scheduling context — no separate QoS subsystem or thread pool.

### Complexity Budget
Small team, no networking specialization. Implementation must be understandable
and debuggable. Single-threaded scheduler on the writer thread. No virtual-clock
bookkeeping. No complex dependency trees (HTTP/2 stream priority is over-engineered
for this use case).

### Accuracy / Correctness
Heartbeats must NEVER be starved — phi accrual failure detector depends on timely
delivery. Query responses should see bounded worst-case latency increase from
scheduling. Bulk transfers must eventually complete (no indefinite starvation).
All traffic classes must get some minimum bandwidth share.

### Operational Requirements
Configurable weights per traffic class. No runtime tuning required initially —
static weights are sufficient for v1. Monitoring: per-class queue depths and
dequeue rates should be observable for debugging. Latency overhead of scheduling
must be negligible (<1μs per dequeue decision).

### Fit
Builds directly on the connection-pooling ADR's framing protocol: type byte classifies
messages into traffic classes at enqueue time, flags byte carries MORE_FRAMES for
chunking, stream IDs enable per-stream flow control. Writer thread holds ReentrantLock
per connection — scheduler runs inside the lock-holding write path.

## Key Constraints (most narrowing)
1. **Heartbeat starvation prevention** — correctness requirement, not just performance
2. **Single-threaded writer context** — scheduler must be O(1) per dequeue, no blocking
3. **Pure Java NIO** — no kernel-level QoS, no io_uring, no external frameworks

## Unknown / Not Specified
None — full profile captured. Derived from parent ADR constraints (transport-abstraction-design,
connection-pooling) and project-wide constraints (no external deps, Java 25).

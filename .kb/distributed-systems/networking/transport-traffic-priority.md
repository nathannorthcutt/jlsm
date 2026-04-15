---
title: "Transport Traffic Priority and QoS"
aliases: ["traffic priority", "QoS", "weighted fair queuing", "flow control"]
topic: "distributed-systems"
category: "networking"
tags: ["qos", "priority", "scheduling", "flow-control", "weighted-fair-queuing", "head-of-line-blocking"]
complexity:
  time_build: "N/A"
  time_query: "O(log P) per dequeue where P = priority levels"
  space: "O(queued messages)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/networking/multiplexed-transport-framing.md"
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
decision_refs: ["transport-traffic-priority"]
sources:
  - url: "https://hpbn.co/http2/"
    title: "HTTP/2 - High Performance Browser Networking (O'Reilly)"
    accessed: "2026-04-13"
    type: "book"
  - url: "https://intronetworks.cs.luc.edu/current/html/fairqueuing.html"
    title: "Queuing and Scheduling — An Introduction to Computer Networks"
    accessed: "2026-04-13"
    type: "textbook"
  - url: "https://en.wikipedia.org/wiki/Weighted_fair_queueing"
    title: "Weighted Fair Queueing — Wikipedia"
    accessed: "2026-04-13"
    type: "reference"
  - url: "https://httpwg.org/specs/rfc7540.html"
    title: "RFC 7540 — Hypertext Transfer Protocol Version 2 (HTTP/2)"
    accessed: "2026-04-13"
    type: "spec"
  - url: "https://arxiv.org/html/2512.04859v1"
    title: "io_uring for High-Performance DBMSs: When and How to Use It"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.cs.cit.tum.de/fileadmin/w00cfj/dis/papers/damon25_wake_up_call.pdf"
    title: "A Wake-Up Call for Kernel-Bypass on Modern Hardware (DAMON 2025)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://fardatalab.org/vldb24-zhang.pdf"
    title: "DDS: DPU-optimized Disaggregated Storage (VLDB 2024)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3689031.3696083"
    title: "Pegasus: Transparent and Unified Kernel-Bypass Networking (EuroSys 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

# Transport Traffic Priority and QoS

## summary

On a single multiplexed TCP connection, different traffic types compete for
send bandwidth. Without scheduling, a large bulk transfer starves heartbeats
and blocks latency-sensitive queries. The solution combines three mechanisms:
(1) message chunking to break large payloads into interleave-able frames,
(2) a weighted send-side scheduler that dequeues from priority classes in
proportion to assigned weights, and (3) per-stream flow control windows to
apply backpressure without blocking the entire connection. Deficit round robin
(DRR) is the recommended scheduler — O(1) per dequeue, simple to implement,
and avoids the virtual-clock bookkeeping of full WFQ.

## how-it-works

### traffic classes

Assign each message type to a priority class with a weight reflecting its
share of send bandwidth:

| Class | Traffic Types | Weight | Rationale |
|-------|--------------|--------|-----------|
| **CONTROL** | Heartbeats, membership, NACK | 3 | Must never be starved; small, frequent |
| **METADATA** | Catalog updates, schema changes | 2 | Small but important for consistency |
| **INTERACTIVE** | Client query request/response | 4 | Latency-sensitive; user-facing |
| **STREAMING** | Data replication, compaction sync | 3 | Steady throughput, tolerates jitter |
| **BULK** | Snapshot transfer, bootstrap, backfill | 1 | Large, deferrable |

Weights are relative. With the above, INTERACTIVE gets 4/13 of bandwidth when
all classes are active. When BULK is idle, the other classes share in their
original ratios (3:2:4:3 = 12 parts).

### message chunking

Large messages must be split into frames to allow interleaving. Without
chunking, a 64 MiB bulk transfer blocks all traffic for the full write time.

**Chunk size**: 64 KiB default — amortizes the 11-byte frame header, limits
worst-case CONTROL wait to ~0.5 ms at 1 Gbps. HTTP/2 defaults to 16 KiB.

**Frame format extension**: add `MORE_FRAMES` flag (bit 0) to the flags byte
from [multiplexed-transport-framing.md](multiplexed-transport-framing.md).
Receiver buffers frames per stream ID until a frame without the flag arrives,
then reassembles.

### send-side scheduler: deficit round robin

DRR approximates weighted fair queuing with O(1) per-dequeue cost. Each
priority class maintains a FIFO queue and two counters:

- **Quantum**: bytes granted per round, proportional to weight
- **Deficit**: accumulated unused bytes from prior rounds

**Algorithm per send cycle**:

```
for each class in round-robin order:
    deficit += quantum
    while queue is non-empty AND head frame size <= deficit:
        dequeue frame, write to channel
        deficit -= frame size
    if queue is empty:
        deficit = 0          // no credit banking when idle
```

With a base quantum of 16 KiB and the weights above, CONTROL gets 48 KiB per
round, INTERACTIVE gets 64 KiB, BULK gets 16 KiB. A heartbeat (~100 bytes)
drains immediately; a bulk chunk (64 KiB) must wait for the class's full
quantum accumulation.

**Starvation prevention**: DRR inherently prevents starvation — every class
gets at least one quantum per round regardless of other classes' load. For
CONTROL traffic, additionally enforce a **strict-priority bypass**: if the
CONTROL queue is non-empty, always drain it first before entering the DRR
round. This is safe because heartbeats are small and rate-limited (typically
1-5 per second per peer), so they cannot starve other classes.

### per-stream flow control

Modeled after HTTP/2 (RFC 7540 Section 6.9). Each stream and the connection
maintain a credit window in bytes. Sender decrements before sending DATA
frames; blocks per-stream (not connection-wide) when window hits zero.
Receiver sends WINDOW_UPDATE to grant credit, applying backpressure without
blocking unrelated streams. Initial window: 64 KiB default, configurable per
class (larger for INTERACTIVE, smaller for BULK). Control frames are exempt
from flow control — they must always flow to prevent deadlock.

### head-of-line blocking mitigation

TCP-level HOL blocking (packet loss blocks all streams) cannot be solved at
the application layer. Application-level mitigations: (1) chunking yields the
write lock between frames, (2) CONTROL bypass skips the DRR round, (3) small
BULK windows limit in-flight data, (4) `TCP_NODELAY` on CONTROL/INTERACTIVE
minimizes latency for small messages. If TCP HOL blocking is unacceptable,
use multiple TCP connections per peer (Cassandra's approach: URGENT, SMALL,
LARGE pools) or QUIC.

## implementation-notes

### java NIO integration

The send-side scheduler runs on the NIO selector thread (or a dedicated
write thread per connection). The write loop:

1. Poll DRR across all priority queues.
2. Write frames to `SocketChannel` via `GatheringByteChannel.write()` to
   batch header + body into a single syscall.
3. If the channel's send buffer is full (`write()` returns 0), register
   `OP_WRITE` interest and yield. Resume DRR from the current position on
   next `OP_WRITE` notification.

Enqueue path (producer threads) is lock-free: each priority queue is a
`ConcurrentLinkedQueue<Frame>`. The DRR loop is single-threaded (the
selector/writer thread), so deficit counters need no synchronization.

### alternative: deadline-based scheduling

For tighter latency guarantees, earliest-deadline-first (EDF) can replace DRR
for the INTERACTIVE class. Each query frame carries a deadline (wall-clock +
SLA budget); the scheduler picks the earliest. O(log N) per dequeue. Hybrid:
strict bypass for CONTROL, EDF for INTERACTIVE, DRR for the rest.

## trade-offs

| Approach | Pros | Cons |
|----------|------|------|
| Strict priority only | Simple | Starves BULK under sustained load |
| DRR + priority bypass | O(1), starvation-free | No per-frame deadline guarantees |
| Full WFQ | Provable fairness bounds | O(log N), virtual clock complexity |
| EDF (deadline-based) | Tight latency SLAs | O(log N), deadline propagation overhead |
| Multiple TCP connections | No app-level scheduling needed | FD waste, connection lifecycle complexity |

**Existing systems**: Cassandra uses 3 connection pools per peer (URGENT,
SMALL, LARGE). ScyllaDB uses per-core scheduling groups at the CPU/IO level.
HDFS uses FairCallQueue with weighted round-robin. HTTP/2 defines stream
priority trees (weight 1-256, dependency DAG) but most servers simplify.

**Recommendation for jlsm**: DRR with strict-priority bypass for CONTROL and
per-stream flow control windows. Implementable in pure Java NIO, no OS-level
QoS required, covers priority requirements without WFQ or HTTP/2 tree complexity.

## key-parameters

| Parameter | Default | Range |
|-----------|---------|-------|
| Max frame size (chunk size) | 64 KiB | 16 KiB – 1 MiB |
| DRR base quantum (weight=1) | 16 KiB | 4 KiB – 256 KiB |
| Initial per-stream flow window | 64 KiB | 16 KiB – 1 MiB |
| Connection-level flow window | 1 MiB | 256 KiB – 16 MiB |

## jlsm-applicability

Layers on top of the wire format from
[multiplexed-transport-framing.md](multiplexed-transport-framing.md).
Integration: frame `flags` byte carries `MORE_FRAMES` for chunking, `type`
field classifies into traffic classes at enqueue time, DRR runs on the
existing writer thread, and WINDOW_UPDATE is a new control frame type.
Heartbeats map to CONTROL, replication to STREAMING, queries to INTERACTIVE.

## Updates 2026-04-13

### io_uring for zero-copy I/O

The VLDB 2026 paper "io_uring for High-Performance DBMSs: When and How to Use
It" (arXiv 2512.04859) shows that naive io_uring adoption yields modest gains,
but designs that fully exploit submission batching and registered buffers more
than double throughput vs. traditional `read`/`write` syscalls. Java 25 does not
expose io_uring through NIO — tracking JEP progress is worthwhile but not
actionable yet. The DAMON 2025 paper "A Wake-Up Call for Kernel-Bypass on Modern
Hardware" (TU Darmstadt) measured kernel-based interfaces (including io_uring) at
~6x the cycle budget per message compared to true kernel-bypass (DPDK/RDMA at
~40 cycles/message).

### RDMA-based transports for consensus

DDS (VLDB 2024, U. Toronto) demonstrates DPU-optimized disaggregated storage
where RDMA eliminates OS overhead for remote memory access but still dedicates
CPU cores for polling completions. For consensus-critical messages (heartbeats,
vote requests), one-sided RDMA writes to pre-registered buffers can bypass the
kernel entirely, delivering single-digit microsecond latency — relevant for
CONTROL-class traffic that must never be delayed by send-side scheduling.

### Programmable network switches for hardware QoS

P4-programmable SmartNICs now implement PIFO (Push-In-First-Out) scheduling in
FPGA fabric at line rate, enforcing Hierarchical Class of Service without CPU
involvement. Pegasus (EuroSys 2025, Purdue) provides transparent kernel-bypass
networking that unifies local and remote communication paths. For jlsm, hardware
QoS is not a near-term dependency, but the DRR scheduler design should remain
composable so that hardware-offloaded priority classes can replace software
scheduling when available.

### Adaptive priority scheduling

Recent stream processing research (arXiv 2504.12074, 2025) applies adaptive
parallelism tuning that adjusts scheduling weights based on observed workload
characteristics — analogous to dynamically reweighting DRR quanta when traffic
class ratios shift. The STREAMLINE framework (ScienceDirect, 2025) demonstrates
auto-tuning of pipeline resource allocation based on backpressure signals. For
jlsm, a feedback loop that monitors per-class queue depths and adjusts DRR
weights at configurable intervals (e.g., every 100 ms) would adapt to bursty
replication or query-heavy workloads without manual tuning.

---
problem: "transport-traffic-priority"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Transport Traffic Priority

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Transport Traffic Priority and QoS | Chosen approach — DRR algorithm, traffic classes, NIO integration | [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md) |
| Multiplexed Transport Framing | Foundation — framing protocol this builds on | [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [transport-abstraction-design](../transport-abstraction-design/adr.md) | Parent — defines `ClusterTransport` SPI |
| [connection-pooling](../connection-pooling/adr.md) | Foundation — single-connection multiplexing with Kafka-style framing |
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Consumer — heartbeats map to CONTROL class |
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Consumer — query scatter maps to INTERACTIVE class |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — send-side scheduler in NIO transport

## Problem
On the single multiplexed TCP connection per peer (connection-pooling ADR), different
traffic types compete for send bandwidth. Without scheduling, a large bulk transfer or
burst of query responses can starve membership heartbeats — a correctness issue since
phi accrual failure detection depends on timely heartbeat delivery.

## Constraints That Drove This Decision
- **Heartbeat starvation prevention**: phi accrual failure detector depends on timely heartbeat delivery — this is a correctness requirement, not just performance
- **Single-threaded writer context**: scheduler runs on the NIO writer thread, must be O(1) per dequeue with negligible latency overhead
- **Work-conserving**: when a traffic class is idle, its bandwidth share must redistribute to active classes — no wasted link capacity

## Decision
**Chosen approach: [DRR + Strict-Priority Bypass](../../.kb/distributed-systems/networking/transport-traffic-priority.md)**

Deficit round robin with strict-priority bypass for CONTROL traffic. Five traffic classes
(CONTROL, METADATA, INTERACTIVE, STREAMING, BULK) with configurable weights. Heartbeats
and membership messages bypass the DRR round entirely — always drained first. All other
classes share bandwidth proportionally via DRR, which is O(1) per dequeue, inherently
starvation-free, and work-conserving (idle class bandwidth redistributes automatically).
Large messages are chunked into 64 KiB frames (MORE_FRAMES flag in the framing protocol)
to allow interleaving.

### Traffic Classes

| Class | Traffic Types | Weight | Rationale |
|-------|--------------|--------|-----------|
| CONTROL | Heartbeats, membership, NACK | 3 | Must never be starved; strict bypass |
| METADATA | Catalog updates, schema changes | 2 | Small but important for consistency |
| INTERACTIVE | Client query request/response | 4 | Latency-sensitive; user-facing |
| STREAMING | Data replication, compaction sync | 3 | Steady throughput, tolerates jitter |
| BULK | Snapshot transfer, bootstrap, backfill | 1 | Large, deferrable |

Weights are relative. Message types map to traffic classes at enqueue time via the
type byte in the framing protocol.

## Rationale

### Why [DRR + Strict-Priority Bypass](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
- **Accuracy**: strict bypass guarantees zero scheduling delay for heartbeats; DRR guarantees no class is starved ([KB: `#send-side-scheduler`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#send-side-scheduler))
- **Performance**: O(1) per dequeue — iterates over 5 fixed classes, negligible overhead on writer thread ([KB: `#send-side-scheduler`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#send-side-scheduler))
- **Work-conserving**: idle class deficit resets to zero, bandwidth redistributes to active classes automatically — handles heterogeneous node roles (query-only, ingest-heavy) without configuration changes
- **Proven pattern**: HDFS FairCallQueue uses the same DRR approach ([KB: `#trade-offs`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#trade-offs))

### Why not Strict Priority Only
- **Starves BULK**: under sustained INTERACTIVE + STREAMING load, BULK traffic never gets bandwidth. Violates the requirement that all traffic classes receive a minimum share. ([KB: `#trade-offs`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#trade-offs))

### Why not [Full WFQ](../../.kb/distributed-systems/networking/transport-traffic-priority.md#trade-offs)
- **Accuracy gap on CONTROL**: heartbeats participate in virtual clock ordering — bounded but nonzero delay, vs DRR's strict bypass which is unconditional zero delay. The binding accuracy constraint favors bypass over provable fairness bounds.
- **O(log N) per dequeue**: unnecessary overhead for 5 priority classes where DRR provides equivalent starvation prevention at O(1)

### Why not [EDF (Deadline-Based)](../../.kb/distributed-systems/networking/transport-traffic-priority.md#alternative)
- **Deadline propagation**: queries would need to carry deadline metadata end-to-end — cross-cutting concern that touches scatter-gather, handler dispatch, and transport layers
- **Awkward fit for heartbeats**: heartbeats have implicit deadlines (protocol period) but no natural per-frame deadline, making EDF's core mechanism inapplicable to the highest-priority traffic

## Implementation Guidance
Key parameters from [`transport-traffic-priority.md#key-parameters`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#key-parameters):
- Max frame size (chunk size): 64 KiB default (limits worst-case CONTROL wait to ~0.5ms at 1 Gbps)
- DRR base quantum (weight=1): 16 KiB
- Frame format: MORE_FRAMES flag (bit 0) in flags byte from connection-pooling framing protocol

NIO integration from [`transport-traffic-priority.md#implementation-notes`](../../.kb/distributed-systems/networking/transport-traffic-priority.md#implementation-notes):
- DRR loop runs on the NIO writer thread (single-threaded, no synchronization for deficit counters)
- Enqueue path is lock-free: ConcurrentLinkedQueue per priority class
- GatheringByteChannel.write() for batched header + body syscall
- OP_WRITE registration when channel send buffer is full; resume DRR from current position

Known edge cases:
- CONTROL bypass safety: heartbeats are rate-limited (~1-5/s/peer, ~100 bytes each); membership protocol bursts during mass rejoin are transient and bounded
- Heterogeneous nodes: work-conserving property handles query-only vs ingest-heavy nodes without per-node weight profiles (idle classes yield bandwidth automatically)

## What This Decision Does NOT Solve
- Per-stream flow control and backpressure (scatter-backpressure — separate decision in this WD)
- Adaptive weight tuning based on observed traffic patterns (future enhancement if static weights prove insufficient)
- Large payload transfer across connections (bulk-data-transfer-channel — deferred)

## Conditions for Revision
This ADR should be re-evaluated if:
- INTERACTIVE latency p99 under sustained mixed load exceeds acceptable bounds (upgrade path: hybrid DRR + EDF for INTERACTIVE class)
- Single TCP connection per peer is revisited (multiple connections would eliminate the need for application-level scheduling)
- Traffic class count grows beyond ~10 (DRR round cost grows linearly with class count)
- Adaptive weight tuning based on queue depth monitoring proves necessary in production

---
*Confirmed by: user deliberation (2 rounds — complexity reweight challenge, heterogeneous roles probe) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*

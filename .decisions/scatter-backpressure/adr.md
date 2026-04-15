---
problem: "scatter-backpressure"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Scatter-Gather Backpressure

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Scatter-Gather Backpressure Strategies | Chosen approach — credit model + Flow API integration | [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md) |
| Distributed Scan Cursor Management | Informed deliberation — continuation tokens align with credit model | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [transport-abstraction-design](../transport-abstraction-design/adr.md) | Parent — defines `ClusterTransport` SPI |
| [connection-pooling](../connection-pooling/adr.md) | Foundation — multiplexed framing with stream IDs |
| [transport-traffic-priority](../transport-traffic-priority/adr.md) | Sibling — DRR classifies query traffic as INTERACTIVE |
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Consumer — proxy table's k-way merge consumes paged responses |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — scatter-gather proxy, partition page handler

## Problem
The scatter-gather proxy fans out queries to partition owners and merges results via a
streaming k-way merge iterator. Without flow control, fast partitions can overwhelm the
coordinator with buffered responses, causing OOM. The coordinator needs a backpressure
mechanism that bounds memory consumption within the ArenaBufferPool budget while maintaining
low query latency on the multiplexed single-connection transport.

## Constraints That Drove This Decision
- **Hard memory cap via ArenaBufferPool**: memory budget per query must be deterministic — no unbounded buffering
- **NIO compatibility**: must work with CompletableFuture-based transport and multiplexed framing — blocking primitives conflict with the event-driven transport
- **Per-stream isolation**: one query's backpressure must not stall unrelated queries on the same connection

## Decision
**Chosen approach: Credit-Based + [Flow API](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md) (composite)**

Credit-based memory budgeting combined with JDK `java.util.concurrent.Flow` API for
non-blocking demand signaling. Credits map to ArenaBufferPool slabs — each credit
represents one page buffer acquired from the pool. The coordinator issues credits via
`Subscription.request(N)` at query start; partitions send one page per credit. The merge
operator's `onNext()` consumes a batch, releases the pool slab, and re-issues demand —
a self-regulating loop bounded by physical memory.

On the wire, demand signals travel as standard `request()` messages carrying a
continuation token (see cursor management research). Each page request is self-contained:
the partition seeks from the token, scans one page, returns results + new token. No
server-side state between requests.

### Credit Budget Formula

```
credits_per_partition = pool_capacity / (page_buffer_size × partition_count × concurrent_queries)
```

With ArenaBufferPool at 256 MB, 64 KiB page buffers, 50 partitions, and 4 concurrent
queries: `256 MB / (64 KiB × 50 × 4) = 20 credits per partition`. Each partition can
have up to 20 pages in flight before the coordinator applies backpressure.

## Rationale

### Why Credit-Based + Flow API
- **Hard memory cap**: credits = pool slabs, deterministic bound, no overflow possible ([KB: `#credit-based-flow-control`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md))
- **NIO compatible**: `Flow.Subscription.request(N)` is non-blocking demand signaling — no thread blocking during backpressure ([KB: `#reactive-streams`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md))
- **Backpressure-aligned with stateless cursors**: demand-driven paging maps directly to continuation token exchange — partition resource usage is proportional to active demand, not open scan count ([KB: `#backpressure-integration`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md))
- **Standard JDK API**: `java.util.concurrent.Flow` is in `java.base` — no external dependency

### Why not [Flow API alone](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
- **No inherent memory bound**: `Subscription.request(N)` controls demand but the subscriber must implement its own memory cap. The credit layer provides this — without it, a subscriber that issues unbounded demand can OOM.

### Why not [Pull-Based Streaming](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
- **RTT-limited throughput**: one outstanding request per partition means throughput = `page_size / RTT`. At 100 partitions with 1ms RTT, pipelining helps but adds complexity equivalent to the credit model without the memory guarantee.

### Why not [Credit-Based standalone](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
- **Blocking**: `Semaphore.acquire()` blocks the calling thread — incompatible with the NIO event loop. Virtual threads absorb this but add indirection the Flow API avoids.

### Why not [Push-Based with Bounded Buffers](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
- **TCP backpressure stalls all streams**: on a multiplexed connection, TCP receive window closing blocks ALL streams, not just the slow query. Violates per-stream isolation.

## Implementation Guidance

Key parameters from [`scatter-gather-backpressure.md#key-takeaways`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md):
- Credit count per partition: `pool_capacity / (page_buffer_size × N × concurrent_queries)`
- Page buffer size: 64 KiB default (matches ArenaBufferPool slab size)
- Demand signal: `Subscription.request(1)` per page, carrying continuation token

Continuation token integration from [`distributed-scan-cursors.md#demand-signal-protocol`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md):
- Each `request(1)` sends a `PageRequest(fromKey, toKey, sequenceNumber, pageSize)`
- Partition responds with `PageResponse(entries, continuationToken, snapshotDegraded)`
- Token encodes `[sequenceNumber, lastKey, flags]` — 21–141 bytes, fits in one frame

Known edge cases:
- `ArenaBufferPool.acquire()` is blocking with timeout — run on virtual threads, not NIO selector thread
- Variable batch sizes: point lookups waste slab capacity; implementation should allow on-heap processing for responses under a configurable threshold (e.g., < 4 KiB)
- Flow API `onNext()` must not block — `pool.release()` is `queue.offer()` which is non-blocking

Slow partition handling from [`scatter-gather-backpressure.md#slow-partition-handling`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md):
- Per-partition timeout + partial results (already decided in scatter-gather-query-execution ADR)
- Adaptive timeout: deadline = f(p99 of completed partitions) — future enhancement

## What This Decision Does NOT Solve
- Hierarchical memory budget across concurrent queries (Presto-style MemoryContext for query-level and cluster-level budgets)
- Snapshot binding strategy for continuation tokens (sequence-number vs best-effort — orthogonal to flow control)
- Scan lease mechanism to prevent GC watermark from trimming version history needed by active scans

## Conditions for Revision
This ADR should be re-evaluated if:
- ArenaBufferPool moves to byte-level allocation (Presto-style MemoryContext would then score higher on Fit)
- Variable response sizes cause >50% slab waste in production (may need variable-size buffer pooling)
- Single-connection multiplexing is replaced by multiple connections per peer (per-stream isolation constraint relaxes)
- Concurrent query count exceeds credit budget capacity (may need hierarchical budgeting)

---
*Confirmed by: user deliberation (cursor management research commissioned and incorporated) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*

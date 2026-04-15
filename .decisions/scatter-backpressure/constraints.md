---
problem: "Scatter-gather backpressure — how to prevent coordinator OOM when merging responses from multiple partitions"
slug: "scatter-backpressure"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — scatter-backpressure

## Problem Statement
The scatter-gather proxy (decided in scatter-gather-query-execution ADR) fans out queries
to partition owners and merges results via a streaming k-way merge iterator. Without flow
control, fast partitions can overwhelm the coordinator with buffered responses, causing OOM.
The coordinator needs a backpressure mechanism that bounds memory consumption while
maintaining low query latency, working within the ArenaBufferPool budget and the
multiplexed single-connection transport.

## Constraints

### Scale
Queries fan out to up to ~100 partitions per query. Multiple concurrent queries may be
active simultaneously. Each partition response can range from a few KB (point lookups)
to tens of MB (full scans). Total in-flight response data across concurrent queries
must fit within the ArenaBufferPool allocation.

### Resources
ArenaBufferPool provides bounded off-heap memory via Arena.ofShared(). All response
buffers must come from the pool — no heap allocation for response data in hot paths.
Pure Java NIO — no external dependencies. Transport is single TCP connection per peer
with multiplexed stream IDs.

### Complexity Budget
Not a constraint — project run by agent experts.

### Accuracy / Correctness
Must prevent coordinator OOM with a hard memory cap — not a soft guideline. Memory
budget per query must be deterministic: `budget = f(pool_capacity, concurrent_queries)`.
Partial results are acceptable for timed-out partitions (scatter-gather ADR already
handles this via PartialResultMetadata). Data must not be lost or silently dropped
during backpressure — either deliver it or mark the partition as timed out.

### Operational Requirements
Query latency p99 < 50ms target. Backpressure mechanism must not add more than
single-digit ms overhead per partition response. Must be observable: per-query
buffer utilization, credits outstanding, partition response rates for debugging
slow queries.

### Fit
Builds on:
- ClusterTransport `request()` returning `CompletableFuture<Message>`
- ArenaBufferPool for MemorySegment-backed response buffers
- Multiplexed framing with stream IDs and flags byte (connection-pooling ADR)
- DRR scheduler classifying query traffic as INTERACTIVE (transport-traffic-priority ADR)
- K-way merge iterator consuming partition responses in key order (scatter-gather ADR)

## Key Constraints (most narrowing)
1. **Hard memory cap via ArenaBufferPool** — no unbounded buffering, deterministic budget
2. **NIO compatibility** — must work with CompletableFuture-based transport, not blocking primitives
3. **Per-stream isolation** — one query's backpressure must not stall unrelated queries on the same connection

## Unknown / Not Specified
None — full profile captured from parent ADRs and project constraints.

---
title: "Scatter-Gather Backpressure Strategies"
aliases: ["backpressure", "flow control", "scatter-gather", "distributed query flow"]
topic: "distributed-systems"
category: "networking"
tags: ["backpressure", "scatter-gather", "flow-control", "credit-based", "streaming", "memory-budget"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per flow control decision"
  space: "O(buffer size x partitions)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/networking/multiplexed-transport-framing.md"
decision_refs: ["scatter-backpressure"]
sources:
  - url: "https://prestodb.io/blog/2019/08/19/memory-tracking/"
    title: "Memory Tracking in Presto"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://oneflow2020.medium.com/the-development-of-credit-based-flow-control-part-2-f04b76010a16"
    title: "The Development of Credit-based Flow Control (Part 2)"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://www.alibabacloud.com/blog/analysis-of-network-flow-control-and-back-pressure-flink-advanced-tutorials_596632"
    title: "Analysis of Network Flow Control and Back Pressure: Flink Advanced Tutorials"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://medium.com/@jayphelps/backpressure-explained-the-flow-of-data-through-software-2350b3e77ce7"
    title: "Backpressure explained -- the resisted flow of data through software"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://arxiv.org/html/2412.13314v1"
    title: "Distributed Speculative Execution for Resilient Cloud Applications (2024)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://arxiv.org/html/2401.04494v2"
    title: "Adaptive Asynchronous Work-Stealing for Distributed Load-Balancing (2024)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3722212.3724460"
    title: "Unlocking the Potential of CXL for Disaggregated Memory in Cloud-Native Databases (SIGMOD 2025)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p3119-weisgut.pdf"
    title: "CXL Memory Performance for In-Memory Data Processing (VLDB 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

# Scatter-Gather Backpressure Strategies

## Problem Statement

A coordinator fans out a query to N partitions and merges results. Without flow
control: (1) coordinator OOM from buffering too many responses, (2) slow
partitions block fast ones, (3) network congestion from simultaneous replies.

## Strategy Taxonomy

### 1. Credit-Based Flow Control

Coordinator issues credits (permits) per partition. A partition sends one batch
per credit. Coordinator returns credits as it consumes batches.

- Memory bound: `credits_per_partition x batch_size x N` -- hard cap
- No overflow possible; adapts naturally to slow consumers
- Used by Apache Flink between TaskManagers (one credit = one network buffer)

```java
record PartitionChannel(int partitionId, Semaphore credits) {}
// Coordinator: on batch received, mergeSink.accept(batch); ch.credits().release();
// Partition: ch.credits().acquire(); transport.send(batch);
```

### 2. Pull-Based Streaming (Iterator Model)

Coordinator pulls via request-response. Partitions hold server-side cursors and
send the next page only on request.

- Simplest model: one outstanding request per partition
- Throughput limited by RTT unless pipelined (issue fetch N+1 while processing N)
- Used by CockroachDB DistSender, Cassandra coordinator (paging with `fetchSize`)

```java
CompletableFuture<Page> pending = fetchPage(partition, token);
while (true) {
    Page current = pending.join();
    if (current.hasMore()) pending = fetchPage(partition, current.nextToken());
    mergeSink.accept(current.rows());
    if (!current.hasMore()) break;
}
```

### 3. Push-Based with Bounded Buffers

Partitions push continuously. Coordinator bounds receive buffers; when full,
TCP receive window closes or an explicit pause signal fires.

- Highest throughput when partitions are roughly equal speed
- Head-of-line blocking risk if one partition's queue fills while others empty
- TCP-level backpressure is coarse-grained with latency spikes on window reopen
- Used by Presto/Trino exchange operators between workers

```java
var buffer = new ArrayBlockingQueue<ResponseBatch>(CAPACITY); // per partition
// Merge: PriorityQueue over PeekableIterators that block on queue.take()
```

### 4. Reactive Streams (`java.util.concurrent.Flow`)

JDK `Flow` API: `Publisher`/`Subscriber`/`Subscription` with async demand.

- Standard API in `java.base` -- no library dependency
- Non-blocking demand signaling; suited for NIO event loops
- On the wire, demand signals map to fetch requests or credit returns

```java
class MergeSubscriber implements Flow.Subscriber<ResponseBatch> {
    private Flow.Subscription sub;
    @Override public void onSubscribe(Flow.Subscription s) {
        this.sub = s; s.request(PREFETCH);
    }
    @Override public void onNext(ResponseBatch batch) {
        mergeSink.accept(batch); sub.request(1); // replenish
    }
}
```

## Slow Partition Handling

| Technique | Mechanism | Trade-off |
|-----------|-----------|-----------|
| **Timeout + partial** | Per-partition deadline; return available rows | Incomplete results |
| **Speculative exec** | Send query to replica partition | Doubles network; must cancel dup |
| **Adaptive timeout** | Deadline = f(p99 of completed partitions) | Latency tracking overhead |
| **Skip + backfill** | Merge fast partitions first; backfill later | Complex ordering |

For range-partitioned LSM stores, **timeout + partial results** is the pragmatic
default. The coordinator tracks incomplete key ranges and the caller decides
whether to retry.

## Memory Budgeting

**Per-query budget:** Fixed ceiling divided across partitions:
`per_partition_buffer = query_budget / partition_count`. Presto uses hierarchical
`MemoryContext` tracking (operator -> query -> pool). Exceeding limits triggers
cooperative blocking or query kill.

**Shared pool with reservation:** Cluster-wide pool with a guaranteed minimum
per query and a shared burst region. When exhausted, the largest consumer is
killed to guarantee forward progress (Presto's reserved pool pattern).

For jlsm, `ArenaBufferPool` already provides bounded off-heap allocation. A
scatter-gather coordinator wraps partition buffers in pool-backed
`MemorySegment` slabs with credit count = `pool_capacity / (batch_size * N)`.

## Strategy Selection

| Factor | Credit-Based | Pull-Based | Push+Bounded | Flow API |
|--------|-------------|------------|--------------|----------|
| Memory guarantee | Hard cap | Hard cap | Soft (TCP) | Hard cap |
| Throughput | High | RTT-limited | Highest | High |
| Complexity | Medium | Low | Medium | Medium |
| NIO compatible | No (blocking) | With async | No (blocking) | Yes |
| Standard API | Custom | Custom | Custom | `java.base` |

**Recommendation for jlsm:** Credit-based control with `Flow` API signaling.
Hard memory bound, works with `ArenaBufferPool`, NIO-compatible for the cluster
transport layer (see [multiplexed-transport-framing.md](multiplexed-transport-framing.md)).
Credit budget ties directly to pool slab count.

## Transport Layer Relationship

Backpressure operates above transport framing. On a multiplexed connection, per-
stream flow control is essential -- one query's backpressure must not stall
unrelated streams. This maps to per-stream credit tracking within the framing
protocol, analogous to HTTP/2 flow control windows.

## Key Takeaways

1. Credit-based is the strongest OOM guarantee: `credits x batch_size x N`
2. Pull-based is simplest; pipeline fetches to hide RTT
3. Push-based has peak throughput but coarse TCP backpressure on muxed connections
4. `Flow` API is the right abstraction for non-blocking NIO coordinators
5. Memory budgets must be hierarchical (operator/query/cluster) -- Presto is the
   reference architecture
6. Slow partition handling is orthogonal to flow control; timeout + partial
   results is the default for range-partitioned LSM stores

## Updates 2026-04-13

### Morsel-driven execution applied to distributed fan-out

Morsel-driven parallelism (Leis et al.) is now being applied beyond single-node
engines. Polars (2025, GitHub issue #26432) identified that morsel-based I/O
sources require explicit backpressure when downstream operators consume slower
than production rate — unbounded morsel buffering causes OOM. The fix mirrors
credit-based flow control: I/O sources check a permit/credit counter before
emitting the next morsel. For distributed scatter-gather, each partition
channel acts as a morsel source; the coordinator's merge operator is the
consumer. Credit count = `memory_budget / (morsel_size * partition_count)`.

### Adaptive timeout and speculative execution

Distributed speculative execution (DSE, arXiv 2412.13314, 2024) implements
durable execution without synchronous persistence penalties. For scatter-gather,
speculative re-dispatch to a replica partition when the primary exceeds p99
latency avoids straggler-induced tail latency. MapReduce-era speculative
execution suffered from late straggler detection; recent adaptive approaches
(arXiv 2504.12074, 2025) use real-time progress tracking to trigger speculation
earlier. For jlsm, adaptive timeouts derived from a rolling p99 of completed
partition responses — combined with cancellation of the slower duplicate — are
a practical straggler mitigation without doubling sustained network load.

### Memory disaggregation impact on buffer management

CXL-attached memory pools (SIGMOD 2025, "Unlocking the Potential of CXL for
Disaggregated Memory in Cloud-Native Databases"; VLDB 2025, "CXL Memory
Performance for In-Memory Data Processing") change buffer management
assumptions. With disaggregated memory, scatter-gather buffers can reside in a
shared CXL pool accessible by multiple compute nodes at ~150-300 ns latency
(vs. ~1-5 us for RDMA). This enables a coordinator to allocate partition
response buffers from a shared fabric rather than local heap, decoupling buffer
capacity from per-node memory. For jlsm's `ArenaBufferPool`, a future CXL
backend would replace the local `Arena.ofShared()` allocator while preserving
the credit-based flow control contract.

### Work-stealing for imbalanced partition responses

Adaptive asynchronous work-stealing (arXiv 2401.04494, 2024) addresses load
imbalance in heterogeneous distributed systems by collecting node performance
metadata to guide victim selection and task offloading. Applied to
scatter-gather: when partition response sizes are skewed (common with range
partitions), the merge operator can use work-stealing to redistribute
deserialization and merge work across available threads. Fast partitions that
finish early donate their merge thread to help process buffered responses from
slow partitions, improving tail latency without over-provisioning thread pools.

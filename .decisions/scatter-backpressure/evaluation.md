---
problem: "scatter-backpressure"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/networking/scatter-gather-backpressure.md"
    name: "Credit-Based Flow Control"
    section: "#credit-based-flow-control"
  - path: ".kb/distributed-systems/networking/scatter-gather-backpressure.md"
    name: "Pull-Based Streaming (Iterator Model)"
    section: "#pull-based-streaming"
  - path: ".kb/distributed-systems/networking/scatter-gather-backpressure.md"
    name: "Push-Based with Bounded Buffers"
    section: "#push-based-with-bounded-buffers"
  - path: ".kb/distributed-systems/networking/scatter-gather-backpressure.md"
    name: "Reactive Streams (Flow API)"
    section: "#reactive-streams"
  - paths:
      - ".kb/distributed-systems/networking/scatter-gather-backpressure.md"
    name: "Credit-Based + Flow API (composite)"
    boundary: "Credit budget for memory control, Flow API for NIO-compatible demand signaling"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — scatter-backpressure

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)

## Constraint Summary
The coordinator must bound memory consumption with a hard cap derived from ArenaBufferPool
capacity, work non-blockingly with the CompletableFuture-based transport, and isolate
per-query backpressure on the multiplexed connection. Accuracy (OOM prevention) and
resources (pool-bounded) are the binding constraints. Fit is weighted equally high because
the backpressure mechanism must compose with 4 existing ADR decisions.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | ~100 partitions is moderate; strategy choice matters more than scale properties |
| Resources | 3 | ArenaBufferPool hard bound is the primary design constraint |
| Complexity | 1 | Not a concern per user feedback |
| Accuracy | 3 | OOM prevention is correctness, not performance — must be guaranteed |
| Operational | 2 | Latency overhead matters but is secondary to correctness |
| Fit | 3 | Must compose with 4 existing ADRs (transport, connection-pooling, scatter-gather, DRR) |

---

## Candidate: Credit-Based Flow Control

**KB source:** [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
**Relevant sections read:** `#credit-based-flow-control`, `#memory-budgeting`, `#strategy-selection`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Credits scale linearly with partition count; budget = credits × batch_size × N (#memory-budgeting) |
|       |   |   |    | **Would be a 2 if:** credit management overhead exceeded O(1) per partition (it doesn't — one Semaphore per channel) |
| Resources | 3 | 5 | 15 | Hard memory cap: `credits_per_partition x batch_size x N` directly maps to ArenaBufferPool slab count (#memory-budgeting) |
|           |   |   |    | **Would be a 2 if:** pool fragmentation caused effective capacity to be much less than theoretical (mitigated by fixed-size slabs) |
| Complexity | 1 | 4 | 4 | Medium complexity per KB (#strategy-selection); Semaphore per partition + credit return on consume |
| Accuracy | 3 | 5 | 15 | "No overflow possible; adapts naturally to slow consumers" (#credit-based-flow-control). Deterministic bound. |
|          |   |   |    | **Would be a 2 if:** credit accounting had race conditions that allowed over-issuance (mitigated by Semaphore atomicity) |
| Operational | 2 | 4 | 8 | Low overhead per batch; Semaphore acquire/release is sub-microsecond |
|             |   |   |   | **Would be a 2 if:** Semaphore.acquire() blocked the NIO event loop (it does — see NIO compatibility below) |
| Fit | 3 | 2 | 6 | KB explicitly notes "NIO compatible: No (blocking)" (#strategy-selection). Semaphore.acquire() blocks the thread — incompatible with CompletableFuture-based transport unless wrapped in virtual threads |
| **Total** | | | **58** | |

**Hard disqualifiers:** NIO incompatibility is a serious fit concern, not a hard disqualifier — can be worked around with virtual threads, but adds a layer of indirection.

**Key strengths:**
- Strongest memory guarantee in the candidate set (#credit-based-flow-control)
- Direct mapping to ArenaBufferPool slab count (#memory-budgeting)

**Key weaknesses:**
- Blocking Semaphore conflicts with CompletableFuture-based NIO transport (#strategy-selection)

---

## Candidate: Pull-Based Streaming (Iterator Model)

**KB source:** [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
**Relevant sections read:** `#pull-based-streaming`, `#strategy-selection`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 3 | 6 | Throughput limited by RTT unless pipelined (#pull-based-streaming). At 100 partitions, RTT stacking is significant |
| Resources | 3 | 5 | 15 | Hard memory cap: one outstanding page per partition (#pull-based-streaming) |
|           |   |   |    | **Would be a 2 if:** pipelining (fetch N+1 while processing N) inflated memory to 2× pages per partition |
| Complexity | 1 | 5 | 5 | "Simplest model" per KB (#strategy-selection) |
| Accuracy | 3 | 5 | 15 | Hard cap: one page per partition in flight. Deterministic. No data loss — cursor holds position server-side |
|          |   |   |    | **Would be a 2 if:** server-side cursor state consumed unbounded resources on the partition node |
| Operational | 2 | 3 | 6 | RTT-limited without pipelining; pipelining adds ~1 RTT of latency per page at startup (#pull-based-streaming) |
| Fit | 3 | 4 | 12 | Maps to CompletableFuture request-response naturally. Pipelining: `fetchPage()` returns future, chain next fetch |
|     |   |   |    | **Would be a 2 if:** server-side cursor management conflicted with the stateless partition owner model |
| **Total** | | | **59** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Simplest model with hard memory cap
- Natural fit with request-response transport pattern

**Key weaknesses:**
- Throughput limited by RTT per partition; pipelining helps but adds server-side cursor state

---

## Candidate: Push-Based with Bounded Buffers

**KB source:** [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
**Relevant sections read:** `#push-based-with-bounded-buffers`, `#strategy-selection`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | "Highest throughput when partitions are roughly equal speed" (#push-based) |
|       |   |   |   | **Would be a 2 if:** partition response sizes were highly skewed (common with range partitions) |
| Resources | 3 | 3 | 9 | Soft memory bound — TCP receive window is the actual backpressure, which is coarse-grained (#push-based) |
| Complexity | 1 | 4 | 4 | ArrayBlockingQueue per partition + PriorityQueue merge |
| Accuracy | 3 | 2 | 6 | "TCP-level backpressure is coarse-grained with latency spikes on window reopen" (#push-based). Soft cap, not hard |
| Operational | 2 | 4 | 8 | Highest raw throughput; head-of-line blocking risk if one partition queue fills |
|             |   |   |   | **Would be a 2 if:** HOL blocking under skewed partitions caused latency spikes |
| Fit | 3 | 2 | 6 | "NIO compatible: No (blocking)" (#strategy-selection). BlockingQueue.take() blocks. On multiplexed connection, TCP backpressure stalls ALL streams, not just the slow query |
| **Total** | | | **41** | |

**Hard disqualifiers:** TCP-level backpressure on a multiplexed connection stalls all streams, violating the per-stream isolation constraint.

**Key strengths:**
- Highest throughput when partitions are balanced

**Key weaknesses:**
- TCP backpressure is connection-wide, not per-stream — violates isolation constraint
- Soft memory bound — cannot guarantee ArenaBufferPool budget compliance

---

## Candidate: Reactive Streams (Flow API)

**KB source:** [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md)
**Relevant sections read:** `#reactive-streams`, `#strategy-selection`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Async demand signaling scales to any partition count; non-blocking |
|       |   |   |    | **Would be a 2 if:** per-subscription overhead became significant at high concurrency |
| Resources | 3 | 4 | 12 | Hard cap via demand signaling (request(N) bounds in-flight); maps to pool slabs |
|           |   |   |    | **Would be a 2 if:** Flow API buffering semantics allowed demand > physical capacity |
| Complexity | 1 | 3 | 3 | Publisher/Subscriber/Subscription contracts have subtle threading rules |
| Accuracy | 3 | 4 | 12 | Demand-controlled — producer cannot emit without demand. But memory bound depends on subscriber implementation, not protocol |
|          |   |   |    | **Would be a 2 if:** subscriber issued more demand than pool capacity supported |
| Operational | 2 | 4 | 8 | Non-blocking demand signaling; no blocking threads during backpressure |
|             |   |   |   | **Would be a 2 if:** demand round-trips added measurable latency |
| Fit | 3 | 5 | 15 | "Standard API in java.base — no library dependency" (#reactive-streams). NIO-compatible. Maps to CompletableFuture and transport naturally |
|     |   |   |    | **Would be a 2 if:** Flow API's threading model conflicted with virtual thread usage in the transport |
| **Total** | | | **60** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Standard JDK API — no external dependency
- NIO-compatible, non-blocking demand signaling
- Best fit with CompletableFuture-based transport

**Key weaknesses:**
- Memory bound depends on subscriber implementation quality, not inherent in the protocol
- Flow API threading rules (Subscriber methods must not block) require careful implementation

---

## Composite Candidate: Credit-Based + Flow API

**Components:** Credit-Based memory budget + Flow API demand signaling
**Boundary rule:** Credits define the memory budget (hard cap via ArenaBufferPool slab count). Flow API `Subscription.request(N)` is the demand signal on the wire. Credit return happens in `onNext()` after the merge operator consumes a batch.

| Constraint | Weight | Component | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-----------|-------------|----------|-----------------|
| Scale | 2 | Flow API | 5 | 10 | Async demand signaling, non-blocking, partition count independent |
|       |   |          |   |    | **Would be a 2 if:** per-subscription demand tracking exceeded O(1) |
| Resources | 3 | Credit | 5 | 15 | Hard cap: `pool_capacity / (batch_size * N)` credits per partition (#memory-budgeting). Slabs from ArenaBufferPool |
|           |   |        |   |    | **Would be a 2 if:** credit↔slab mapping leaked (mitigated by acquire/release in try-finally) |
| Complexity | 1 | Both | 3 | 3 | Medium — credit tracking + Flow API contracts. More code than pull-based |
| Accuracy | 3 | Credit | 5 | 15 | Deterministic hard cap. Credits = pool slabs. No overflow possible |
|          |   |        |   |    | **Would be a 2 if:** credit accounting diverged from actual pool usage (mitigated by using pool acquire count directly as credit) |
| Operational | 2 | Flow API | 5 | 10 | Non-blocking demand signaling; credit return in onNext() is O(1) |
|             |   |          |   |    | **Would be a 2 if:** demand round-trip added RTT to each batch (no — demand is piggy-backed on next request or sent as control frame) |
| Fit | 3 | Both | 5 | 15 | Flow API is java.base; credits map to ArenaBufferPool slabs; demand signals map to transport stream control frames |
|     |   |      |   |    | **Would be a 2 if:** Flow API's Subscriber threading rules conflicted with the NIO writer thread model (they don't — onNext runs on the reader virtual thread) |
| **Total** | | | | **68** | |

**Integration cost:** Map `Subscription.request(N)` to credit issuance. `onNext()` calls `pool.release()` + re-issues demand. Wire protocol: demand piggybacked on next request frame or sent as a lightweight control frame via the flags byte.

**When this composite is better than either alone:** Credit-Based alone is blocking (Semaphore); Flow API alone has no inherent memory bound. Together: Flow API provides non-blocking NIO-compatible demand signaling, Credits provide deterministic ArenaBufferPool-bound memory control.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Credit-Based + Flow API (composite) | 10 | 15 | 3 | 15 | 10 | 15 | **68** |
| Reactive Streams (Flow API) | 10 | 12 | 3 | 12 | 8 | 15 | **60** |
| Pull-Based Streaming | 6 | 15 | 5 | 15 | 6 | 12 | **59** |
| Credit-Based (standalone) | 10 | 15 | 4 | 15 | 8 | 6 | **58** |
| Push-Based + Bounded Buffers | 8 | 9 | 4 | 6 | 8 | 6 | **41** |

## Preliminary Recommendation
Credit-Based + Flow API composite wins (68) by 8 points over Flow API alone (60). The composite
uniquely achieves 5 on both Accuracy (hard memory cap from credits) and Fit (non-blocking demand
signaling from Flow API). Neither component alone covers both binding constraints.

## Risks and Open Questions
- Risk: Flow API threading contract (onNext must not block) requires that pool.release() + credit
  return is non-blocking — ArenaBufferPool.release() must be O(1) without locks
- Risk: demand piggybacking on transport frames may require framing protocol extension (new control
  frame type for WINDOW_UPDATE, analogous to HTTP/2)
- Open: credit budget sharing across concurrent queries (hierarchical budget like Presto's
  MemoryContext) is a future concern, not addressed by the per-query credit model

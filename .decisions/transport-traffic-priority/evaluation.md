---
problem: "transport-traffic-priority"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/networking/transport-traffic-priority.md"
    name: "DRR + Strict-Priority Bypass"
    section: "#send-side-scheduler"
  - path: ".kb/distributed-systems/networking/transport-traffic-priority.md"
    name: "Strict Priority Only"
    section: "#trade-offs"
  - path: ".kb/distributed-systems/networking/transport-traffic-priority.md"
    name: "Full Weighted Fair Queuing (WFQ)"
    section: "#trade-offs"
  - path: ".kb/distributed-systems/networking/transport-traffic-priority.md"
    name: "Earliest Deadline First (EDF)"
    section: "#alternative"
constraint_weights:
  scale: 2
  resources: 2
  complexity: 3
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — transport-traffic-priority

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
- Related: [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)

## Constraint Summary
The scheduler must prevent heartbeat starvation (correctness requirement for phi accrual),
run at O(1) per dequeue on a single writer thread, and integrate with the existing
Kafka-style framing protocol. Complexity and accuracy are the binding constraints —
the scheduler must be simple to implement and debug while guaranteeing that CONTROL
traffic is never delayed by lower-priority work.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Scheduler runs per-connection; 1000-node scale doesn't change the per-connection algorithm |
| Resources | 2 | All candidates are pure Java, resource differences are minor |
| Complexity | 3 | Small team, no networking specialization — must be simple and debuggable |
| Accuracy | 3 | Heartbeat starvation prevention is a correctness requirement, not just performance |
| Operational | 2 | v1 uses static weights; monitoring is nice-to-have |
| Fit | 2 | All candidates fit Java NIO; differences are minor |

---

## Candidate: DRR + Strict-Priority Bypass

**KB source:** [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
**Relevant sections read:** `#send-side-scheduler`, `#traffic-classes`, `#implementation-notes`, `#trade-offs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | O(1) per dequeue, per-connection scheduling — independent of cluster size (#send-side-scheduler) |
|        |   |   |    | **Would be a 2 if:** number of priority classes exceeded ~100 (round-robin cycle becomes expensive) |
| Resources | 2 | 5 | 10 | Queue + two counters per class; no heap pressure (#send-side-scheduler) |
|           |   |   |    | **Would be a 2 if:** queue memory grew unbounded without backpressure (addressed by per-stream flow control) |
| Complexity | 3 | 5 | 15 | 15-line algorithm, single-threaded, no virtual clocks (#send-side-scheduler) |
|            |   |   |    | **Would be a 2 if:** the chunking/reassembly logic added significant complexity (it adds ~50 lines, still simple) |
| Accuracy | 3 | 5 | 15 | Strict bypass guarantees CONTROL is never delayed; DRR guarantees no class is starved (#send-side-scheduler, #starvation-prevention) |
|          |   |   |    | **Would be a 2 if:** CONTROL traffic volume became high enough to starve other classes (mitigated by heartbeats being rate-limited at 1-5/s/peer) |
| Operational | 2 | 4 | 8 | Configurable weights, per-class queue depths observable (#key-parameters) |
|             |   |   |   | **Would be a 2 if:** dynamic weight tuning was required in production (v1 uses static weights, acceptable) |
| Fit | 2 | 5 | 10 | Maps to ConcurrentLinkedQueue per class, NIO writer thread, flags byte for chunking (#implementation-notes) |
|     |   |   |    | **Would be a 2 if:** write serialization conflicted with ReentrantLock from connection-pooling (it doesn't — runs inside lock) |
| **Total** | | | **68** | |

**Hard disqualifiers:** None.

**Key strengths for this problem:**
- O(1) per dequeue — negligible scheduling overhead on writer thread (#send-side-scheduler)
- Strict-priority bypass for CONTROL guarantees heartbeat delivery (#starvation-prevention)
- Proven pattern: HDFS FairCallQueue uses the same DRR approach (#trade-offs)

**Key weaknesses for this problem:**
- No per-frame deadline guarantees for INTERACTIVE class — bounded but not deterministic (#trade-offs)

---

## Candidate: Strict Priority Only

**KB source:** [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
**Relevant sections read:** `#trade-offs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | O(1) per dequeue — always drain highest priority first |
| Resources | 2 | 5 | 10 | Even simpler than DRR — one queue per class, no counters |
| Complexity | 3 | 5 | 15 | Simplest possible scheduler |
| Accuracy | 3 | 1 | 3 | **DISQUALIFIER:** Starves BULK under sustained load. If INTERACTIVE + STREAMING are always active, BULK never gets bandwidth (#trade-offs) |
| Operational | 2 | 3 | 6 | No weight tuning possible — priority is absolute |
| Fit | 2 | 5 | 10 | Trivial NIO integration |
| **Total** | | | **54** | |

**Hard disqualifiers:** Starves BULK under sustained load. Violates "all traffic classes must get some minimum bandwidth share" constraint.

**Key strengths for this problem:**
- Simplest possible implementation
- CONTROL traffic is absolutely first — matches correctness requirement

**Key weaknesses for this problem:**
- BULK starvation is guaranteed under sustained mixed load (#trade-offs)
- No fairness guarantees between non-CONTROL classes

---

## Candidate: Full Weighted Fair Queuing (WFQ)

**KB source:** [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
**Relevant sections read:** `#trade-offs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | O(log N) per dequeue where N = priority classes. Acceptable but unnecessary for 5 classes |
|       |   |   |   | **Would be a 2 if:** dequeue cost mattered at high message rates (unlikely for 5 classes, but still unnecessary overhead) |
| Resources | 2 | 4 | 8 | Virtual clock state per class — more bookkeeping than DRR |
|           |   |   |   | **Would be a 2 if:** virtual clock drift required periodic normalization under sustained load |
| Complexity | 3 | 2 | 6 | Virtual clock bookkeeping, sorted scheduling — significantly more complex than DRR (#trade-offs) |
| Accuracy | 3 | 4 | 12 | Provable fairness bounds, but no strict bypass — heartbeats get a large share but not absolute priority |
|          |   |   |    | **Would be a 2 if:** a burst of INTERACTIVE traffic temporarily delayed a heartbeat beyond phi threshold |
| Operational | 2 | 4 | 8 | More tuning parameters, harder to reason about behavior |
|             |   |   |   | **Would be a 2 if:** virtual clock values needed external monitoring to debug priority issues |
| Fit | 2 | 3 | 6 | Implementable in Java but no prior art in similar systems (Cassandra/Kafka don't use WFQ) |
| **Total** | | | **48** | |

**Hard disqualifiers:** None, but complexity score is near-disqualifying.

**Key strengths for this problem:**
- Provable fairness bounds with mathematical guarantees

**Key weaknesses for this problem:**
- Complexity budget violation — virtual clock bookkeeping is unnecessary for 5 priority classes (#trade-offs)
- No strict CONTROL bypass — heartbeats share the scheduling algorithm with other classes

---

## Candidate: Earliest Deadline First (EDF)

**KB source:** [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md)
**Relevant sections read:** `#alternative`, `#trade-offs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | O(log N) per dequeue via priority queue |
|       |   |   |   | **Would be a 2 if:** per-frame deadline computation added latency per enqueue |
| Resources | 2 | 4 | 8 | Priority queue + deadline tracking per frame |
|           |   |   |   | **Would be a 2 if:** deadline propagation from clients to transport added memory overhead |
| Complexity | 3 | 2 | 6 | Deadline propagation from clients through scatter-gather to transport — cross-cutting complexity (#alternative) |
| Accuracy | 3 | 4 | 12 | Tight per-frame latency SLAs, but CONTROL heartbeats don't have natural deadlines |
|          |   |   |    | **Would be a 2 if:** deadline assignment for heartbeats was wrong (too generous → delayed, too aggressive → starves everything else) |
| Operational | 2 | 3 | 6 | Deadline tuning requires understanding end-to-end latency budget, harder than weight tuning |
| Fit | 2 | 3 | 6 | Implementable but awkward — heartbeats have implicit deadlines (protocol period), not explicit ones |
| **Total** | | | **46** | |

**Hard disqualifiers:** None, but complexity and operational scores are near-disqualifying.

**Key strengths for this problem:**
- Best latency guarantees for INTERACTIVE class when deadlines are well-calibrated

**Key weaknesses for this problem:**
- Deadline propagation is cross-cutting — queries need to carry deadline metadata (#alternative)
- Heartbeats don't naturally have deadlines — awkward fit with CONTROL bypass pattern

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| DRR + Strict-Priority Bypass | 10 | 10 | 15 | 15 | 8 | 10 | **68** |
| Strict Priority Only | 10 | 10 | 15 | 3 | 6 | 10 | **54** |
| Full WFQ | 8 | 8 | 6 | 12 | 8 | 6 | **48** |
| EDF (Deadline-Based) | 8 | 8 | 6 | 12 | 6 | 6 | **46** |

## Preliminary Recommendation
DRR + Strict-Priority Bypass wins on weighted total (68) by a wide margin. It is the only
candidate that scores 5 on both Complexity and Accuracy — the two highest-weighted constraints.
It provides O(1) scheduling with guaranteed heartbeat priority and starvation-free bandwidth
sharing, matching all constraints without unnecessary complexity.

## Risks and Open Questions
- Risk: if INTERACTIVE latency p99 is unacceptable under sustained BULK load, EDF could be
  layered in as a hybrid (strict bypass for CONTROL, EDF for INTERACTIVE, DRR for rest)
- Risk: chunking/reassembly adds implementation surface not present in the scheduling
  algorithm alone — may need dedicated testing for partial frame handling
- Open: adaptive weight adjustment based on queue depths (noted in KB Updates section) could
  improve behavior under bursty workloads but is not needed for v1

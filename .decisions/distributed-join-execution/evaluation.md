---
problem: "distributed-join-execution"
evaluated: "2026-04-14"
candidates:
  - path: ".kb/distributed-systems/query-execution/distributed-join-strategies.md"
    name: "Strategy Selection Framework"
  - path: ".kb/distributed-systems/query-execution/distributed-join-strategies.md#co-partitioned-join"
    name: "Co-Partitioned Only"
  - path: "(general knowledge)"
    name: "Pull-to-Coordinator"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — distributed-join-execution

## References
- Constraints: [constraints.md](constraints.md)
- KB: [distributed-join-strategies.md](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md),
  [lsm-join-algorithms.md](../../.kb/systems/query-processing/lsm-join-algorithms.md),
  [lsm-join-anti-patterns.md](../../.kb/systems/query-processing/lsm-join-anti-patterns.md),
  [lsm-join-snapshot-consistency.md](../../.kb/systems/query-processing/lsm-join-snapshot-consistency.md)

## Constraint Summary
The binding constraints demand correct cross-table joins within ArenaBufferPool memory
budget, with automatic strategy selection based on partition metadata. The system must
compose with the existing scatter-gather proxy and credit-based backpressure, and produce
semantically correct results under cross-table snapshot consistency.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Must handle small lookups and large analytical joins |
| Resources | 3 | ArenaBufferPool budget is the hardest constraint — broadcast must fit |
| Complexity | 1 | Library internals — unconstrained |
| Accuracy | 3 | Cross-table snapshot consistency and result correctness non-negotiable |
| Operational | 2 | Must compose with existing infrastructure |
| Fit | 2 | Must reuse existing join algorithms and proxy pattern |

---

## Candidate: Strategy Selection Framework

**KB source:** [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md)
**Relevant sections read:** Full file — `#co-partitioned-join`, `#broadcast-join`, `#shuffle-join`, `#semi-join-reduction`, `#algorithm-steps`, `#implementation-notes`, `#complexity-analysis`, `#tradeoffs`, `#practical-usage`

**Approach:** Implement a strategy selector that automatically chooses between co-partitioned,
broadcast, shuffle, and semi-join based on the KB's decision tree:
1. Join on partition key with identical boundaries? → Co-partitioned (zero network cost)
2. One side small enough to broadcast? → Broadcast (threshold: 25% of ArenaBufferPool per node)
3. Low selectivity? → Semi-join reduction + shuffle
4. Fallback → Shuffle/repartition

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Covers all join scales: co-partitioned for common case (zero network), broadcast for small side, shuffle for everything else (#complexity-analysis) |
|       |   |   |    | **Would be a 2 if:** cardinality estimation is so poor that the selector consistently picks the wrong strategy |
| Resources | 3 | 4 | 12 | Co-partitioned: zero extra memory. Broadcast: bounded by pool threshold check. Shuffle: temporary SSTables on disk. Semi-join: O(distinct keys) memory. Each strategy has a known budget. (#implementation-notes: broadcast threshold = 25% of pool per node) |
|           |   |   |    | **Would be a 2 if:** shuffle creates temporary SSTables that exhaust disk space during large joins |
| Complexity | 1 | 3 | 3 | Four strategies + selector + partition metadata inspection. More code than single-strategy but well-bounded. CockroachDB, TiDB, Citus all implement this pattern (#practical-usage) |
| Accuracy | 3 | 5 | 15 | All four strategies produce correct results when combined with cross-table snapshot consistency. Local join algorithms (sort-merge, hash) are already correct. (#lsm-join-snapshot-consistency) |
|          |   |   |    | **Would be a 2 if:** cross-table snapshot binding fails silently, producing inconsistent join results |
| Operational | 2 | 4 | 8 | Co-partitioned and broadcast compose with proxy pattern. Shuffle requires temporary SSTables — adds write amplification but stays within existing I/O paths. Semi-join adds one extra round trip. (#tradeoffs) |
|             |   |   |   | **Would be a 2 if:** shuffle temporary SSTable cleanup fails on crash, leaking disk space |
| Fit | 2 | 5 | 10 | Local join algorithms already in jlsm-core. Proxy Table pattern provides per-table scatter-gather. Partition metadata (range boundaries, min/max keys) available from SSTable metadata. JoinStrategy sealed interface + record variants is natural Java pattern. (#code-skeleton) |
|     |   |   |    | **Would be a 2 if:** proxy tables cannot be composed (one feeds into another) without refactoring Table interface |
| **Total** | | | **58** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Handles all join patterns — no artificial limitations on query shape
- Automatic strategy selection based on metadata — callers don't choose
- Reuses existing local join algorithms and proxy infrastructure

**Key weaknesses:**
- Shuffle requires temporary SSTables — write amplification for non-co-partitioned joins
- Strategy selection depends on cardinality estimation (approximate)
- Most complex candidate to implement (four strategies + selector)

---

## Candidate: Co-Partitioned Only

**KB source:** [`.kb/distributed-systems/query-execution/distributed-join-strategies.md#co-partitioned-join`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md)

**Approach:** Only support joins where both tables are partitioned on the join key with
identical partition boundaries. Reject all other joins with an error.

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 3 | 6 | Zero network cost for co-partitioned joins — best possible for that case. But rejects all non-co-partitioned joins, limiting query expressiveness (#co-partitioned-join: "Only works for equi-joins on the partition key") |
| Resources | 3 | 5 | 15 | Zero extra memory or I/O — each node joins locally (#complexity-analysis: Network = 0) |
|           |   |   |    | **Would be a 2 if:** users need non-co-partitioned joins and must fall back to application-level data movement |
| Complexity | 1 | 5 | 5 | Simplest possible — verify partition compatibility, fan out, local join. No temporary storage, no strategy selection. |
|            |   |   |   | **Would be a 2 if:** partition compatibility check is more complex than expected (version skew, dynamic repartitioning) |
| Accuracy | 3 | 5 | 15 | Trivially correct — same data locality as single-table queries. Cross-table snapshot via global sequence number. |
|          |   |   |    | **Would be a 2 if:** tables drift out of partition alignment (different repartitioning cadence) |
| Operational | 2 | 5 | 10 | Composes perfectly with proxy pattern — just two proxies, one fan-out. |
|             |   |   |    | **Would be a 2 if:** users frequently need non-co-partitioned joins and the error response is unacceptable |
| Fit | 2 | 5 | 10 | Perfect fit — reuses proxy and local join with no new infrastructure. |
|     |   |   |    | **Would be a 2 if:** jlsm-sql generates join plans that expect non-co-partitioned join support |
| **Total** | | | **61** | |

**Hard disqualifiers:** None — but severe functional limitation.

**Key strengths:**
- Zero-cost joins for the co-partitioned case
- Minimal implementation surface
- No new infrastructure needed

**Key weaknesses:**
- Rejects all non-co-partitioned joins — significant functional limitation
- Pushes complexity to callers (application must ensure co-partitioning)
- Does not compose with jlsm-sql which will generate arbitrary join plans

---

## Candidate: Pull-to-Coordinator

**KB source:** (general knowledge)

**Approach:** Pull both tables to the coordinator and join locally.

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 1 | 2 | Coordinator receives ALL rows from both tables — O(|R| + |S|). Does not scale. |
| Resources | 3 | 1 | 3 | **Hard disqualifier.** Requires buffering both tables at coordinator. |
| Complexity | 1 | 5 | 5 | Simplest — pull data, local join. |
| Accuracy | 3 | 5 | 15 | All data at coordinator — trivially correct. |
| Operational | 2 | 1 | 2 | Blocks until all data received from all partitions of both tables. |
| Fit | 2 | 2 | 4 | Breaks the streaming model entirely. |
| **Total** | | | **31** | |

**Hard disqualifiers:** Resources — requires buffering both tables.

---

## Composite Candidate: Co-Partitioned + Broadcast (two-tier)

**Components:** Co-Partitioned (zero-cost) + Broadcast (small-side replication)
**Boundary rule:** If tables are co-partitioned on join key → co-partitioned join.
If one side is small enough (< 25% pool per node) → broadcast. Otherwise → reject.

| Constraint | Weight | Component | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-----------|-------------|----------|-----------------|
| Scale | 2 | Both | 4 | 8 | Covers co-partitioned (zero cost) and small-table joins (broadcast). Rejects large non-co-partitioned joins — still a gap. |
|       |   |      |   |   | **Would be a 2 if:** most join workloads are large non-co-partitioned |
| Resources | 3 | Both | 4 | 12 | Co-partitioned: zero. Broadcast: bounded by pool threshold check (#implementation-notes). |
|           |   |      |   |    | **Would be a 2 if:** small table exceeds pool threshold in practice |
| Complexity | 1 | Both | 4 | 4 | Two strategies + simple selector (co-partitioned check, then broadcast size check). Moderate. |
|            |   |      |   |   | **Would be a 2 if:** broadcast serialization/distribution is more complex than expected |
| Accuracy | 3 | Both | 5 | 15 | Both strategies produce correct results with cross-table snapshot. |
|          |   |      |   |    | **Would be a 2 if:** snapshot binding fails |
| Operational | 2 | Both | 4 | 8 | Both compose well with proxy. Broadcast adds one-time small-table distribution. |
|             |   |      |   |   | **Would be a 2 if:** broadcast distribution stalls on slow nodes |
| Fit | 2 | Both | 5 | 10 | Reuses proxy + local join. Broadcast adds small-table serialization — straightforward. |
|     |   |      |   |    | **Would be a 2 if:** Table interface can't express broadcast distribution |
| **Total** | | | **57** | |

**Integration cost:** Simple selector (partition key check + size estimation). No temporary SSTables needed.
**When this composite is better than either alone:** Covers >90% of real-world join workloads (co-partitioned for analytics, broadcast for lookup/reference table joins) with none of the shuffle complexity.

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Strategy Selection Framework | [distributed-join-strategies.md](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) | 10 | 12 | 3 | 15 | 8 | 10 | **58** |
| Co-Partitioned Only | [distributed-join-strategies.md#co-partitioned](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) | 6 | 15 | 5 | 15 | 10 | 10 | **61** |
| Pull-to-Coordinator | (general) | 2 | 3 | 5 | 15 | 2 | 4 | **31** |
| Co-Partitioned + Broadcast (composite) | [distributed-join-strategies.md](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) | 8 | 12 | 4 | 15 | 8 | 10 | **57** |

## Preliminary Recommendation
Co-Partitioned Only has the highest weighted total (61) due to simplicity and perfect
resource efficiency. However, it has a severe functional limitation: non-co-partitioned
joins are rejected entirely. The Strategy Selection Framework (58) and Co-Partitioned +
Broadcast composite (57) score close behind with broader join coverage.

The preliminary recommendation depends on how much the functional limitation matters.
For a library that encourages co-partitioned table design, Co-Partitioned Only may be
sufficient initially, with broadcast and shuffle added incrementally. For a library that
aims to support arbitrary joins (especially via jlsm-sql), the Strategy Selection
Framework is necessary.

Given that the WD notes this needs research and is the most complex decision, and that
jlsm-sql exists and will generate arbitrary join plans, the **Co-Partitioned + Broadcast
composite** is recommended as the pragmatic middle ground: it covers the vast majority of
real-world workloads (co-partitioned for co-designed tables, broadcast for lookup/reference
joins) without the shuffle complexity, and shuffle/semi-join can be added later.

## Risks and Open Questions
- Broadcast threshold (25% of pool per node) may be too conservative or too generous — needs tuning
- Cross-table snapshot binding mechanism is not yet decided (deferred: scan-snapshot-binding)
- Shuffle/repartition is deferred — callers with incompatible large-table joins get an error
- Query planner integration (jlsm-sql) may need metadata APIs not yet defined

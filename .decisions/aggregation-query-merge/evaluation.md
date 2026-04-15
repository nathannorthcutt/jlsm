---
problem: "aggregation-query-merge"
evaluated: "2026-04-14"
candidates:
  - path: ".kb/distributed-systems/query-execution/distributed-join-strategies.md#aggregation-pushdown"
    name: "Two-Phase Partial Aggregation"
  - path: "(general knowledge — no dedicated KB entry)"
    name: "Full Materialization at Coordinator"
  - path: "(general knowledge — no dedicated KB entry)"
    name: "Streaming Aggregate Merge"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — aggregation-query-merge

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The binding constraints demand exact aggregation results within the ArenaBufferPool
memory budget, streaming through the credit-based Flow API backpressure model, and
transparent to callers via the Table interface. Resources (bounded memory) and
accuracy (exact results) are the tightest constraints.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Partition count matters but is bounded by cluster size |
| Resources | 3 | ArenaBufferPool credit budget is the hardest constraint |
| Complexity | 1 | Library internals — implementation complexity unconstrained |
| Accuracy | 3 | Exact results are non-negotiable per project convention |
| Operational | 2 | Must work within existing backpressure model |
| Fit | 2 | Must compose with existing proxy and Table interface |

---

## Candidate: Two-Phase Partial Aggregation

**KB source:** [`.kb/distributed-systems/query-execution/distributed-join-strategies.md#aggregation-pushdown`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md)
**Relevant sections read:** `#aggregation-pushdown`, `#complexity-analysis`, `#tradeoffs`, `#practical-usage`

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | [REVISED] Reduces coordinator input from O(rows) to O(groups) for low-cardinality GROUP BY. High-cardinality GROUP BY degrades toward O(rows) (#complexity-analysis). Scalar aggregates (no GROUP BY) always O(P). |
|        |   |   |    | **Would be a 2 if:** GROUP BY cardinality approaches row count (every row is its own group) |
| Resources | 3 | 4 | 12 | [REVISED] Bounded for scalar aggregates and low-cardinality GROUP BY. High-cardinality GROUP BY requires cardinality guard — must spill or fall back to streaming when partials exceed credit budget (#aggregation-pushdown) |
|           |   |   |    | **Would be a 2 if:** GROUP BY has millions of distinct groups AND P is large — partial aggregates exceed credit budget with no fallback |
| Complexity | 1 | 4 | 4 | Well-understood pattern; TiDB, YugabyteDB, CockroachDB all implement this (#practical-usage) |
|            |   |   |   | **Would be a 2 if:** holistic aggregates (MEDIAN, PERCENTILE) enter scope requiring fundamentally different merge logic |
| Accuracy | 3 | 5 | 15 | Algebraic aggregates (SUM, COUNT, MIN, MAX) decompose exactly. AVG = SUM/COUNT pair — exact (#aggregation-pushdown) |
|          |   |   |    | **Would be a 2 if:** floating-point SUM order-dependence matters (different partition groupings → different rounding) |
| Operational | 2 | 3 | 6 | [REVISED] Scalar aggregates stream naturally. GROUP BY has an implicit barrier — coordinator cannot emit final results until all partitions report. Tail latency of slowest partition dominates at high P (#aggregation-pushdown) |
|             |   |   |   | **Would be a 2 if:** severe partition skew causes stragglers that hold completed partials in memory |
| Fit | 2 | 5 | 10 | Natural extension of scatter-gather proxy: partition computes, coordinator merges. Same fan-out/collect pattern (#practical-usage) |
|     |   |   |    | **Would be a 2 if:** Table interface cannot express aggregation requests without breaking the existing contract |
| **Total** | | | **55** | |

**Hard disqualifiers:** None. DISTINCT aggregates explicitly out of scope (cannot decompose).

**Key strengths for this problem:**
- Minimal network traffic: sends partials instead of full result sets
- Bounded coordinator memory: O(groups) not O(rows)
- Exact for all algebraic aggregates

**Key weaknesses for this problem:**
- COUNT(DISTINCT) requires either HyperLogLog (approximate — violates accuracy constraint) or full key merge (expensive)
- Holistic aggregates (MEDIAN, PERCENTILE) cannot be decomposed — need full data or approximation

---

## Candidate: Full Materialization at Coordinator

**KB source:** (general knowledge — no dedicated KB entry)

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 2 | 4 | Coordinator receives ALL rows from all partitions — O(total_rows). Does not scale. |
| Resources | 3 | 1 | 3 | **Hard disqualifier.** Requires buffering all rows at coordinator, violating ArenaBufferPool credit budget for any non-trivial dataset. |
| Complexity | 1 | 5 | 5 | Simplest possible implementation — aggregate locally after collecting everything |
| Accuracy | 3 | 5 | 15 | All data at coordinator — exact results trivially |
| Operational | 2 | 1 | 2 | Blocks until all data received. Violates streaming/backpressure requirement. |
| Fit | 2 | 3 | 6 | Works with Table interface but breaks the streaming k-way merge pattern |
| **Total** | | | **35** | |

**Hard disqualifiers:** Resources — violates ArenaBufferPool memory bound.

**Key strengths:** Trivially correct.
**Key weaknesses:** Unbounded memory, no streaming, does not scale.

---

## Candidate: Streaming Aggregate Merge

**KB source:** (general knowledge — derived from k-way merge pattern in scatter-gather ADR)

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 3 | 6 | Better than full materialization but still processes all rows through coordinator |
| Resources | 3 | 3 | 9 | Streaming means bounded working memory, but coordinator still processes O(rows) entries through the merge |
| Complexity | 1 | 3 | 3 | Requires aggregation-aware merge operator — more complex than two-phase |
| Accuracy | 3 | 5 | 15 | All data passes through — exact results |
| Operational | 2 | 3 | 6 | Streaming compatible with Flow API but high throughput load on coordinator |
| Fit | 2 | 4 | 8 | Natural extension of existing k-way merge iterator |
|     |   |   |   | **Would be a 2 if:** aggregation operators don't compose cleanly with the merge iterator's comparator |
| **Total** | | | **47** | |

**Hard disqualifiers:** None, but resource usage is borderline.

**Key strengths:** Streaming — bounded working memory.
**Key weaknesses:** Still processes all rows at coordinator — high CPU and network cost compared to two-phase.

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Two-Phase Partial Aggregation | [distributed-join-strategies.md](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) | 8 | 12 | 4 | 15 | 6 | 10 | **55** |
| Full Materialization | (general) | 4 | 3 | 5 | 15 | 2 | 6 | **35** |
| Streaming Aggregate Merge | (general) | 6 | 9 | 3 | 15 | 6 | 8 | **47** |

## Preliminary Recommendation [REVISED after falsification]
Two-Phase Partial Aggregation wins with a revised weighted total of 55 vs 47 (streaming)
and 35 (full materialization). The gap narrowed from the initial 62 after falsification
revealed that GROUP BY cardinality is a load-bearing assumption for Scale and Resources
scores. The recommendation holds with two mandatory guards:

1. **Cardinality guard:** If GROUP BY partial aggregates would exceed the credit budget at
   the coordinator, fall back to sorted-run merge aggregation (streaming merge exploiting
   LSM sort order). The cardinality threshold = `credit_budget / (P * per_group_bytes)`.
2. **DISTINCT scope exclusion:** DISTINCT aggregates (COUNT(DISTINCT), SUM(DISTINCT)) are
   explicitly out of scope — they cannot be decomposed and require separate machinery.

## Falsification Results
- **Scale (5→4):** GROUP BY cardinality assumption was unstated. High-cardinality GROUP BY
  degrades to near-full-materialization. Revised.
- **Resources (5→4):** Same cardinality assumption. Mandatory cardinality guard added.
- **Operational (4→3):** Barrier semantics at coordinator — must wait for all partitions
  before emitting final GROUP BY results. Tail latency dominates at high P.
- **Missing candidate identified:** Adaptive Hybrid (two-phase + sorted-merge fallback).
  Incorporated as a guard clause in the recommendation rather than a separate candidate.
- **DISTINCT aggregates:** Potential hard disqualifier if in scope. Explicitly scoped out.

## Risks and Open Questions
- High-cardinality GROUP BY with the cardinality guard falls back to streaming — the
  guard threshold must be tunable and documented
- Floating-point SUM ordering across partitions may produce non-deterministic rounding —
  acceptable for jlsm's integer-first field types
- Sorted-run merge fallback assumes GROUP BY key aligns with partition sort order —
  if not, fallback degrades to unsorted streaming merge

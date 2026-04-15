---
problem: "limit-offset-pushdown"
evaluated: "2026-04-14"
candidates:
  - path: ".kb/distributed-systems/query-execution/distributed-join-strategies.md#limit-offset-pushdown"
    name: "Top-N Pushdown with Keyset Pagination"
  - path: "(baseline from scatter-gather-query-execution ADR)"
    name: "Naive Per-Partition Over-Fetch"
  - path: "(general knowledge)"
    name: "Global Offset Coordinator Tracking"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — limit-offset-pushdown

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The binding constraints demand that large OFFSET values do not cause proportional
over-fetching (Scale weight 3), results are globally correct in sort order
(Accuracy weight 3), and the solution works within the credit-based continuation
token model already adopted. The primary pain point is OFFSET: the current approach
requests LIMIT+OFFSET rows from every partition, making deep pagination O(P * OFFSET)
in network traffic.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | Large OFFSET is the primary problem — solution must not degrade with OFFSET depth |
| Resources | 2 | Credit budget is fixed but continuation tokens are already efficient |
| Complexity | 1 | Library internals — unconstrained |
| Accuracy | 3 | Global ordering correctness is non-negotiable |
| Operational | 2 | Must compose with existing Flow API model |
| Fit | 2 | Must build on existing continuation token infrastructure |

---

## Candidate: Top-N Pushdown with Keyset Pagination

**KB source:** [`.kb/distributed-systems/query-execution/distributed-join-strategies.md#limit-offset-pushdown`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) and [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md)
**Relevant sections read:** `#limit-offset-pushdown`, `#keyset-pagination`, `#continuation-token-encoding`, `#backpressure-integration`, `#trade-offs`

**Approach:** Replace OFFSET with keyset (cursor) pagination using continuation tokens.
For LIMIT, push top-N to each partition: each returns sorted top-LIMIT rows, coordinator
k-way merges and takes the global top-LIMIT. For subsequent pages, the coordinator sends
the continuation token (last key from previous page) as the lower bound, so each
partition seeks to that position and returns the next LIMIT rows. OFFSET becomes a
client-side concept: "page 5" means "request page 5 using the continuation token from
page 4." No partition ever skips rows — they always scan from a known position.

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 5 | 15 | Keyset eliminates OFFSET entirely — each page is O(P * LIMIT) regardless of how deep into the result set. "Large offsets remain expensive — keyset (cursor) pagination is preferred" (#limit-offset-pushdown). O(1) per-page cost vs O(OFFSET) for offset-based. |
|       |   |   |    | **Would be a 2 if:** random access to arbitrary page numbers is required (keyset is forward-only — can't jump to page 500 without visiting pages 1-499) |
| Resources | 2 | 5 | 10 | Each page is exactly one credit allocation per partition. No additional buffering beyond what credit model already provides. Token is 21-141 bytes (#continuation-token-encoding) |
|           |   |   |    | **Would be a 2 if:** token size grows unboundedly (e.g., multi-column composite keys with large values) |
| Complexity | 1 | 4 | 4 | Well-understood pattern used by Cosmos DB, Cassandra, CockroachDB (#practical-usage). Token encode/decode is ~50 lines of code (#code-skeleton) |
|            |   |   |   | **Would be a 2 if:** secondary index scans require complex filter state in the token (Cosmos DB's approach) |
| Accuracy | 3 | 4 | 12 | Correct with sequence-number snapshot binding — sees same logical snapshot across pages (#stateless-continuation-tokens). Point-in-time consistency. |
|          |   |   |    | **Would be a 2 if:** snapshot expires between pages (GC watermark advances past scan's sequence number) — scan fails with STALE_TOKEN (#stateless-compaction-safety) |
| Operational | 2 | 5 | 10 | "Demand-driven paging IS pull-based iteration with credit-bounded concurrency" (#backpressure-integration). Natural fit with Flow API — each request(1) sends PageRequest with token. |
|             |   |   |    | **Would be a 2 if:** backward pagination is needed (tokens are forward-only) |
| Fit | 2 | 5 | 10 | Continuation tokens already adopted by scatter-backpressure ADR. PageRequest/PageResponse types already defined. k-way merge iterator already exists. (#jlsm-applicability) |
|     |   |   |    | **Would be a 2 if:** PartitionedTable API must expose OFFSET as a first-class parameter (breaking change to Table interface) |
| **Total** | | | **61** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Eliminates OFFSET cost entirely — each page is O(P * LIMIT) regardless of depth
- Builds directly on already-decided infrastructure (continuation tokens, Flow API)
- Standard pattern across production distributed databases

**Key weaknesses:**
- Forward-only: cannot jump to arbitrary page without traversing prior pages
- Snapshot expiry risk on very long scans (mitigated by scan lease mechanism — deferred)
- Does not help with traditional SQL OFFSET semantics (caller must use cursor API instead)

---

## Candidate: Naive Per-Partition Over-Fetch (Baseline)

**KB source:** (scatter-gather-query-execution ADR, section "LIMIT/OFFSET")

**Approach:** Request LIMIT+OFFSET rows from every partition, coordinator k-way merges,
skips OFFSET, returns LIMIT rows. Current behavior.

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 1 | 3 | O(P * (LIMIT+OFFSET)) network traffic. At P=100, OFFSET=10000, LIMIT=20: each partition sends 10020 rows. **Hard disqualifier** for deep pagination. |
| Resources | 2 | 2 | 4 | Must buffer OFFSET+LIMIT rows at coordinator before trimming. Exceeds credit budget for large OFFSET. |
| Complexity | 1 | 5 | 5 | Simplest possible — already implemented in scatter-gather proxy. |
| Accuracy | 3 | 5 | 15 | Trivially correct — all rows at coordinator, standard sort. |
| Operational | 2 | 2 | 4 | Blocks on receiving LIMIT+OFFSET rows from slowest partition. Latency proportional to OFFSET. |
| Fit | 2 | 4 | 8 | Works with existing proxy but does not use continuation tokens. |
| **Total** | | | **39** | |

**Hard disqualifiers:** Scale — O(P * OFFSET) over-fetch makes deep pagination impractical.

---

## Candidate: Global Offset Coordinator Tracking

**KB source:** (general knowledge)

**Approach:** Coordinator maintains a global position counter. For OFFSET, coordinator
uses partition key range metadata to determine how many rows each partition contributes
before the offset point. Routes requests only to partitions that contain rows at the
target offset range.

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 3 | 9 | Can skip entire partitions if their row count is below the cumulative offset. But requires accurate per-partition row count estimates, which LSM trees do not provide cheaply (SSTable metadata gives approximate counts only). |
| Resources | 2 | 3 | 6 | Requires partition statistics (row counts per range) at coordinator — moderate memory. |
| Complexity | 1 | 2 | 2 | Requires accurate per-partition cardinality estimation, which is hard for LSM trees with tombstones, overlapping levels, and concurrent compaction. Estimates become stale between requests. |
| Accuracy | 3 | 2 | 6 | Approximate row counts → approximate offset positioning. May skip rows or duplicate rows at partition boundaries. Exact offset requires reading all rows up to the offset point anyway. |
| Operational | 2 | 3 | 6 | Reduces fan-out for some queries but adds a statistics-collection burden. |
| Fit | 2 | 2 | 4 | Requires new infrastructure (partition statistics, cardinality estimation) not present in jlsm. Does not compose with continuation tokens. |
| **Total** | | | **33** | |

**Hard disqualifiers:** Accuracy — approximate offset positioning violates correctness constraint.

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Top-N Pushdown + Keyset | [distributed-join-strategies.md](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) + [distributed-scan-cursors.md](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) | 15 | 10 | 4 | 12 | 10 | 10 | **61** |
| Naive Over-Fetch | (scatter-gather ADR) | 3 | 4 | 5 | 15 | 4 | 8 | **39** |
| Global Offset Tracking | (general) | 9 | 6 | 2 | 6 | 6 | 4 | **33** |

## Preliminary Recommendation
Top-N Pushdown with Keyset Pagination wins with a weighted total of 61 vs 39 (over-fetch)
and 33 (global offset tracking). Keyset pagination eliminates the OFFSET cost entirely
by replacing positional offsets with continuation tokens, making every page O(P * LIMIT)
regardless of pagination depth. This builds directly on the already-decided continuation
token infrastructure from the scatter-backpressure ADR.

## Falsification Results
- **Scale (5):** Holds for key-ordered scans. Would break for secondary-sort ORDER BY, but
  jlsm's query model (F10, F30) returns results in LSM sort order — secondary-sort is not
  supported at the distributed level. Caveat documented in ADR scope.
- **Fit (5):** Continuation token encodes primary key position only. Secondary-sort would
  need a different token format. Not a concern given current query model.
- **Partition pruning noted as complementary:** already decided in scatter-gather ADR
  (R39 prunes partitions before scatter). Not a missing candidate — it's additive.
- **Small-OFFSET workload:** Naive over-fetch is equivalent for OFFSET=0 queries. True but
  not a reason to avoid keyset — keyset handles OFFSET=0 identically and also handles deep
  pagination. No downside to adopting keyset as the universal model.

## Risks and Open Questions
- Forward-only pagination: callers expecting SQL OFFSET semantics must adapt to cursor-based API
- Snapshot expiry on very long scans — mitigated by scan lease mechanism (deferred decision)
- Secondary-sort pagination (ORDER BY non-key column) requires richer token — deferred
- k-way merge requires P pages simultaneously at coordinator — credit budget must be sized to fan-out

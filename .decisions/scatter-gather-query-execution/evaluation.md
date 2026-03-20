---
problem: "scatter-gather-query-execution"
evaluated: "2026-03-20"
candidates:
  - path: "general-knowledge"
    name: "Partition-Aware Proxy Table"
  - path: "general-knowledge"
    name: "Coordinator Node Scatter-Gather"
  - path: "general-knowledge"
    name: "Client-Side Fan-Out"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — scatter-gather-query-execution

## References
- Constraints: [constraints.md](constraints.md)
- Related ADRs: engine-api-surface-design, table-partitioning, partition-to-node-ownership

## Constraint Summary
Query execution must scatter to partition owners concurrently, merge results correctly
(preserving order from range partitions), indicate partial results transparently, and
compose with the Engine/Table interface handle pattern.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Fan-out concurrency is straightforward |
| Resources | 1 | Not constraining |
| Complexity | 1 | High budget |
| Accuracy | 3 | Correct merge ordering, LIMIT/OFFSET, partial result tracking |
| Operational | 3 | Concurrent fan-out, timeout on unavailable, non-blocking |
| Fit | 3 | Must compose with Table interface handle pattern — highest priority |

---

## Candidate: Partition-Aware Proxy Table

A clustered table wraps each partitioned table in a proxy that implements the
same Table interface. The proxy: (1) looks up partition owners via HRW,
(2) sends sub-queries to each owner via the transport abstraction concurrently,
(3) merges results using a k-way merge iterator (range partitions are naturally
ordered), (4) attaches PartialResultMetadata to the response indicating any
unavailable partitions.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Concurrent fan-out via transport; k-way merge is O(total_results * log(P)) |
| Resources | 1 | 5 | 5 | Streaming merge — no need to buffer all results before returning |
| Complexity | 1 | 3 | 3 | Proxy must handle all Table operations (query, scan, get, mutate). K-way merge for ordered results. LIMIT/OFFSET requires over-fetching |
| Accuracy | 3 | 5 | 15 | K-way merge on range-partitioned data preserves global order. Each partition returns its range — no overlap, no duplicates. PartialResultMetadata tracks missing partitions |
| Operational | 3 | 5 | 15 | Concurrent fan-out. Timeout per partition — result from slow/dead partition is marked missing, others return. Streaming merge — first results available immediately |
| Fit | 3 | 5 | 15 | Implements Table interface — callers can't distinguish local from clustered table. Composes with Engine handle pattern naturally. Transport abstraction handles in-JVM/NIO |
| **Total** | | | **58** | |

**Hard disqualifiers:** none

**Key strengths:**
- Transparent to callers — same Table interface for local and clustered tables
- Streaming k-way merge exploits range partition ordering — no global sort needed
- PartialResultMetadata provides explicit unavailability information

**Key weaknesses:**
- LIMIT/OFFSET across partitions requires over-fetching (request LIMIT from each partition, merge, then trim)
- Proxy must implement every Table operation — large interface surface

---

## Candidate: Coordinator Node Scatter-Gather

The node receiving the query acts as explicit coordinator. It builds an
execution plan, scatters sub-queries, and gathers results. The coordinator
role is a distinct concept from the Table interface.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Same concurrent fan-out |
| Resources | 1 | 5 | 5 | Same streaming merge |
| Complexity | 1 | 4 | 4 | Separate coordinator concept — cleaner separation but more moving parts |
| Accuracy | 3 | 5 | 15 | Same merge correctness |
| Operational | 3 | 5 | 15 | Same timeout/partial behavior |
| Fit | 3 | 3 | 9 | Introduces a new "coordinator" concept outside the Table interface. Callers must know they're querying a distributed table and use the coordinator API. Breaks the interface-based handle pattern |
| **Total** | | | **53** | |

**Hard disqualifiers:** none

**Key strengths:**
- Explicit execution model — clear separation of coordinator logic from table logic

**Key weaknesses:**
- Breaks the Engine/Table interface abstraction — callers must use a different API for distributed queries
- Introduces a new concept (coordinator) that doesn't exist in the current design

---

## Candidate: Client-Side Fan-Out

Expose the partition ownership map to callers. They query each partition owner
individually and merge results themselves.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 4 | 4 | Client must manage concurrency |
| Resources | 1 | 5 | 5 | No proxy overhead |
| Complexity | 1 | 5 | 5 | Engine is simpler — no scatter-gather logic |
| Accuracy | 3 | 2 | 6 | Merge correctness pushed to every caller — high risk of bugs. No standard partial result handling |
| Operational | 3 | 3 | 9 | Caller manages timeouts and partial results. Duplicated effort across all callers |
| Fit | 3 | 1 | 3 | Breaks the Table interface contract entirely. Defeats the purpose of the Engine abstraction |
| **Total** | | | **32** | |

**Hard disqualifiers:** breaks Table interface contract

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Partition-Aware Proxy Table | 5 | 5 | 3 | 15 | 15 | 15 | **58** |
| Coordinator Node Scatter-Gather | 5 | 5 | 4 | 15 | 15 | 9 | **53** |
| Client-Side Fan-Out | 4 | 5 | 5 | 6 | 9 | 3 | **32** |

## Preliminary Recommendation
Partition-Aware Proxy Table wins on weighted total (58). It preserves the Table interface abstraction, making clustered tables transparent to callers. The streaming k-way merge exploits range partition ordering for correct, efficient result merging.

## Risks and Open Questions
- LIMIT/OFFSET over-fetching: requesting LIMIT from each partition produces up to P*LIMIT results before trimming. For large P and large LIMIT, this is wasteful. Can optimize later with partition pruning (skip partitions outside the key range).
- Aggregation queries (COUNT, SUM, etc.) need per-partition execution with merge at the proxy — different merge strategy than ordered iteration
- Write operations (PUT, DELETE) route to a single partition owner — no scatter needed, just lookup

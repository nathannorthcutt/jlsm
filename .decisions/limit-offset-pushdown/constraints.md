---
problem: "LIMIT/OFFSET partition pushdown optimization for distributed queries"
slug: "limit-offset-pushdown"
captured: "2026-04-14"
status: "final"
---

# Constraint Profile — limit-offset-pushdown

## Problem Statement
The scatter-gather proxy currently handles LIMIT by requesting LIMIT rows from
each partition and merging via k-way merge, trimming to the global LIMIT.
OFFSET is handled by requesting LIMIT+OFFSET from each partition — O(P * (LIMIT+OFFSET))
network traffic. How should LIMIT/OFFSET be optimized to reduce over-fetching
while preserving correct global ordering?

## Constraints

### Scale
Variable partition count P=1 to P=1000+. OFFSET values can be large (e.g., deep
pagination through millions of results). The solution must not degrade with
large OFFSET values or high partition counts.

### Resources
Bounded by ArenaBufferPool credit budget (from scatter-backpressure ADR). The
credit-based Flow API model means each page is a bounded allocation. The
solution must work within the existing credit budget — no additional memory
allocations beyond what the backpressure model provides.

### Complexity Budget
Library internals — implementation complexity unconstrained. API must be simple:
callers use `query(predicate, limit)` or `scan(from, to)` without pagination knowledge.

### Accuracy / Correctness
Results must be globally correct: the N-th through (N+OFFSET)-th entries in
global sort order must be exactly those returned. No duplicates, no gaps, no
reordering. For key-ordered scans, the merge must preserve the natural LSM
sort order. Partial results from unavailable partitions are acceptable (already
decided in scatter-gather ADR) but must be flagged.

### Operational Requirements
Must compose with the credit-based Flow API backpressure model from
scatter-backpressure ADR. Continuation tokens (already adopted) are the paging
mechanism. Latency for first-page response should be O(P * log N) (one seek
per partition), not O(P * (LIMIT+OFFSET)).

### Fit
Java 25, JPMS. Must compose with continuation tokens (scatter-backpressure ADR),
k-way merge iterator (scatter-gather ADR), and existing `PartitionedTable`
query API (F30 spec). Already decided: the transport uses `PageRequest`/
`PageResponse` with continuation tokens.

## Key Constraints (most narrowing)
1. **Large OFFSET must not cause proportional over-fetch** — the current
   O(P * (LIMIT+OFFSET)) approach is the primary pain point
2. **Credit budget** — solution must not require additional memory beyond
   existing credit allocations
3. **Continuation token compatibility** — must build on the already-decided
   stateless paging model

## Unknown / Not Specified
None — full profile captured from parent ADRs, specs, and project constraints.

## Constraint Falsification — 2026-04-14
Checked: F30 (partition-data-operations), scatter-gather ADR, scatter-backpressure
ADR, distributed-scan-cursors.md, distributed-join-strategies.md.
- Operational: scatter-backpressure ADR's continuation token model already
  constrains the paging mechanism. Confirmed.
- Accuracy: F30 R39 defines `query(predicate, limit)` but does not specify
  OFFSET behavior. This ADR fills that gap.
- No additional implied constraints found.

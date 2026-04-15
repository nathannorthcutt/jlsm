---
problem: "Aggregation query merge strategies for distributed partitioned tables"
slug: "aggregation-query-merge"
captured: "2026-04-14"
status: "final"
---

# Constraint Profile — aggregation-query-merge

## Problem Statement
How should the scatter-gather proxy merge aggregation results (COUNT, SUM, AVG,
MIN, MAX, GROUP BY) from per-partition execution into a single result for the
caller? The proxy implements the Table interface transparently (per
scatter-gather-query-execution ADR), so aggregation must compose with the
existing k-way merge iterator pattern.

## Constraints

### Scale
Variable — jlsm is a library. The partition count P is the primary scale
dimension: aggregation must work for P=1 through P=1000+. GROUP BY cardinality
is the secondary dimension: the number of distinct groups determines merge
memory. No fixed upper bound on group count, but the memory budget (Resources)
constrains it.

### Resources
Bounded by ArenaBufferPool. The scatter-backpressure ADR allocates credits per
partition per query: `pool_capacity / (page_buffer_size * P * concurrent_queries)`.
Aggregation merge must operate within this budget — no unbounded accumulation.
Pure Java, no external dependencies.

### Complexity Budget
Library code consumed by application developers. The aggregation API must be
simple to use — callers should not need to understand the merge strategy. The
implementation complexity is unconstrained (per project convention), but the
public API surface must be minimal.

### Accuracy / Correctness
Exact results required for all standard aggregates (COUNT, SUM, MIN, MAX).
AVG must be computed from exact SUM and COUNT — no approximate averages.
Approximate aggregation (HyperLogLog for DISTINCT COUNT, sketches for
percentiles) is explicitly out of scope for this decision. Results must be
identical whether the query runs on 1 partition or 100.

### Operational Requirements
Streaming execution: aggregation merge must work within the credit-based
Flow API backpressure model from scatter-backpressure ADR. No full
materialization of partition results before merging. Timeout and partial result
semantics from scatter-gather-query-execution ADR apply.

### Fit
Java 25, JPMS, existing Table interface and PartitionedTable proxy pattern.
Must compose with the k-way merge iterator already decided for non-aggregation
queries. Must work with both local and remote (S3/GCS) backends.

## Key Constraints (most narrowing)
1. **Bounded memory via ArenaBufferPool credits** — aggregation cannot buffer
   all partition results; must use streaming or partial-aggregate patterns
2. **Exact results** — eliminates approximate/probabilistic approaches
3. **Table interface transparency** — callers use the same API regardless of
   aggregation; no special "distributed aggregation" API

## Unknown / Not Specified
None — full profile captured from parent ADRs and project constraints.

## Constraint Falsification — 2026-04-14
Checked: F04 (engine-clustering), F10 (table-indices-and-queries), F11
(table-partitioning), scatter-gather-query-execution ADR, scatter-backpressure ADR.
- Accuracy: F10 explicitly defers aggregation but does not specify approximate vs
  exact. Project convention (correctness-first) implies exact. Confirmed.
- Operational: scatter-backpressure ADR's credit model is the binding memory
  constraint. Confirmed.
- No additional implied constraints found.

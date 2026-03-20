---
problem: "How should queries on partitioned tables fan out to partition owners and merge results?"
slug: "scatter-gather-query-execution"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — scatter-gather-query-execution

## Problem Statement
Define the query execution model for partitioned tables in a clustered jlsm-engine. When a query targets a partitioned table, it must be scattered to all partition owners, results gathered and merged into a unified response. Must handle partial results when some owners are unavailable. Must compose with the Engine/Table handle pattern, transport abstraction, and range partitioning.

## Constraints

### Scale
Fan-out to potentially many partition owners (tens to hundreds per table). Must be concurrent.

### Resources
Not constraining.

### Complexity Budget
High. Complex merge logic acceptable.

### Accuracy / Correctness
- Merged results must preserve ordering (range-partitioned data is naturally ordered)
- No duplicate results from overlapping partitions
- Partial results must clearly indicate which partitions were missing
- Correct handling of LIMIT, OFFSET, and aggregation across partitions

### Operational Requirements
- Concurrent fan-out — latency bounded by the slowest responding partition
- Must not block indefinitely on unavailable partitions (timeout + partial result)
- Must work over in-JVM transport now and NIO transport later

### Fit
- Composes with Engine/Table interface-based handle pattern (ADR: engine-api-surface-design)
- Composes with range partitioning (ADR: table-partitioning) — partitions hold contiguous key ranges
- Composes with transport abstraction (in-JVM direct call initially)
- Pure Java 25, no external dependencies

## Key Constraints (most narrowing)
1. **Result correctness** — merge must preserve order and handle LIMIT/OFFSET correctly across partitions
2. **Partial result transparency** — caller must know which partitions are missing
3. **Concurrent execution** — fan-out in parallel, don't serialize partition queries

## Unknown / Not Specified
None — full profile from feature brief and existing ADRs.

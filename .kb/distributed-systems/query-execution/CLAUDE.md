# Query Execution — Category Index
*Topic: distributed-systems*
*Tags: join, broadcast, shuffle, co-partition, semi-join, pushdown, distributed-query, aggregation, limit, offset, scatter-gather*

Distributed query execution strategies for partitioned storage systems.
Covers join distribution, aggregation pushdown, pagination, and partition
pruning. Complements the local query-processing coverage in systems/query-processing.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [distributed-join-strategies.md](distributed-join-strategies.md) | Distributed Join Execution Strategies | active | Co-partitioned: zero network cost | Join strategy selection for range-partitioned LSM |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [distributed-join-strategies.md](distributed-join-strategies.md) — co-partitioned, broadcast, shuffle, semi-join, pushdown

## Research Gaps
- Distributed query optimizer / cost model
- Adaptive query execution (switch strategy mid-query)
- Vectorized distributed execution (batch-at-a-time)

# Query Execution — Category Index
*Topic: distributed-systems*
*Tags: join, broadcast, shuffle, co-partition, semi-join, pushdown, distributed-query, aggregation, limit, offset, scatter-gather, cursor, paging, continuation-token, keyset, iterator-pinning*

Distributed query execution strategies for partitioned storage systems.
Covers join distribution, aggregation pushdown, pagination, and partition
pruning. Complements the local query-processing coverage in systems/query-processing.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [distributed-join-strategies.md](distributed-join-strategies.md) | Distributed Join Execution Strategies | active | Co-partitioned: zero network cost | Join strategy selection for range-partitioned LSM |
| [distributed-scan-cursors.md](distributed-scan-cursors.md) | Distributed Scan Cursor Management | active | O(1) stateless resume | Paged scan cursor model selection for scatter-gather |

## Comparison Summary

Join strategies and scan cursor management are complementary: join execution
determines HOW data from multiple partitions is combined, while cursor
management determines how paged iteration over each partition's contribution
is coordinated. Continuation tokens (stateless cursors) are recommended for
the scatter-gather proxy because they avoid SSTable pinning during long scans
and align naturally with credit-based backpressure.

## Recommended Reading Order
1. Start: [distributed-join-strategies.md](distributed-join-strategies.md) — co-partitioned, broadcast, shuffle, semi-join, pushdown
2. Then: [distributed-scan-cursors.md](distributed-scan-cursors.md) — stateful vs stateless cursors, compaction interaction, backpressure alignment

## Research Gaps
- Distributed query optimizer / cost model
- Adaptive query execution (switch strategy mid-query)
- Vectorized distributed execution (batch-at-a-time)

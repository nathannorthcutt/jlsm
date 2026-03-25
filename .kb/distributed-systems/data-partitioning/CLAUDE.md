# data-partitioning — Category Index
*Topic: distributed-systems*

Strategies for distributing data across multiple nodes in a distributed
storage system. Covers range partitioning, hash partitioning, consistent
hashing, and hybrid approaches, with focus on tradeoffs for LSM-tree
backed stores that need range scan support.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [partitioning-strategies.md](partitioning-strategies.md) | Range, Hash, Consistent Hashing comparison | active | O(log R) routing | Choosing a partitioning model |
| [vector-search-partitioning.md](vector-search-partitioning.md) | Vector search + hybrid filtering in distributed systems | active | scatter-gather O(P*k) | Vector + filter + text search topology |
| [table-partitioning.md](table-partitioning.md) | table-partitioning (feature footprint) | stable | feature audit record | Range partitioning implementation overview |

## Comparison Summary

Range partitioning is the right foundation for LSM-tree backed distributed
storage that needs range scans and key-value CRUD. For vector search, the
recommended overlay is **per-partition local vector indices** with scatter-gather
query routing. This co-locates documents, metadata indices, vector indices, and
inverted indices on the same partition, enabling single-partition filtered vector
search and hybrid (vector + BM25) retrieval. The tradeoff is O(P) fan-out for
vector queries, which is acceptable for moderate partition counts (10-100).

Global IVF sharding (Milvus-style) is better for pure vector workloads at
extreme scale, but doesn't co-locate with document storage.

## Key Papers (2025-2026)

| Paper | Venue | Key Contribution |
|-------|-------|-----------------|
| HAKES | VLDB 2025 | Disaggregated filter-refine with IVF sharding; 16x throughput |
| SIEVE | VLDB 2025 | Multi-index approach for filtered search; 8x speedup |
| CrackIVF | arXiv 2025 | Deferred adaptive index construction; 10-1000x faster startup |
| Attribute Filtering in ANN | arXiv 2025 | Comprehensive filtering benchmark; IVF best at low selectivity |
| LSM-tree Survey | arXiv 2025 | 100+ papers; distributed compaction + disaggregation trends |
| EcoTune | SIGMOD 2025 | Dynamic compaction policy selection |

## Research Gaps
- Partition rebalancing algorithms (load-aware vs size-aware splitting)
- Raft/Paxos per-partition replication details
- Multi-tenant partition isolation strategies
- Cross-partition transaction coordination (2PC, Calvin, Percolator)
- Vector index rebuild cost during range split/merge
- Adaptive nprobe selection based on filter selectivity
- SIEVE-style multi-index approach within range partitions

## Shared References Used
@../../_refs/complexity-notation.md

---
problem: "table-partitioning"
date: "2026-03-16"
version: 1
status: "confirmed"
supersedes: null
---

# ADR-001 — Table Partitioning

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Data Partitioning Strategies | Foundation — range vs hash vs consistent hashing | [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) |
| Vector Search Partitioning | Overlay topology — per-partition co-located indices | [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md) |

---

## Problem
Choose a data partitioning strategy for jlsm-table that distributes documents
across multiple nodes, supports horizontal scaling, and enables efficient
combined queries across all search modalities (property filters, full-text
search, vector similarity, and any combination).

## Constraints That Drove This Decision
- **Combined query efficiency**: All query modalities must execute efficiently
  without cross-partition joins for filtered vector search or hybrid retrieval.
  This was the most narrowing constraint — it ruled out any strategy that
  separates index types from documents.
- **Fit with jlsm architecture**: Must compose with existing JPMS modules,
  LSM-tree sorted storage, and the document/index model. Cannot require
  separate node types or external infrastructure.
- **Correctness on partition operations**: Splits and merges must not lose
  data or produce incorrect results during or after the operation.

## Decision
**Chosen approach: Range Partitioning with Per-Partition Co-located Indices**

Each range partition owns a contiguous key range and contains a complete,
self-contained set of indices: the LSM-tree (documents), secondary indices
(FieldIndex), vector index (IvfFlat or HNSW), and inverted index
(LsmInvertedIndex). This co-location means every query type — property
filters, full-text search, vector similarity, and any combination — executes
within a single partition without cross-partition joins. For queries that
cannot be routed to a single partition (vector similarity, full-text), the
coordinator scatters the query to all partitions in parallel and merges
the top-k results.

## Rationale

### Why Range Partitioning with Co-located Indices
- **Combined query efficiency**: Documents and all their indices live on the
  same partition. A query like "find similar images where category='outdoor'"
  runs the vector search and property filter on the same partition in one pass.
  No cross-partition join required.
  ([vector-search-partitioning#topology-for-jlsm](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#topology-for-jlsm))
- **Fit**: Range boundaries align with LSM-tree SSTable key ranges. The
  existing `StandardLsmTree`, `FieldIndex`, `LsmVectorIndex`, and
  `LsmInvertedIndex` all operate on sorted key sets — a range partition
  is exactly that. Partitioning composes as a routing layer above the
  existing `JlsmTable`.
  ([partitioning-strategies#why-it-fits-lsm-trees](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#range-partitioning))
- **Scale**: Dynamic range splitting (size-based or load-based) is
  production-proven at CockroachDB, TiKV, and FoundationDB scale.
  ([partitioning-strategies#range-partitioning-lifecycle](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#algorithm-steps))
- **Hybrid search**: Vector + BM25 full-text fusion executes entirely
  per-partition via RRF, then merges globally at the coordinator.
  ([vector-search-partitioning#hybrid-search](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#hybrid-search-vector--full-text-bm25))

### Why not Hash Partitioning
- **Breaks range queries**: Hash destroys key ordering. Every property range
  query (`price BETWEEN 100 AND 500`), ordered scan, and secondary index
  range lookup degrades to O(N) fan-out across all partitions.
  ([partitioning-strategies#why-it-hurts-lsm-trees](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#hash-partitioning))

### Why not Global IVF Sharding (Milvus/HAKES-style)
- **Separates vectors from documents**: Every combined query becomes a
  cross-system join between the vector shard cluster and the document store.
  Does not compose with jlsm's single-library architecture.
  ([vector-search-partitioning#compared-to-alternatives](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#compared-to-alternatives))

## Implementation Guidance

Key parameters from [`partitioning-strategies.md#key-parameters`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#key-parameters):
- **Range size threshold**: 64-512 MB per partition (start with 256 MB)
- **Split policy**: Size-based initially; load-based when hotspot detection is added
- **Replication factor**: 3 (deferred — separate decision for consensus protocol)

Key parameters from [`vector-search-partitioning.md#key-parameters`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#key-parameters):
- **Per-partition vector index type**: IvfFlat for larger partitions, HNSW for smaller
- **Scatter-gather merge**: Top-k merge across partitions for vector queries
- **Hybrid fusion**: RRF with configurable alpha for vector+text queries

Known edge cases from [`partitioning-strategies.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#edge-cases-and-gotchas):
- Sequential insert hotspot — mitigate with prefix hashing or compound partition keys
- Empty ranges after bulk delete — need merge policy to consolidate
- Split during compaction — compaction must be range-aware or operate below the partition layer (TiKV model)

Known edge cases from [`vector-search-partitioning.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#edge-cases-and-gotchas):
- Partition split triggers vector index rebuild — CrackIVF (deferred construction) mitigates startup cost
- Per-partition HNSW with very small partitions may have insufficient graph density
- IVF centroid staleness after data distribution shift — periodic re-clustering needed

Enhancements from recent papers (VLDB/SIGMOD 2025):
- **SIEVE**: Build multiple small sub-indexes per partition for common filter patterns (8x speedup, <2.15x memory)
- **CrackIVF**: Defer vector index construction on new/cold partitions (10-1000x faster startup)

Full implementation detail: [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md)
Code scaffold: [`partitioning-strategies.md#code-skeleton`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md#code-skeleton) and [`vector-search-partitioning.md#code-skeleton`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md#code-skeleton)

## What This Decision Does NOT Solve
- Replication protocol (Raft/Paxos per partition) — separate decision needed
- Cross-partition transaction coordination (2PC, Calvin, Percolator) — separate decision
- Vector query optimization at 1000+ partitions (needs partition-level metadata for skip logic)
- Sequential insert hotspot mitigation (needs compound partition key design)
- Partition-aware compaction strategy (interaction between range splits and SpookyCompactor)

## Conditions for Revision
This ADR should be re-evaluated if:
- Partition count exceeds 100 and vector query latency becomes unacceptable
- A pure-vector workload emerges that dominates mixed queries (would favour global IVF)
- The library evolves from composable components to a managed service (would enable disaggregated node types)
- New research on graph-aware distributed HNSW eliminates the >80% cross-node traversal cost
- Review at 6-month mark (2026-09-16) regardless

---
*Confirmed by: user deliberation | Date: 2026-03-16*
*Full scoring: [evaluation.md](evaluation.md)*

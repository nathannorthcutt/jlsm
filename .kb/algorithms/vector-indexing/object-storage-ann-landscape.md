---
title: "Object Storage ANN — Landscape Summary and Recommendations"
aliases: ["remote-storage-ann", "s3-vector-search", "cloud-native-ann"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "object-storage", "s3", "architecture", "survey", "filtered-search"]
complexity:
  time_build: "varies by algorithm"
  time_query: "varies by algorithm"
  space: "varies by algorithm"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2601.01937"
    title: "Vector Search for the Future: Memory-Resident to Cloud-Native (Jan 2026)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/abs/2511.14748"
    title: "Cloud-Native Vector Search: Comprehensive Performance Analysis (Nov 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://aws.amazon.com/s3/features/vectors/"
    title: "Amazon S3 Vectors feature page"
    accessed: "2026-03-30"
    type: "docs"
  - url: "https://www.usenix.org/conference/fast26/presentation/guo"
    title: "OdinANN: Direct Insert for Consistently Stable Performance (FAST 2026)"
    accessed: "2026-03-30"
    type: "paper"
---

# Object Storage ANN — Landscape Summary

## summary

This file synthesizes 2025-2026 research on ANN algorithms suitable for object storage
(S3, GCS) with filtered search support. The consensus finding: **partition-based indexes
(SPANN/IVF-family) are the only current architecture that maps naturally to object storage
access patterns.** Graph-based indexes (HNSW, DiskANN) suffer 5-10x latency degradation
on remote storage due to sequential random-read dependency. Filtered search adds a second
axis — the best approach depends on the intersection of storage tier and predicate
complexity.

## decision-matrix

### by storage tier

| Storage Tier | Recommended Approach | Why |
|--------------|---------------------|-----|
| In-memory | ACORN, SIEVE, Compass | Full random access; rich predicate support |
| Local SSD | Filtered-DiskANN, SPANN | Co-located layout or sequential posting reads |
| Remote SSD (NVMe-oF) | SPFresh | LIRE rebalancing, direct NVMe via SPDK |
| Object storage (S3/GCS) | SPANN-adapted | Posting lists as objects; parallel batch GET |

### by predicate complexity

| Predicate Type | Best Algorithm | Runner-up |
|----------------|---------------|-----------|
| None (pure ANN) | SPANN/HNSW | DiskANN |
| Equality, <1K labels | Filtered-DiskANN | CAPS |
| Equality, high cardinality | ACORN | SIEVE |
| Range predicates | Compass | ACORN |
| Complex (AND/OR/range) | Compass | ACORN |
| Arbitrary/unknown | ACORN | SIEVE (with workload) |

### combined: object storage + filtered search

| Predicate Type | Recommended for Object Storage | Adaptation |
|----------------|-------------------------------|------------|
| Equality on metadata | SPANN + inline posting filter | Co-locate metadata in posting lists |
| Range on numeric attr | SPANN + secondary B+-tree index | B+-tree on centroids or per-partition |
| Complex predicates | SPANN + Compass-style adaptive | Fallback to brute-force on fetched postings |

## why-graph-indexes-fail-on-object-storage

Graph traversal is inherently sequential: each hop depends on the previous hop's result.
On S3 (50-200ms per GET), a 10-hop traversal takes 500ms-2s even with perfect caching.
Two-hop expansion (ACORN) doubles this. The Nov 2025 cloud-native analysis confirmed
network I/O is the dominant bottleneck, not CPU.

**Partition-based wins because:**
1. Centroid search happens entirely in memory (sub-millisecond)
2. Posting list fetches are independent and parallelizable (nprobe concurrent GETs)
3. Each posting list is a contiguous block — perfect for S3 byte-range reads
4. Metadata co-located in postings enables single-pass filtered scan

## adaptation-pattern-for-jlsm

Given jlsm's LSM-tree architecture and existing IvfFlat/Hnsw implementations:

1. **IVF-based approach maps to LSM naturally:**
   - Posting lists ≈ sorted runs in an SSTable level
   - Centroid index ≈ in-memory metadata (like bloom filters per SSTable)
   - Insert buffer ≈ MemTable (recent vectors before flush)
   - Compaction ≈ LIRE-style split/merge/reassign of postings

2. **Filtered search integration:**
   - Co-locate metadata fields in posting list entries (alongside vectors)
   - For equality filters: inline scan during posting list read
   - For range filters: per-posting-list min/max metadata for skip optimization
   - For complex predicates: evaluate during candidate scan (post-fetch, pre-rank)

3. **Object storage compatibility:**
   - Each posting list → one S3 object (or block with byte-range reads)
   - WAL for recent inserts (in-memory/local), periodic compaction to S3
   - Immutable posting objects fit S3 write-once semantics

## industry-signals-2025-2026

- **Amazon S3 Vectors (GA Dec 2025):** native ANN in S3, up to 2B vectors, sub-100ms
  latency. Architecture is proprietary but confirms partition-based approach at scale.
- **Jan 2026 survey:** explicitly recommends partition-based for remote storage.
- **Nov 2025 analysis:** quantifies 5-10x latency penalty for graph-based on cloud storage.
- **OdinANN (FAST 2026):** even the latest graph-based research (direct insert) still
  requires random access — not S3-compatible.
- **LEANN (ICML 2025):** storage-efficient via embedding recomputation, but focused on
  reducing local storage size, not remote access patterns.

## sources

1. [Vector Search for the Future (Jan 2026)](https://arxiv.org/html/2601.01937) — comprehensive survey
2. [Cloud-Native Performance Analysis (Nov 2025)](https://arxiv.org/abs/2511.14748) — quantitative comparison
3. [Amazon S3 Vectors](https://aws.amazon.com/s3/features/vectors/) — production validation
4. [OdinANN (FAST 2026)](https://www.usenix.org/conference/fast26/presentation/guo) — latest graph-based research

## Updates 2026-04-13

### HAKES — Disaggregated Filter-Refine (VLDB 2025)

HAKES (arXiv:2505.12524, PVLDB Vol 18 No 9) introduces a disaggregated two-stage
architecture for scalable vector search that directly challenges the monolithic
index-per-node model used by Milvus and similar systems.

**Key contributions:**
- Explicit filter-refine pipeline: a fast filter stage uses highly compressed vectors
  (binary/scalar quantized) to identify candidates, then a refine stage re-ranks with
  full-precision vectors to recover recall. Stages can run on separate node pools.
- IVF-based sharding for the filter stage: partitions distribute naturally across
  stateless filter nodes, enabling horizontal scaling without graph connectivity issues.
- Concurrent read-write support without the contention that plagues graph-based indexes.
- 16x higher throughput than graph-based baselines at comparable recall.

**Impact on the decision matrix:** HAKES validates that IVF + compressed-vector filter
is production-viable at scale. It strengthens the "partition-based for remote storage"
recommendation: the filter stage's compressed vectors fit in memory even when full
vectors live on object storage. The disaggregated design maps well to cloud-native
deployments where filter and refine pools scale independently.

**Implications for jlsm-vector:**
- The filter-refine pattern fits naturally into IvfFlat: quantized centroids + compressed
  posting summaries for filtering, full-precision posting lists on S3 for refinement.
- Pseudocode sketch for a two-stage query:

```
def query_filter_refine(q, k, nprobe, rerank_factor):
    # Stage 1: filter with compressed vectors (in-memory)
    candidates = ivf_search_compressed(q, nprobe, k * rerank_factor)
    # Stage 2: refine with full-precision vectors (from S3/disk)
    full_vectors = fetch_full_precision(candidates)
    return rerank(q, full_vectors, k)
```

### CrackIVF — Deferred Adaptive Index Construction (arXiv 2025)

CrackIVF (arXiv:2503.01823) eliminates upfront index construction by building
partitions incrementally as queries arrive — inspired by database cracking.

**Key contributions:**
- 10-1000x faster initialization: begins answering queries immediately with a minimal
  index; no k-means clustering or full-dataset scan required at startup.
- Progressive adaptation: partitions refine toward the query workload over time,
  eventually converging to quality comparable to eagerly-built IVF.
- Serves over 1 million queries before traditional approaches finish building their index.
- Targets "embedding data lakes" where pre-indexing all datasets is impractical —
  cold or infrequently accessed vector collections.

**Impact on the decision matrix:** CrackIVF fills a gap in the object storage
landscape: what to do when a dataset is too large or too cold to justify upfront
indexing. For jlsm, this is the compaction-deferred scenario — vectors flushed to
S3 can be queried via brute-force scan initially, with partition structure emerging
as query patterns stabilize.

**Implications for jlsm-vector:**
- Natural fit for the LSM insert buffer model: new vectors in MemTable are unindexed;
  CrackIVF-style deferred partitioning replaces the current "flush then index" step.
- Cold SSTable levels on S3 could remain lightly-indexed until queries target them.
- Complements LIRE (incremental-partition-maintenance.md): LIRE maintains existing
  partitions; CrackIVF creates them on demand.

### Updated Source List

5. [HAKES (PVLDB 2025)](https://arxiv.org/abs/2505.12524) — disaggregated filter-refine
6. [CrackIVF (arXiv 2025)](https://arxiv.org/abs/2503.01823) — deferred adaptive construction

---
*Researched: 2026-03-30 | Updated: 2026-04-13 | Next review: 2026-09-30*

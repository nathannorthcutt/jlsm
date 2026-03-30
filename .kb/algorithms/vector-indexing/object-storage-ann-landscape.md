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

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

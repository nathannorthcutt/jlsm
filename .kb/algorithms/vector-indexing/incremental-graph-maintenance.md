---
title: "Incremental Graph Index Maintenance — Streaming Updates Without Full Rebuild"
aliases: ["graph-maintenance", "FreshDiskANN", "IP-DiskANN", "Wolverine", "Greator"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "graph-index", "incremental-update", "tombstone", "streaming", "maintenance"]
complexity:
  time_build: "varies — O(1) per insert to O(n) full merge"
  time_query: "same as base graph index + tombstone overhead"
  space: "1x-2x base index during maintenance"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/abs/2105.09613"
    title: "FreshDiskANN: Graph-Based ANN Index for Streaming Similarity Search"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/abs/2502.13826"
    title: "IP-DiskANN: In-Place Updates of a Graph Index (Feb 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://www.usenix.org/conference/fast26/presentation/guo"
    title: "OdinANN: Direct Insert (FAST 2026)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://doi.org/10.14778/3734839.3734860"
    title: "Wolverine: Monotonic Search Path Repair (VLDB 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2503.00402v2"
    title: "Greator: Topology-Aware Localized Update Strategy (Mar 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2407.07871v2"
    title: "Enhancing HNSW Index for Real-Time Updates (2024)"
    accessed: "2026-03-30"
    type: "paper"
---

# Incremental Graph Index Maintenance

## summary

Graph-based ANN indexes (HNSW, DiskANN/Vamana) degrade under insert/delete churn:
tombstones waste distance computations, deleted hub vertices fragment connectivity, and
unreachable points accumulate. Five approaches exist on a spectrum from batch merge
(FreshDiskANN) to fully incremental (OdinANN), with targeted repair strategies
(Wolverine, Greator) offering middle ground. Key insight: the singly-linked graph
structure makes deletion expensive because finding in-neighbors of a deleted vertex
requires either a full scan or a graph-based approximation.

## how-it-works

### approach-spectrum

```
Batch merge          In-place delete        Direct insert        Targeted repair
(FreshDiskANN)  -->  (IP-DiskANN)     -->  (OdinANN)      -->  (Wolverine/Greator)
High latency spikes  Moderate spikes        Stable latency       Path-level repair
2x memory during     1x memory              Over-provisioned     Localized I/O
Full graph rewrite   Local graph edits      Page-level writes    Affected pages only
```

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| R | Max out-degree | 32-128 | Higher = more edges to repair per delete |
| Tombstone ratio | Deleted / total vertices | 0-15% before rebuild | Higher = more wasted computation |
| Consolidation threshold | When to trigger merge/repair | 5-10% deletes | Frequency vs batch size tradeoff |
| Over-provision ratio | Extra page slots (OdinANN) | 1.5-2x | More = longer before page overflow |

## algorithm-steps

### freshdiskann-merge-based
1. **Insert:** append to mutable in-memory TempIndex (RW-TempIndex)
2. **Delete:** add to DeleteList (tombstone); vertex traversed but excluded from results
3. **Periodically:** promote RW-TempIndex → read-only RO-TempIndex; create fresh RW
4. **StreamingMerge (background):**
   a. Pass 1: process deletions — remove edges to deleted vertices, RobustPrune affected
   b. Pass 2: insert all RO-TempIndex vectors into main graph via greedy search + RobustPrune
5. **Search:** fan out across LTI + all TempIndexes, merge results
- **Cost:** 2x storage during merge; latency spikes from I/O contention

### ip-diskann-in-place (feb 2025)
1. **Insert:** greedy search finds position; connect + RobustPrune if over degree R
2. **Delete:** approximate in-neighbors via greedy search on the deleted vertex itself
3. **Repair:** connect discovered in-neighbors to new candidates (O(cR) per deletion)
4. **Lightweight consolidation:** clean dangling edges after deletion threshold reached
- **Key insight:** graph structure approximates in-neighbor discovery, eliminating full scan
- **Cost:** O(cR) per delete vs O(R²) for FreshDiskANN consolidation, O(R³) for HNSW

### odinann-direct-insert (fast 2026)
1. **Fixed-size records:** each disk page holds vector + neighbor list in fixed slots
2. **Over-provision:** allocate extra free slots per page (e.g., 3 pages for 9 records)
3. **Insert:** write directly into free slot; update neighbor lists in same page I/O
4. **Update combining:** multiple record updates to same page → single write operation
- **Cost:** bounded I/O per insert; no deferred work; <1ms search at billion scale

### wolverine-path-repair (vldb 2025)
1. **On deletion:** identify monotonic search paths disrupted by the deleted vertex
2. **Wolverine base:** add in-edges to deleted vertex's out-neighbors
3. **Wolverine+:** restrict to 2-hop neighbors (cheaper, minimal accuracy loss)
4. **Wolverine++:** sophisticated candidate selection within 2-hop, improves both accuracy and speed
- **Cost:** 3.6-4.8x insert throughput over FreshDiskANN; higher recall

### greator-localized-repair (mar 2025)
1. **Maintain lightweight topology** (3-21% of index) storing neighbor info without vectors
2. **Delete:** scan topology for affected vertices, load only affected pages, repair
3. **Insert:** greedy search for neighbors; cache reverse edges page-aware
4. **Patch:** merge reverse edges reading only affected pages
5. **ASNR (Adaptive Similar Neighbor Replacement):** 96% of deletions affect only 1
   neighbor per vertex — replace with most similar out-neighbor of deleted vertex, no pruning
- **Cost:** 2.47-6.45x higher update throughput than FreshDiskANN

## implementation-notes

### hnsw-specific-patterns
- `markDelete`: flag vertex, traverse for navigation but exclude from results
- `allow_replace_deleted`: reuse deleted slots for new inserts (prevents memory growth)
- **Unreachable points:** after ~3000 delete/reinsert cycles, 3-4% become unreachable
- **MN-RU algorithm:** mutual-neighbor replaced update, O(M²) per layer vs O(M³)
- **Entry point maintenance:** open problem — no reliable entry point recovery mechanism
- **Dual-index fallback:** maintain backup index for unreachable points; dualSearch both

### concurrency-models
- **HNSWlib:** per-node fine-grained locks for writes; reads unlocked (relaxed isolation)
- **OdinANN:** page-level atomic writes; concurrent reads consistent at record granularity
- **IP-DiskANN:** single-graph in-place; lightweight consolidation (no global lock)
- **Key insight:** approximate indexes tolerate relaxed isolation — slightly stale neighbor
  lists during concurrent update do not meaningfully affect recall

### edge-cases-and-gotchas
- FreshDiskANN StreamingMerge can take 10% of full rebuild time for 10% change
- Tombstone accumulation between consolidations degrades QPS (wasted distance computations)
- Unreachable point accumulation is steady and hard to detect without periodic sampling
- Entry point deletion in HNSW has no clean solution in the literature

## complexity-analysis

### per-operation-cost

| Operation | FreshDiskANN | IP-DiskANN | OdinANN | Wolverine |
|-----------|-------------|------------|---------|-----------|
| Insert | O(log n) search | O(log n) search | O(1) page write | N/A |
| Delete | O(1) tombstone | O(cR) repair | N/A | O(2-hop) repair |
| Consolidation | O(n) merge | O(threshold) | None | None |
| Memory during update | 2x | 1x | 1x + provision | 1x |

## tradeoffs

### strengths
- No full rebuild required for ongoing insert/delete workloads
- Range from batch (FreshDiskANN) to fully incremental (OdinANN) fits different latency needs
- Wolverine/Greator offer targeted repair without touching the full index
- OdinANN achieves <1ms search at billion scale with stable latency

### weaknesses
- All graph approaches still require random access (poor for object storage)
- Unreachable point problem has no perfect solution (accumulates over time)
- Entry point maintenance in HNSW remains an open problem
- Even incremental approaches eventually need full rebuild when quality degrades too far

### compared-to-alternatives
- vs [partition-based maintenance](incremental-partition-maintenance.md): graph maintenance is finer-grained but requires random access; partition maintenance uses sequential I/O
- vs full rebuild: 2-6x throughput improvement but eventual quality degradation

## practical-usage

### when-to-use
- Continuous insert/delete workload on graph-based index
- Latency-sensitive: cannot tolerate full rebuild downtime
- Dataset on SSD or in memory (not object storage)

### when-not-to-use
- Object storage backend (graph random access is prohibitive)
- Very high delete rate (>15% tombstone ratio) — full rebuild may be more efficient
- Need guaranteed recall bounds (incremental maintenance has no recall guarantees)

## sources

1. [FreshDiskANN](https://arxiv.org/abs/2105.09613) — batch merge baseline (deployed in Bing)
2. [IP-DiskANN (Feb 2025)](https://arxiv.org/abs/2502.13826) — in-place delete via graph approximation
3. [OdinANN (FAST 2026)](https://www.usenix.org/conference/fast26/presentation/guo) — direct insert, no merge
4. [Wolverine (VLDB 2025)](https://doi.org/10.14778/3734839.3734860) — monotonic path repair
5. [Greator (Mar 2025)](https://arxiv.org/html/2503.00402v2) — topology-aware localized repair
6. [HNSW real-time updates (2024)](https://arxiv.org/html/2407.07871v2) — unreachable points analysis

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

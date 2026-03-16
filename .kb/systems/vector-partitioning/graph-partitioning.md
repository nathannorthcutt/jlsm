---
title: "Graph-Aware Partitioning for HNSW Indexes"
aliases: ["graph-partitioning", "hnsw-sharding", "distributed-hnsw"]
topic: "systems"
category: "vector-partitioning"
tags: ["partitioning", "hnsw", "graph", "distributed", "proximity-graph"]
complexity:
  time_build: "O(N * log(N) * M * efConstruction) + O(N) partitioning"
  time_query: "O(log(N) * efSearch) routing + O(p * log(N/k) * efSearch) shard search"
  space: "O(N * M * d) base + routing index overhead"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://arxiv.org/abs/2403.01797"
    title: "Unleashing Graph Partitioning for Large-Scale Nearest Neighbor Search (GP-ANN)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/1603.09320"
    title: "Efficient and Robust ANN Search Using HNSW Graphs"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2410.14452"
    title: "SPFresh: Incremental In-Place Update for Billion-Scale Vector Search"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2505.16064"
    title: "Three Algorithms for Merging HNSW Graphs"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2506.14707"
    title: "HARMONY: Scalable Distributed Vector Database (SIGMOD 2025)"
    accessed: "2026-03-16"
    type: "paper"
---

# Graph-Aware Partitioning for HNSW Indexes

## summary

Partition strategies specifically designed for HNSW (Hierarchical Navigable Small World)
proximity graphs, where naive sharding destroys the graph's navigability property. The
core challenge: in a naively sharded 100M-vector HNSW, cross-node traversals constitute
>80% of search steps, each orders of magnitude slower than local reads. Solutions include
graph-preserving partitioning (GP-ANN), incremental rebalancing (SPFresh/LIRE), graph
merging algorithms, and multi-granularity partitioning (HARMONY). This is HNSW-specific
— the partitioning must account for graph edge connectivity, not just vector proximity.

## how-it-works

**The navigability problem:** HNSW relies on greedy traversal through connected graph
layers. Cutting edges between partitions breaks traversal paths, requiring cross-partition
hops (network round-trips in distributed settings).

**Three main approaches:**

1. **Graph partitioning + routing index (GP-ANN, VLDB 2024):** Build full HNSW, apply
   balanced graph partitioning (edge-cut via METIS/KaHIP), use the HNSW graph itself as
   a routing index on a coarsened pointset. All shards with visited centroids are probed.
   Result: 2.14x higher QPS at 90% recall@10 vs best competitors at billion scale.

2. **Incremental rebalancing (SPFresh/LIRE, SOSP 2023):** Split partitions when they
   exceed max length; only reassign boundary vectors violating nearest-partition-assignment.
   Uses 1% DRAM and <10% CPU vs full rebuild.

3. **Independent indexes + merge:** Build separate HNSW per partition, merge via IGTM
   algorithm (70% fewer distance computations than naive merge). Practical for
   LSM-compaction-style workflows.

**Edge-cut vs vertex-cut:** Edge-cut (METIS-style) is dominant for HNSW because vertex
replication (copying full d-dimensional vectors) is memory-prohibitive. Edge-cut assigns
vertices to partitions, accepting some cross-partition edges.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| k (partitions) | Number of graph partitions | 2-32 | More = smaller per-partition memory, more routing overhead |
| overlap_ratio | Points assigned to multiple partitions | 1-2 (DiskANN uses 2) | Higher = better cross-boundary recall, ~2x storage |
| nprobe | Partitions queried per search | 1-k | >90% recall@5 at 1B scale requires probing 4/5 partitions |
| M | HNSW max connections per layer | 16-64 | Higher = better recall, more cross-partition edges when partitioned |
| efSearch | Query beam width | 64-500 | Effect amplified across partitions |

## algorithm-steps

1. **Build HNSW:** Construct full HNSW graph on all vectors (or per-partition, if streaming)
2. **Partition graph (GP-ANN):** Apply balanced k-way graph partitioning (multilevel heuristic: coarsen → partition → refine) minimizing cut edges while maintaining balance
3. **Build routing index:** Construct HNSW on cluster centroids (or coarsened pointset) as a lightweight routing structure (~1-5% memory overhead)
4. **Query routing:** Traverse routing HNSW to identify candidate shards; fan out queries to selected shards; merge results
5. **Streaming insert:** New vector is inserted into its nearest partition's local HNSW graph (HNSW natively supports incremental insert)
6. **Rebalance (SPFresh LIRE):** When partition exceeds max size, split; identify boundary vectors (those closer to new centroid than original); reassign only boundary vectors

## implementation-notes

### data-structure-requirements
- Per-partition: independent HNSW graph with local navigability
- Routing index: small HNSW on partition centroids (k nodes)
- Cross-partition edges: either replicated boundary vectors or explicit edge references

### edge-cases-and-gotchas
- **Recall at boundaries:** Points near partition boundaries are poorly served — achieving >90% recall@5 at 1B scale requires probing 4 of 5 partitions
- **Hot partitions:** Coarse partitioning causes severe hotspot contention — hottest node may handle 80-100% of queries at billion scale
- **Merge inefficiency:** Weaviate explicitly notes "HNSW indexes cannot be merged efficiently" — the 2025 IGTM paper addresses this but merging remains more expensive than single-build
- **Deletions degrade navigability:** Orphaned nodes break greedy traversal; SPFresh LIRE protocol handles this
- **Balanced partitioning is NP-complete:** Practical solutions use multilevel heuristics (METIS)

## complexity-analysis

### build-phase
Single HNSW: O(N * log(N) * M * efConstruction). Graph partitioning: O(N) via multilevel heuristic. Total: dominated by HNSW construction. Per-partition (if built independently): O(N/k * log(N/k) * M * efConstruction).

### query-phase
Routing: O(log(k) * efSearch). Per-shard search: O(log(N/k) * efSearch). Total with p probes: O(log(k) * efSearch + p * log(N/k) * efSearch). Recall degrades significantly if p < k.

### memory-footprint
Base: O(N * M * d) for graph + vectors. With overlap factor i: O(N * i * M * d). Routing index: O(k * M_routing * d) — negligible. DiskANN variant: PQ-compressed vectors in memory, full vectors on disk.

## tradeoffs

### strengths
- Preserves HNSW navigability — graph structure informs partition boundaries
- Sub-linear query time within each partition
- HNSW natively supports incremental insert — streaming-compatible
- GP-ANN achieves 2.14x QPS improvement over naive sharding at billion scale
- SPFresh LIRE uses minimal resources for rebalancing (1% DRAM, <10% CPU)

### weaknesses
- HNSW-specific — not transferable to IVF or other index types
- Graph partitioning itself is computationally expensive (though one-time)
- Cross-partition boundary effects are fundamental — recall degrades at boundaries
- Merge algorithms exist but are costly compared to single-build
- Hot partition problem with skewed query distributions

### compared-to-alternatives
- vs [consistent-hashing](consistent-hashing.md): graph partitioning preserves navigability; LSH is agnostic but doesn't optimize for graph traversal
- vs [streaming-kmeans](streaming-kmeans.md): k-means creates Voronoi cells for IVF; graph partitioning creates edge-cut regions for HNSW — complementary, not competing
- vs [random-projection](random-projection.md): RP trees don't account for graph connectivity at all
- vs [quantization-aware](quantization-aware.md): different axis — graph partitioning is about topology, quantization-aware is about compression alignment

## current-research

### key-papers
1. Malkov & Yashunin, "Efficient and Robust ANN Search Using HNSW Graphs," IEEE TPAMI 2020
2. Gottesbueren et al., "Unleashing Graph Partitioning for Large-Scale NN Search," VLDB 2024
3. SPFresh, "Incremental In-Place Update for Billion-Scale Vector Search," SOSP 2023
4. "Three Algorithms for Merging HNSW Graphs," 2025
5. HARMONY, "Scalable Distributed Vector Database," SIGMOD 2025
6. d-HNSW, "Efficient Vector Search on Disaggregated Memory," HotStorage 2025

### active-research-directions
- RDMA-based distributed HNSW (d-HNSW, SHINE) — 117x latency improvement
- Multi-granularity partitioning (HARMONY) — dimension + vector based
- Merge algorithms that approach single-build quality (IGTM: 70% fewer distance computations)
- Incremental rebalancing without full rebuild (SPFresh LIRE)

## practical-usage

### when-to-use
- HNSW-based vector indexes at scale (>10M vectors)
- When graph navigability and recall are critical
- Distributed deployments requiring horizontal scaling
- When data arrives incrementally but periodic rebalancing is acceptable

### when-not-to-use
- IVF-based indexes (use streaming k-means instead)
- Small datasets that fit in single-node memory
- When partition decisions must be instant and data-independent (use LSH)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| GP-ANN | C++ | https://github.com/larsgottesbueren/gp-ann | Research |
| DiskANN | C++ | https://github.com/microsoft/DiskANN | Active |
| HARMONY | C++ | https://github.com/xuqianmamba/Harmony | Research |
| HNSW Merge | C++ | https://github.com/aponom84/merging-navigable-graphs | Research |
| hnswlib | C++ | https://github.com/nmslib/hnswlib | Active |
| Qdrant | Rust | https://github.com/qdrant/qdrant | Active |
| Milvus | Go/C++ | https://github.com/milvus-io/milvus | Active |

## code-skeleton

```java
class HnswPartitionManager {
    private final HnswIndex[] partitions;
    private final HnswIndex routingIndex;  // over centroids
    private final float[][] centroids;

    int assignPartition(float[] vector) {
        // find nearest centroid via routing index
        return routingIndex.searchNearest(vector, 1)[0];
    }

    void insert(float[] vector, long id) {
        int partition = assignPartition(vector);
        partitions[partition].add(vector, id);
        updateCentroid(partition, vector);
    }

    long[] query(float[] query, int topK, int nprobe) {
        int[] shards = routingIndex.searchNearest(query, nprobe);
        PriorityQueue<Result> merged = new PriorityQueue<>();
        for (int shard : shards)
            merged.addAll(partitions[shard].search(query, topK));
        return topK(merged, topK);
    }

    void rebalance(int partition) {
        // SPFresh LIRE: split if oversized, reassign boundary vectors only
    }
}
```

## sources

1. [GP-ANN (VLDB 2024)](https://arxiv.org/abs/2403.01797) — 2.14x QPS at 90% recall via graph partitioning + HNSW routing
2. [SPFresh (SOSP 2023)](https://arxiv.org/abs/2410.14452) — LIRE protocol for incremental rebalancing at 1% DRAM
3. [HNSW Merge Algorithms (2025)](https://arxiv.org/abs/2505.16064) — IGTM: 70% fewer distance computations than naive merge
4. [HARMONY (SIGMOD 2025)](https://arxiv.org/abs/2506.14707) — 4.63x throughput via multi-granularity partitioning

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

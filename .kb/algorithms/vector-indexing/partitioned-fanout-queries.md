---
title: "Partitioned Fanout Query Optimization for Vector Indices"
aliases: ["fanout queries", "partition pruning", "distributed ANN", "scatter-gather vector search"]
topic: "algorithms"
category: "vector-indexing"
tags: ["HNSW", "IVF-FLAT", "partition-pruning", "fanout", "adaptive-nprobe", "scatter-gather"]
complexity:
  time_build: "O(n log n) partitioning + O(n·M) graph/index construction per partition"
  time_query: "O(P · cost_per_partition) where P = partitions probed"
  space: "O(n·d + routing_index) across all partitions"
research_status: "active"
last_researched: "2026-03-27"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/KeyRange.java"
sources:
  - url: "https://arxiv.org/html/2503.23409"
    title: "LIRA: A Learning-based Query-aware Partition Framework for Large-scale ANN Search"
    accessed: "2026-03-27"
    type: paper
  - url: "https://arxiv.org/html/2507.17647v1"
    title: "SHINE: A Scalable HNSW Index in Disaggregated Memory"
    accessed: "2026-03-27"
    type: paper
  - url: "https://arxiv.org/html/2506.03437v1"
    title: "Quake: Adaptive Indexing for Vector Search (OSDI'25)"
    accessed: "2026-03-27"
    type: paper
  - url: "https://arxiv.org/abs/2403.01797"
    title: "Unleashing Graph Partitioning for Large-Scale Nearest Neighbor Search"
    accessed: "2026-03-27"
    type: paper
  - url: "https://milvus.io/blog/understanding-ivf-vector-index-how-It-works-and-when-to-choose-it-over-hnsw.md"
    title: "How to Choose Between IVF and HNSW for ANN Vector Search"
    accessed: "2026-03-27"
    type: blog
    note: "(not fetched — timeout)"
---

# Partitioned Fanout Query Optimization for Vector Indices

## summary

When a vector index is too large for a single node or is naturally partitioned
(e.g., by LSM level, by tenant, or by shard), queries must fan out across
multiple partitions and merge results. Naive fanout (probe all partitions)
wastes compute; the core optimization problem is **selecting the minimum set
of partitions that preserves recall** and **merging partial top-k results
efficiently**. Three families of approach dominate: geometric/centroid-based
pruning (classic IVF nprobe), learned routing (LIRA), and adaptive cost-model
driven selection (Quake APS). For HNSW, the challenge shifts to graph
partitioning strategies that keep neighbor sets co-located (SHINE, balanced GP).

## how-it-works

### partition-strategies

**IVF-native partitioning** — k-means on the full dataset produces B centroids;
each vector is assigned to its nearest centroid's partition (Voronoi cell). At
query time, compute distance to all B centroids, rank, and probe the top-nprobe
partitions. Simple but nprobe is global and static — suboptimal for queries
near partition boundaries ("long-tail" queries).

**Graph-based partitioning (HNSW)** — balanced graph partitioning on the HNSW
proximity graph keeps nearest neighbors in the same shard. A lightweight routing
index (e.g., upper HNSW levels or centroid index) selects which shards to probe.
Achieves 1.19–2.14× higher QPS than random/geometric partitioning at 90%
recall@10 on billion-scale datasets.

**Logical partitioning (SHINE)** — the global HNSW graph is built once, then
logically partitioned via balanced k-means on upper-level nodes. No data is
moved; the graph structure (all edges) is preserved. Each compute node caches
its partition's vectors. Query routing uses centroid proximity with three
policies: best-fit, balanced, and adaptive (queue-length-aware).

### query-routing-mechanisms

| Mechanism | How it selects partitions | Per-query adaptive? |
|-----------|--------------------------|---------------------|
| Static nprobe | Fixed top-P by centroid distance | No |
| LIRA learned router | Neural net predicts partition membership probability | Yes (threshold σ) |
| Quake APS | Geometric recall estimation via hyperspherical caps | Yes (target τ_R) |
| SHINE adaptive | Centroid distance + queue-length balancing | Partially (load-aware) |
| Graph-partition routing | HNSW upper-level traversal identifies visited shards | Yes (traversal-driven) |

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| nprobe (static) | Partitions probed per query | 1–64 | Higher = better recall, linear latency cost |
| τ_R (Quake) | Target recall threshold for APS | 0.85–0.99 | Higher = more partitions probed |
| σ (LIRA) | Probability threshold for partition selection | 0.1–1.0 | Lower = more partitions, better recall |
| B (IVF) | Number of partitions / centroids | √n to 16√n | More = finer granularity, higher centroid cost |
| η (LIRA) | Redundancy percentage for long-tail points | 3–100% | Higher = better recall, more storage |

## algorithm-steps

### adaptive-partition-selection (Quake APS)

1. **Compute centroid distances**: rank all B partition centroids by distance to query q
2. **Scan nearest partition**: execute ANN search in partition P₀, obtain initial k-NN
   candidates with radius ρ = distance to k-th nearest neighbor
3. **Estimate recall per remaining partition**: for each partition Pᵢ, compute
   probability pᵢ = Vol(B(q,ρ) ∩ Pᵢ) / Vol(B(q,ρ)) using hyperspherical cap
   approximation (regularized incomplete beta function)
4. **Iteratively scan**: select partition with highest pᵢ, scan it, update
   candidates and ρ. If ρ shrinks beyond threshold τ_ρ, recompute all pᵢ
5. **Terminate**: when cumulative recall r = Σ(pᵢ for scanned partitions) ≥ τ_R

### learned-routing (LIRA)

1. **Encode query**: x_q = φ_q(q) via query network
2. **Encode centroid distances**: x_I = φ_I(distances) via centroid network
3. **Predict partition probabilities**: p̂ = φ_p(x_q ⊕ x_I) via prediction network
4. **Select partitions**: probe all Pᵢ where p̂ᵢ > σ
5. **Merge results**: standard top-k merge across probed partition results

### scatter-gather-merge

1. **Fan out**: dispatch query to selected partitions (parallel execution)
2. **Local search**: each partition runs its local index (HNSW graph traversal
   or IVF flat scan) and returns local top-k candidates with distances
3. **Merge**: priority-queue merge of all partial results, retain global top-k
4. **Optional re-rank**: if using approximate distances (PQ), re-rank final
   candidates with exact distance computation

## implementation-notes

### data-structure-requirements

- **Partition metadata**: centroid vectors (B × d floats), partition sizes, access
  frequency counters (for cost-model approaches)
- **Routing index**: either a small HNSW graph over centroids, or a trained neural
  network (LIRA), or the APS geometric model (Quake)
- **Result merge buffer**: min-heap of size k for streaming merge across partitions

### edge-cases-and-gotchas

- **Boundary vectors**: queries equidistant from multiple centroids need higher
  nprobe. LIRA addresses this with learned redundancy (duplicating boundary
  vectors into multiple partitions at 3–100% overhead)
- **Empty partitions**: after deletions, some partitions may be empty — skip them
  in fanout to avoid wasted I/O
- **Stale centroids**: if data distribution drifts, centroid quality degrades.
  Quake handles this via cost-model-driven split/merge maintenance
- **Cross-node HNSW traversal**: in sharded HNSW, 80%+ of search steps may cross
  node boundaries — this is the primary argument for IVF over HNSW in
  distributed settings, or for SHINE's logical-partition approach

### jlsm-applicability

In jlsm's architecture, vector indices (IvfFlat, Hnsw in `jlsm-vector`) sit
atop LSM-tree storage where data is naturally partitioned by SSTable level and
by key range. Fanout optimization applies at two levels:

1. **Cross-level fanout**: query must search the active memtable + multiple SSTable
   levels; each level is a natural partition. APS-style pruning could skip levels
   unlikely to contain nearest neighbors (e.g., via per-level centroid summaries)
2. **Cross-shard fanout**: if the index is range-partitioned across nodes, each
   shard holds a subset of vectors. Centroid-based or learned routing selects shards

## complexity-analysis

### build-phase

- Centroid computation (k-means): O(n·B·d·iterations), typically B = √n
- LIRA model training: O(n·d) for ground-truth k-NN + network training epochs
- Graph partitioning (for HNSW): O(E·log(B)) where E = edges in proximity graph

### query-phase

- Centroid ranking: O(B·d) — dominates when B is large
- APS per-partition probability: O(B) regularized beta function evaluations
- Local search per partition: O((n/B)·d) for flat scan, O(log(n/B)·d·ef) for HNSW
- Result merge: O(P·k·log(k)) where P = partitions probed

### memory-footprint

- Centroids: B × d × 4 bytes (float32)
- LIRA model: ~100KB–1MB depending on architecture
- APS state: O(B) per query (centroid distances + probabilities)

## tradeoffs

### strengths

- **APS (Quake)**: no training required, adapts per-query, matches oracle nprobe
  within 17% latency overhead; handles dynamic workloads with cost-model maintenance
- **LIRA**: 30–35% fewer distance computations than static nprobe at equivalent
  recall; strongest gains on hard/boundary queries and large k values
- **SHINE logical partitioning**: preserves exact single-machine HNSW accuracy;
  no graph structure modification; 2.3× throughput on skewed workloads
- **Balanced GP**: 2.14× QPS over random partitioning at billion scale

### weaknesses

- **APS**: hyperspherical cap approximation assumes uniform density within
  partitions — breaks down for highly clustered data
- **LIRA**: requires ground-truth k-NN computation for training (O(n²·d) worst
  case); model must be retrained when data distribution shifts
- **SHINE**: requires RDMA infrastructure; compute nodes need enough memory to
  cache their partition (~10GB per node in experiments)
- **Balanced GP**: partitioning step is expensive at billion scale; routing index
  adds memory overhead

### compared-to-alternatives

- Static nprobe is the simplest baseline — works well when query distribution is
  uniform and data is well-clustered, but wastes 30–50% of scans on easy queries
- For **read-heavy, static datasets**: graph partitioning + HNSW routing gives
  best throughput
- For **dynamic, write-heavy workloads**: Quake's cost-model adaptation is
  superior (8× faster than Faiss-IVF on Wikipedia-12M)
- For **highest recall with minimal tuning**: SHINE's accuracy-preserving approach
  avoids the recall-efficiency tradeoff entirely

## current-research

### key-papers

- Zhang, T. et al. (2025). "LIRA: A Learning-based Query-aware Partition
  Framework for Large-scale ANN Search." arXiv:2503.23409.
- Mohoney, J. et al. (2025). "Quake: Adaptive Indexing for Vector Search."
  OSDI'25, USENIX. arXiv:2506.03437.
- Balder, F. et al. (2025). "SHINE: A Scalable HNSW Index in Disaggregated
  Memory." arXiv:2507.17647.
- Gottesbüren, L. et al. (2024). "Unleashing Graph Partitioning for Large-Scale
  Nearest Neighbor Search." arXiv:2403.01797.

### active-research-directions

- **Learned routing without ground-truth**: reducing LIRA's training cost by using
  approximate labels or self-supervised objectives
- **Hierarchical adaptive partitioning**: Quake's multi-level cost model extends
  to tree-structured partition hierarchies
- **Hardware-aware fanout**: SHINE's RDMA-based approach points toward CXL and
  disaggregated memory as enabling technologies for distributed HNSW
- **Hybrid IVF+HNSW**: using IVF for partition selection and HNSW as the
  intra-partition index (supported in LanceDB, Milvus)

## practical-usage

### when-to-use

- Dataset exceeds single-node memory or is naturally sharded/partitioned
- Query latency budget requires probing fewer than all partitions
- Workload has skew (hot partitions) that static nprobe handles poorly
- LSM-tree storage where data spans multiple levels/SSTables

### when-not-to-use

- Dataset fits in memory on one node — single HNSW graph is simpler and faster
- Recall requirements are 100% (exact search) — must probe all partitions anyway
- Very small partition count (B < 8) — overhead of routing exceeds savings
- Write-once, read-many with uniform access — static nprobe is sufficient

## reference-implementations

| Library | Language | URL | Approach |
|---------|----------|-----|----------|
| Quake | C++/Python | github (OSDI'25 artifact) | APS + cost-model maintenance |
| LIRA | Python | github (arXiv artifact) | Learned routing + redundancy |
| SHINE | C++ | github.com/DatabaseGroup/shine-hnsw-index | Logical HNSW partitioning |
| Faiss | C++/Python | github.com/facebookresearch/faiss | IVF baseline + nprobe |
| Milvus | Go/C++ | github.com/milvus-io/milvus | IVF + HNSW hybrid |

## code-skeleton

```java
/**
 * Adaptive partition selection for fanout vector queries.
 * Probes minimum partitions needed to meet recall target.
 */
class AdaptivePartitionSelector {
    private final float[][] centroids;   // B x d
    private final float targetRecall;    // τ_R

    AdaptivePartitionSelector(float[][] centroids, float targetRecall) { ... }

    /** Returns ordered list of partition indices to probe. */
    List<Integer> selectPartitions(float[] query, int k) {
        // 1. Rank centroids by distance to query
        float[] distances = computeCentroidDistances(query);
        int[] ranked = argsort(distances);

        // 2. Scan nearest partition, get initial radius
        List<Integer> selected = new ArrayList<>();
        selected.add(ranked[0]);
        float radius = scanAndGetKthDistance(ranked[0], query, k);

        // 3. Iteratively add partitions until recall target met
        float cumulativeRecall = estimateRecall(ranked[0], query, radius);
        for (int i = 1; i < ranked.length && cumulativeRecall < targetRecall; i++) {
            float prob = estimatePartitionProbability(ranked[i], query, radius);
            if (prob > 0.01f) {
                selected.add(ranked[i]);
                cumulativeRecall += prob;
            }
        }
        return selected;
    }

    /** Hyperspherical cap probability estimation. */
    private float estimatePartitionProbability(int partition, float[] query, float radius) {
        float centroidDist = distance(query, centroids[partition]);
        // Regularized incomplete beta function for cap volume ratio
        return hypersphericalCapVolume(radius, centroidDist, query.length);
    }
}
```

## sources

1. [LIRA (arXiv:2503.23409)](https://arxiv.org/html/2503.23409) — learned query-aware
   partition routing; 31% nprobe reduction at 98% recall on SIFT1M
2. [SHINE (arXiv:2507.17647)](https://arxiv.org/html/2507.17647v1) — logical HNSW
   partitioning preserving single-machine accuracy; 2.3× throughput on skewed workloads
3. [Quake (OSDI'25, arXiv:2506.03437)](https://arxiv.org/html/2506.03437v1) —
   adaptive partition selection via geometric recall estimation; 8× faster than
   Faiss-IVF on dynamic workloads
4. [Graph Partitioning for ANN (arXiv:2403.01797)](https://arxiv.org/abs/2403.01797) —
   balanced graph partitioning achieves 2.14× QPS over random sharding at billion scale

---
*Researched: 2026-03-27 | Next review: 2026-09-27*

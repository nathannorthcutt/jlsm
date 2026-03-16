---
title: "Streaming K-Means for IVF Partitioning"
aliases: ["streaming-kmeans", "online-kmeans", "mini-batch-kmeans", "ivf-partitioning"]
topic: "systems"
category: "vector-partitioning"
tags: ["partitioning", "ivf", "clustering", "streaming", "online-learning"]
complexity:
  time_build: "O(n * k * d) amortized"
  time_query: "O(k * d + nprobe * n/k * d)"
  space: "O(k * d) centroids + O(n * d) on disk"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://dl.acm.org/doi/10.1145/1772690.1772862"
    title: "Web-Scale K-Means Clustering (Mini-Batch K-Means)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://epubs.siam.org/doi/10.1137/1.9781611974317.7"
    title: "An Algorithm for Online K-Means Clustering"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://papers.nips.cc/paper/2011/hash/2b6d65b9a9445c4271ab9076ead5605a-Abstract.html"
    title: "Fast and Accurate k-means For Large Datasets (Streaming K-Means)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://github.com/facebookresearch/faiss"
    title: "FAISS - Facebook AI Similarity Search"
    accessed: "2026-03-16"
    type: "repo"
  - url: "https://arxiv.org/abs/1702.08734"
    title: "Billion-scale similarity search with GPUs (Faiss)"
    accessed: "2026-03-16"
    type: "paper"
---

# Streaming K-Means for IVF Partitioning

## summary

Online clustering approach for creating IVF (Inverted File) partitions incrementally
without requiring the full dataset upfront. Centroids are learned from a stream of vectors
using mini-batch k-means, online k-means++, or streaming k-means algorithms. As vectors
arrive, they are assigned to the nearest centroid and appended to that partition's inverted
list. Centroids drift via exponential moving average updates, adapting to distribution
changes. Partition splitting/merging during LSM compaction maintains balance. This is
IVF-optimized — the partitioning directly produces Voronoi cells used for search.
No major vector database currently does true streaming centroid training; all use
batch-train-then-serve, making an LSM-backed streaming IVF novel.

## how-it-works

**Core loop:** For each incoming vector v: (1) find nearest centroid c_i among k centroids,
(2) assign v to partition i, (3) update centroid: c_i = (1 - eta) * c_i + eta * v where
eta = 1 / (1 + count_i).

**IVF integration:** Inverted lists (posting lists) are append-only — naturally aligned
with LSM-tree storage. Each SSTable stores one or more partition's vectors. The centroid
table (k * d floats) is the only required in-memory structure.

**Three algorithm variants:**

| Variant | Guarantee | Memory | Best For |
|---------|-----------|--------|----------|
| Mini-batch k-means (Sculley 2010) | Heuristic | O(k*d + b*d) | Most practical; used by Faiss |
| Online k-means++ (Liberty 2016) | O(log k)-competitive | O(k*d*log n) | Initialization phase |
| Streaming k-means (Shindler 2011) | O(1)-competitive | O(k*d) | When k is unknown |

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| k (nlist) | Number of partitions | sqrt(N) to 4*sqrt(N) | More = faster queries, more centroid memory |
| batch_size | Mini-batch size | 256-10,000 | Larger = more stable, higher latency |
| learning_rate | Centroid update rate | 1/count or 0.001-0.01 | Higher = faster drift adaptation, more jitter |
| nprobe | Partitions searched | 1 to k/4 | More = higher recall, slower queries |
| split_threshold | Max partition size | 2x mean size | Lower = more balanced, more splits |
| merge_threshold | Min partition size | 0.25x mean size | Prevents tiny partitions |

## algorithm-steps

1. **Seed centroids:** Reservoir-sample first W vectors; run k-means++ on the sample to produce k initial centroids
2. **Streaming insert(v):** Find nearest centroid c_i (O(k*d)); assign v to partition i; update c_i via EMA
3. **Batch update (mini-batch variant):** Accumulate b vectors; for each, assign to nearest centroid; batch-update all affected centroids
4. **Split check:** If |partition_i| > 2 * (N/k), run 2-means on partition_i to produce two new centroids; old partition marked for lazy migration
5. **Merge check:** If |partition_i| < 0.25 * (N/k), merge with nearest neighbor partition
6. **Compaction reassignment:** During LSM compaction, reassign vectors from split/stale partitions to their correct new centroids (piggybacks on compaction I/O)
7. **Query(q, top_k):** Find nprobe nearest centroids; scan those partitions' posting lists; return top_k

## implementation-notes

### data-structure-requirements
- Centroid table: k * d floats in memory (e.g., k=4096, d=128 → 2 MiB)
- Per-centroid metadata: count, variance statistics (O(k) additional)
- Inverted lists: on-disk in SSTables, append-only
- Optional: PQ-compressed centroids reduce memory (k*d floats → k*(d/m) bytes)

### edge-cases-and-gotchas
- **Cold start:** First queries have poor partition quality until enough vectors establish centroids
- **Concept drift:** Data distribution shifts over time; windowed learning rate or periodic full retrain helps
- **Cluster imbalance:** Some centroids attract far more points; causes hot partitions and slow queries
- **High dimensionality:** k-means quality degrades above ~1000 dims (curse of dimensionality)
- **Deletions:** Removing vectors doesn't update centroids; stale centroids handled during compaction
- **Empty partitions:** After drift, some partitions may have zero vectors; merge or reassign

## complexity-analysis

### build-phase
Per-point: O(k * d) for nearest centroid + O(d) for centroid update.
Total: O(n * k * d) for assignment across all vectors.
With mini-batch: O(n * k * d / b) iterations.

### query-phase
Centroid scan: O(k * d). Partition scan: O(nprobe * (n/k) * d).
Total: O(k * d + nprobe * n/k * d). With PQ lookup tables: partition scan becomes O(nprobe * n/k * M) lookup-adds.

### memory-footprint
Centroids: O(k * d) — fixed, small (2 MiB typical). Inverted lists: O(n * d) on disk.
Per-partition memory bounded by disk flush threshold, not centroid storage.

## tradeoffs

### strengths
- Adapts to data distribution — centroids track where vectors actually cluster
- Naturally handles growing datasets without full retraining
- Inverted list append-only pattern aligns with LSM-tree writes
- Reassignment during compaction amortizes cost — no extra I/O pass
- Well-understood algorithms with extensive production use (Faiss, ScaNN)

### weaknesses
- Requires a training phase (even if streaming) — centroids must stabilize before good recall
- Cold-start problem — early queries perform poorly
- Centroid quality may be worse than batch k-means, especially with few points
- No formal recall guarantees (recall depends on centroid quality)
- IVF-specific — partitioning is tightly coupled to the IVF search paradigm

### compared-to-alternatives
- vs [consistent-hashing](consistent-hashing.md): k-means adapts to data; LSH is data-independent. k-means has better recall but requires stabilization.
- vs [random-projection](random-projection.md): k-means produces tighter partitions; RP trees are simpler but don't adapt
- vs [graph-partitioning](graph-partitioning.md): k-means is for IVF; graph partitioning is for HNSW — different paradigms
- vs [quantization-aware](quantization-aware.md): streaming k-means handles the coarse quantizer; quantization-aware adds fine PQ alignment

## current-research

### key-papers
1. Sculley, "Web-Scale K-Means Clustering," WWW 2010 — mini-batch k-means
2. Liberty et al., "An Algorithm for Online K-Means Clustering," ALENEX 2016 — online k-means++
3. Shindler et al., "Fast and Accurate k-means For Large Datasets," NeurIPS 2011 — O(1)-competitive streaming
4. Ackermann et al., "StreamKM++: A Clustering Algorithm for Data Streams," ACM JEA 2012 — coreset approach
5. Johnson et al., "Billion-scale similarity search with GPUs," IEEE TBD 2019 — Faiss IVF training

### active-research-directions
- True streaming IVF (no batch retraining) — no major system implements this yet
- Adaptive split/merge during LSM compaction — unexplored in literature
- Concept-drift-aware centroid adaptation with formal recall bounds

## practical-usage

### when-to-use
- IVF-based vector indexes with streaming data ingestion
- When data distribution is non-uniform and centroids should adapt
- When partition quality (recall) matters more than partition simplicity
- LSM-tree backed storage where compaction can piggyback reassignment

### when-not-to-use
- When no training/stabilization period is acceptable (use LSH instead)
- HNSW or graph-based indexes (use graph partitioning instead)
- Very high dimensional data (d > 1000) where k-means degrades

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| scikit-learn MiniBatchKMeans | Python | https://scikit-learn.org/stable/modules/generated/sklearn.cluster.MiniBatchKMeans.html | Active |
| River KMeans | Python | https://riverml.xyz/latest/api/cluster/KMeans/ | Active |
| FAISS IVF | C++/Python | https://github.com/facebookresearch/faiss | Active |
| Apache Flink ML KMeans | Java | https://nightlies.apache.org/flink/flink-ml-docs-stable/ | Active |
| Smile KMeans | Java/Scala | https://haifengl.github.io/clustering.html | Active |

## code-skeleton

```java
class StreamingIvfPartitioner {
    private final float[][] centroids;  // [k][d]
    private final int[] counts;         // [k]
    private final int k, d;

    void insert(float[] vector) {
        int nearest = findNearestCentroid(vector);
        counts[nearest]++;
        float eta = 1.0f / counts[nearest];
        for (int i = 0; i < d; i++)
            centroids[nearest][i] += eta * (vector[i] - centroids[nearest][i]);
        // append vector to partition[nearest] posting list
    }

    int findNearestCentroid(float[] vector) {
        int best = 0; float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < k; c++) {
            float dist = l2Distance(centroids[c], vector);
            if (dist < bestDist) { bestDist = dist; best = c; }
        }
        return best;
    }

    int[] queryPartitions(float[] query, int nprobe) {
        // return indices of nprobe nearest centroids
    }
}
```

## sources

1. [Web-Scale K-Means (Sculley, WWW 2010)](https://dl.acm.org/doi/10.1145/1772690.1772862) — introduces mini-batch k-means
2. [Online K-Means Clustering (Liberty et al., ALENEX 2016)](https://epubs.siam.org/doi/10.1137/1.9781611974317.7) — O(log k)-competitive streaming variant
3. [Streaming K-Means (Shindler et al., NeurIPS 2011)](https://papers.nips.cc/paper/2011/hash/2b6d65b9a9445c4271ab9076ead5605a-Abstract.html) — O(1)-competitive with facility location foundation
4. [Faiss: Billion-scale similarity search (Johnson et al.)](https://arxiv.org/abs/1702.08734) — IVF training and GPU k-means

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

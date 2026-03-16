---
title: "Quantization-Aware Partitioning"
aliases: ["quantization-aware", "ivf-pq", "lopq", "opq", "pq-partitioning"]
topic: "systems"
category: "vector-partitioning"
tags: ["partitioning", "quantization", "product-quantization", "compression", "ivf"]
complexity:
  time_build: "O(N * d * nlist * iters) coarse + O(k * d * N_train) PQ"
  time_query: "O(nlist * d + k * d + nprobe * N/nlist * M)"
  space: "O(N * M * ceil(nbits/8)) compressed + O(nlist * d) centroids"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://people.csail.mit.edu/kaiming/publications/pami13opq.pdf"
    title: "Optimized Product Quantization"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://openaccess.thecvf.com/content_cvpr_2014/papers/Kalantidis_Locally_Optimized_Product_2014_CVPR_paper.pdf"
    title: "Locally Optimized Product Quantization for ANN Search"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/1908.10396"
    title: "Accelerating Large-Scale Inference with Anisotropic VQ (ScaNN)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/1711.10775"
    title: "Online Product Quantization"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2411.00970"
    title: "Ada-IVF: Incremental IVF Index Maintenance"
    accessed: "2026-03-16"
    type: "paper"
---

# Quantization-Aware Partitioning

## summary

Partitioning strategies that align partition boundaries with product quantization (PQ)
subspaces to minimize quantization distortion and maximize compression efficiency. The
two-stage approach: coarse quantizer (IVF k-means) assigns vectors to Voronoi cells, then
residuals (vector minus centroid) are PQ-encoded within each cell. OPQ learns a global
rotation to balance variance across PQ subspaces; LOPQ learns per-cell rotations and
codebooks for even lower distortion. Achieves 32-64x compression (512 bytes → 8-16 bytes
per vector) with reasonable recall. Tightly coupled to IVF-PQ search — the partitioning
and compression are co-designed. Incremental updates supported via Ada-IVF and online PQ.

## how-it-works

**IVF-PQ pipeline:**
1. **Coarse quantizer:** k-means produces nlist centroids → Voronoi cells
2. **Residual computation:** r = x - c_nearest (centers residuals near origin)
3. **PQ encoding:** Split residual into M sub-vectors of d/M dims; quantize each against
   a codebook of 2^nbits centroids → M-byte code per vector

**Why residuals matter:** Residuals within a cell are roughly centered and more uniform
than raw vectors, so PQ achieves lower distortion on residuals than on raw vectors.

**OPQ (Ge et al., TPAMI 2013):** Learns a global orthogonal rotation R minimizing overall
quantization distortion. Training alternates: (1) fix R, optimize PQ codebooks via k-means,
(2) fix codebooks, optimize R via Procrustes analysis. Single global rotation — doesn't
adapt per cluster.

**LOPQ (Kalantidis & Avrithis, CVPR 2014):** Four-phase hierarchical quantization:
PCA → split coarse quantization (V^2 cells) → per-cell residual projection (local PCA) →
per-cell fine PQ. Per-partition rotation and codebooks yield significantly lower distortion.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| M | Sub-quantizers (PQ segments) | 4-64, power of 2 | More = better accuracy, more memory. Must divide d |
| nbits | Bits per code | 4, 8, 12, 16 | Higher = larger codebook (2^nbits), better accuracy |
| nlist | Coarse partitions | sqrt(N) to 4*sqrt(N) | More = faster query, needs more training data |
| nprobe | Search-time partitions | 1 to nlist/10 | Linear tradeoff: 2x nprobe ≈ 2x latency, better recall |
| k (codebook) | Codebook size (2^nbits) | 256 typical | Larger = less distortion, more training time |

## algorithm-steps

1. **Train coarse quantizer:** Run k-means on training set to produce nlist centroids
2. **Compute residuals:** For each training vector, r = x - c_nearest
3. **(OPQ) Learn rotation:** Alternate Procrustes + k-means until convergence
4. **(LOPQ) Learn per-cell projections:** For each cell: compute local PCA, learn local PQ codebooks
5. **Train PQ codebooks:** For each sub-quantizer m, run k-means on the m-th sub-vector of all residuals
6. **Encode dataset:** For each vector: assign to nearest centroid, compute residual, (optionally rotate), PQ-encode residual as M-byte code
7. **Query(q, top_k):** Find nprobe nearest centroids (O(nlist * d)); compute PQ distance tables (O(k * d)); scan codes in selected partitions using lookup-add (O(nprobe * N/nlist * M)); return top_k

## implementation-notes

### data-structure-requirements
- Coarse centroids: nlist * d floats (e.g., 4096 * 128 * 4 = 2 MiB)
- PQ codebooks: M * 2^nbits * (d/M) floats (e.g., 8 * 256 * 16 * 4 = 128 KiB) — negligible
- Encoded vectors: N * M * ceil(nbits/8) bytes (e.g., 1B * 8 = 8 GiB for 8-byte codes)
- Per-vector overhead: code (M bytes) + ID (8 bytes)

### edge-cases-and-gotchas
- **Distribution shift:** When incoming data diverges from training data, codebooks become stale. Symptoms: increased quantization error, recall degradation, partition imbalance
- **Codebook staleness:** Underused codes drift from encoder distribution. Mitigation: EMA adaptation, periodic retraining
- **Partition imbalance:** Skewed data creates oversized partitions degrading both latency and recall
- **High-dim sparse data:** PQ works poorly when most sub-vectors are zero; use SQ or binary quantization
- **Non-Euclidean metrics:** Standard PQ optimizes for L2. For MIPS/cosine: normalize to unit sphere first or use ScaNN's anisotropic quantization

## complexity-analysis

### build-phase
Coarse quantizer: O(N * d * nlist * iters). PQ codebook training: O(k * d * N_train * iters). Encoding: O(N * M * k * d/M) = O(N * k * d). OPQ adds O(d^2 * N_train) for rotation.

### query-phase
Centroid scan: O(nlist * d). Lookup table: O(k * d). Code scanning: O(nprobe * N/nlist * M) lookup-adds. Total: O(nlist * d + k * d + nprobe * N/nlist * M). The code scanning step replaces O(N * d) brute force with O(N * M/nlist) — massive speedup.

### memory-footprint
Compressed: ~97% reduction vs float32 for typical params (128-dim float32 = 512 bytes → 8-byte PQ code + 8-byte ID = 16 bytes). Codebooks + centroids: O(nlist * d + M * k * d/M) — fixed, small.

## tradeoffs

### strengths
- Dramatic compression: 32-64x with reasonable recall (0.6-0.85 for IVF-PQ, higher with re-ranking)
- Lookup-table distance replaces floating-point arithmetic — hardware-friendly (SIMD, GPU)
- Composable: IVF + PQ + OPQ layers stack independently
- Faiss IVF-PQ is battle-tested at billion scale in production
- Incremental inserts supported: assign to nearest centroid + encode (no retrain)

### weaknesses
- Requires representative training data — poor training degrades all subsequent queries
- Fixed codebook granularity: once trained, precision level is locked
- Recall ceiling from irreducible quantization error — high-recall needs re-ranking with originals
- Training is expensive: k-means on full dataset + PQ codebook fitting
- Tightly coupled to IVF search paradigm — not index-agnostic

### compared-to-alternatives
- vs [consistent-hashing](consistent-hashing.md): quantization-aware achieves much higher compression but requires training; LSH is training-free
- vs [streaming-kmeans](streaming-kmeans.md): streaming k-means handles the coarse quantizer; quantization-aware adds the fine PQ layer on top
- vs [graph-partitioning](graph-partitioning.md): orthogonal axes — graph for HNSW topology, quantization for compression
- vs [random-projection](random-projection.md): PQ-aligned partitions achieve far better recall-per-byte than random partitions

## current-research

### key-papers
1. Jegou et al., "Product Quantization for Nearest Neighbor Search," IEEE TPAMI 2011 — seminal PQ paper
2. Ge et al., "Optimized Product Quantization," IEEE TPAMI 2013 — global rotation optimization
3. Kalantidis & Avrithis, "Locally Optimized PQ for ANN Search," CVPR 2014 — per-cell optimization
4. Guo et al., "Anisotropic Vector Quantization," ICML 2020 — ScaNN
5. "Online Product Quantization," IEEE TKDE 2018 — streaming codebook updates
6. "Ada-IVF: Incremental IVF Index Maintenance," arXiv 2024 — 2-5x update throughput

### active-research-directions
- Online/streaming codebook adaptation without full retraining
- Ada-IVF adaptive partition maintenance (local re-clustering only problematic partitions)
- 4-bit PQ with SIMD-friendly scanning (ScaNN)
- Additive quantizers (residual quantization, local search quantization) in Faiss

## practical-usage

### when-to-use
- Billion-scale datasets where memory is the bottleneck
- IVF-based indexes where partition and compression can be co-optimized
- When 0.6-0.85 recall is acceptable (or with re-ranking for higher recall)
- When training data is available and representative

### when-not-to-use
- When no training data is available upfront (use LSH)
- HNSW-based indexes without IVF layer
- Very high recall requirements without re-ranking budget
- Rapidly shifting data distributions where codebooks go stale quickly

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| FAISS (IVF-PQ, OPQ) | C++/Python | https://github.com/facebookresearch/faiss | Active |
| ScaNN | C++/Python | https://github.com/google-research/google-research/tree/master/scann | Active |
| LOPQ | Python/Spark | https://github.com/yahoo/lopq | Stable |
| cuVS IVF-PQ | CUDA/C++ | https://github.com/rapidsai/cuvs | Active |
| Milvus | Go/C++ | https://github.com/milvus-io/milvus | Active |

## code-skeleton

```java
class IvfPqPartitioner {
    private final float[][] centroids;     // [nlist][d]
    private final float[][][] codebooks;   // [M][k][d/M]
    private final int nlist, M, k, d;

    byte[] encode(float[] vector) {
        int cell = findNearestCentroid(vector);
        float[] residual = subtract(vector, centroids[cell]);
        byte[] code = new byte[M];
        for (int m = 0; m < M; m++) {
            float[] subvec = slice(residual, m * (d/M), (m+1) * (d/M));
            code[m] = (byte) findNearestCodeword(codebooks[m], subvec);
        }
        return code;
    }

    float asymmetricDistance(float[] query, byte[] code, int cell) {
        float[] residual = subtract(query, centroids[cell]);
        float dist = 0;
        for (int m = 0; m < M; m++) {
            float[] subquery = slice(residual, m * (d/M), (m+1) * (d/M));
            dist += l2Distance(subquery, codebooks[m][code[m] & 0xFF]);
        }
        return dist;
    }
}
```

## sources

1. [Optimized Product Quantization (Ge et al., TPAMI 2013)](https://people.csail.mit.edu/kaiming/publications/pami13opq.pdf) — global rotation to balance PQ subspaces
2. [LOPQ (Kalantidis & Avrithis, CVPR 2014)](https://openaccess.thecvf.com/content_cvpr_2014/papers/Kalantidis_Locally_Optimized_Product_2014_CVPR_paper.pdf) — per-cell rotation and codebooks
3. [ScaNN (Guo et al., ICML 2020)](https://arxiv.org/abs/1908.10396) — anisotropic VQ for MIPS
4. [Ada-IVF (2024)](https://arxiv.org/abs/2411.00970) — adaptive incremental IVF maintenance

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

---
title: "Product Quantization"
aliases: ["pq", "product-quantization", "ivfpq", "opq"]
topic: "algorithms"
category: "vector-quantization"
tags: ["quantization", "multi-codebook", "compression", "ann-search", "lookup-table"]
complexity:
  time_build: "O(n * k * D * I) training + O(n * k * D) encoding"
  time_query: "O(k * D + N * M) ADC search"
  space: "O(N * M) codes + O(M * k * D/M) codebooks"
research_status: "mature"
last_researched: "2026-03-16"
sources:
  - url: "https://inria.hal.science/inria-00514462v2/document"
    title: "Product Quantization for Nearest Neighbor Search (Jégou, Douze, Schmid, 2011)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://www.pinecone.io/learn/series/faiss/product-quantization/"
    title: "Product Quantization: Compressing high-dimensional vectors by 97% (Pinecone)"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://github.com/facebookresearch/faiss/wiki/Additive-quantizers"
    title: "Faiss Additive Quantizers Wiki"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://github.com/facebookresearch/faiss/wiki/Fast-accumulation-of-PQ-and-AQ-codes-(FastScan)"
    title: "Faiss FastScan Wiki"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://arpitbhayani.me/blogs/product-quantization/"
    title: "Product Quantization Deep Dive (Arpit Bhayani)"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://people.csail.mit.edu/kaiming/publications/pami13opq.pdf"
    title: "Optimized Product Quantization (Ge, He, Ke, Sun, 2013)"
    accessed: "2026-03-16"
    type: "paper"
---

# Product Quantization

## summary

Product Quantization (PQ) decomposes a D-dimensional vector into M disjoint subvectors and
independently quantizes each subspace using its own k-means codebook of k* centroids (typically
k*=256, stored in one byte). A 128-D float32 vector (512 bytes) compresses to M bytes (e.g. 8
bytes for M=8), achieving 64x compression. Distance computation uses precomputed lookup tables
(ADC), requiring only M additions per candidate vector. PQ is the foundational multi-codebook
method — residual quantization, additive quantization, and OPQ all build on it.

## how-it-works

PQ exploits the observation that high-dimensional space can be factored as a Cartesian product
of low-dimensional subspaces. Each subspace is quantized independently, so the total number of
reproducible vectors is (k*)^M — exponential in M but with only M * k* total centroids to train.

**Training**: Run k-means independently on each of M subspaces using a representative sample.
**Encoding**: For each vector, find the nearest centroid in each subspace; store M centroid IDs.
**Search (ADC)**: Precompute a distance table from the (unquantized) query to all centroids in
each subspace, then for each database code, sum M table lookups.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| M | Number of subspaces (subquantizers) | 4–64 | Higher M = more bytes per code, better recall, slower search |
| k* (nbits) | Centroids per subspace; k*=2^nbits | 256 (nbits=8) or 16 (nbits=4) | Higher k* = better approximation, larger codebooks |
| D/M | Subvector dimension | 4–32 | Must divide D evenly; lower = coarser quantization |
| nprobe (IVF) | Voronoi cells searched when combined with IVF | 1–256 | Higher nprobe = better recall, slower search |

## algorithm-steps

### training

1. **Sample** training vectors (10K–100K representative vectors from dataset)
2. **Split** each training vector into M subvectors of dimension D/M
3. **Cluster** each subspace independently: run k-means with k* centroids on all subvectors in that subspace
4. **Store** M codebooks, each of shape (k*, D/M)

### encoding

1. **Split** input vector x into M subvectors: x = [x_1, x_2, ..., x_M]
2. **For each** subspace j: find nearest centroid index c_j = argmin_i ||x_j - codebook[j][i]||²
3. **Output** PQ code = [c_1, c_2, ..., c_M] — M bytes for k*=256

### search (asymmetric distance computation)

1. **Split** query q into M subvectors: q = [q_1, ..., q_M]
2. **Build distance table**: for each subspace j and centroid i, compute d[j][i] = ||q_j - codebook[j][i]||²
3. **Scan database**: for each PQ code [c_1, ..., c_M], compute approximate distance = Σ_j d[j][c_j]
4. **Return** top-k by smallest approximate distance

## implementation-notes

### data-structure-requirements

- **Codebooks**: M arrays of shape (k*, D/M) — float32 or float16. Total: M * k* * (D/M) * 4 bytes. For M=8, k*=256, D=128: 128 KB
- **PQ codes**: byte array of shape (N, M). For 1B vectors, M=8: 8 GB
- **Distance tables**: M * k* floats per query — fits in L1 cache for k*=256, M≤64

### adc-vs-sdc

- **ADC (Asymmetric)**: query is unquantized, only database vectors are compressed. Preferred — lower quantization error, same computational cost as SDC
- **SDC (Symmetric)**: both query and database are quantized. Precomputes M inter-centroid distance tables of size k* × k*. Saves memory when queries are stored, but 5–10% lower recall than ADC

### fastscan-optimization

Faiss FastScan uses 4-bit PQ (k*=16) with SIMD-packed lookup tables stored in CPU registers
(AVX2/Neon). Processes 32 vectors simultaneously. Achieves 280K QPS with reranking — 2x faster
than HNSW with 2.7x less memory. Requires reranking pass for accuracy recovery.

### edge-cases-and-gotchas

- D must be evenly divisible by M — pad or truncate dimensions if needed
- Subspaces with unequal variance waste codebook capacity — use OPQ rotation to equalize
- k*=256 (8-bit) is standard; k*=16 (4-bit) enables SIMD FastScan but needs reranking
- Training set must be representative; biased samples lead to poor centroid placement
- PQ is a batch method: codebooks are trained on a sample, then all vectors are encoded

## complexity-analysis

### build-phase

- **Training**: O(n_train * k* * (D/M) * I * M) where I = k-means iterations (~20–50)
- **Encoding**: O(N * M * k* * (D/M)) = O(N * k* * D) — find nearest centroid per subspace
- With k*=256, D=128, M=8: encoding one vector requires 256 * 128 = 32K distance ops

### query-phase

- **Distance table**: O(M * k* * (D/M)) = O(k* * D) per query
- **Scan**: O(N * M) additions per query (table lookups)
- With IVF (nprobe cells, N/nlist vectors per cell): O(nprobe * N/nlist * M)
- FastScan (4-bit): O(N * M) but with ~10x smaller constant via SIMD

### memory-footprint

| Scenario | Raw Size | PQ Size (M=8) | Compression |
|----------|----------|---------------|-------------|
| 1M × 128D float32 | 512 MB | 8 MB | 64x |
| 1B × 128D float32 | 512 GB | 8 GB | 64x |
| 10M × 1536D float32 | 61.4 GB | 480 MB (M=48) | 128x |

Codebook overhead is negligible: 128 KB for M=8, k*=256, D=128.

## tradeoffs

### strengths

- Extreme compression (32x–128x) with moderate recall (50–80% standalone, 90%+ with IVF+reranking)
- Very fast search via lookup tables — O(M) additions per vector, cache-friendly
- Well-understood, mature algorithm with 15+ years of production use (FAISS, ScaNN, Milvus)
- Composable: combines naturally with IVF (coarse quantizer) and reranking for accuracy recovery
- FastScan variant achieves 280K QPS — competitive with graph-based methods at lower memory

### weaknesses

- **Batch training required**: codebooks must be trained on a representative sample before encoding
- **Not streaming-compatible**: new data distributions require codebook retraining
- **Moderate standalone recall**: 50–60% recall@10 without IVF/reranking on SIFT1M
- **Subspace independence assumption**: correlated dimensions across subspaces waste capacity (OPQ mitigates this)
- **Encoding cost**: O(k* * D) per vector — 10–100x slower than scalar quantization encoding

### compared-to-alternatives

- vs [scalar-quantization](scalar-quantization.md): PQ achieves 16–64x more compression but lower recall without reranking; SQ is streaming-compatible, PQ is not
- vs [binary-quantization](binary-quantization.md): similar compression range (32x), but PQ has higher recall for moderate dimensions; BQ is simpler and streaming
- vs [residual-quantization](residual-quantization.md): RQ achieves 5–20% better recall at same code size, but PQ encodes much faster; PQ is a special case of additive quantization with disjoint subspaces
- vs [rabitq](rabitq.md): RaBitQ is streaming with provable bounds; PQ requires training but supports extreme compression ratios via M parameter

## sources

1. [Product Quantization for Nearest Neighbor Search (Jégou et al., 2011)](https://inria.hal.science/inria-00514462v2/document) — foundational paper defining PQ, ADC, SDC, and IVF-PQ
2. [Product Quantization: Compressing vectors by 97% (Pinecone)](https://www.pinecone.io/learn/series/faiss/product-quantization/) — practical walkthrough with FAISS benchmarks on SIFT1M
3. [Faiss Additive Quantizers Wiki](https://github.com/facebookresearch/faiss/wiki/Additive-quantizers) — positions PQ as special case of additive quantization
4. [Faiss FastScan Wiki](https://github.com/facebookresearch/faiss/wiki/Fast-accumulation-of-PQ-and-AQ-codes-(FastScan)) — 4-bit PQ SIMD optimization details and benchmarks
5. [Product Quantization Deep Dive (Arpit Bhayani)](https://arpitbhayani.me/blogs/product-quantization/) — detailed ADC/SDC explanation with pseudocode
6. [Optimized Product Quantization (Ge et al., 2013)](https://people.csail.mit.edu/kaiming/publications/pami13opq.pdf) — OPQ rotation matrix optimization

---
*Researched: 2026-03-16 | Next review: 2026-09-16*

@./product-quantization-detail.md

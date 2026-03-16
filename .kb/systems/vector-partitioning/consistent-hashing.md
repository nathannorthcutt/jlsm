---
title: "Consistent Hashing & LSH-Based Vector Partitioning"
aliases: ["consistent-hashing", "lsh-partitioning", "locality-sensitive-hashing"]
topic: "systems"
category: "vector-partitioning"
tags: ["partitioning", "lsh", "consistent-hashing", "index-agnostic", "streaming"]
complexity:
  time_build: "O(n * L * k * d)"
  time_query: "O(L * k * d + L * n/2^k * d)"
  space: "O(n * L + L * k * d)"
research_status: "mature"
last_researched: "2026-03-16"
sources:
  - url: "https://arxiv.org/abs/1509.02897"
    title: "Practical and Optimal LSH for Angular Distance"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://github.com/FALCONN-LIB/FALCONN"
    title: "FALCONN - Cross-Polytope LSH Library"
    accessed: "2026-03-16"
    type: "repo"
  - url: "https://cseweb.ucsd.edu/~dasgupta/papers/rptree-stoc.pdf"
    title: "Random Projection Trees and Low Dimensional Manifolds"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://en.wikipedia.org/wiki/Rendezvous_hashing"
    title: "Rendezvous Hashing"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://dbgroup.cs.tsinghua.edu.cn/ligl/papers/vldbj2024-vectordb.pdf"
    title: "Survey of Vector Database Management Systems"
    accessed: "2026-03-16"
    type: "paper"
---

# Consistent Hashing & LSH-Based Vector Partitioning

## summary

Index-agnostic partitioning strategy that combines locality-sensitive hashing (LSH) for
logical partitioning with consistent or rendezvous hashing for physical node assignment.
LSH hash functions are generated from random vectors — no training data required — making
this fully streaming-compatible. Each incoming vector is hashed to a partition in O(k*d) time.
Best suited when partition decisions must be made without seeing any data upfront and the
underlying index type (HNSW, IVF, flat) varies per partition or is chosen later.

## how-it-works

Two-layer design separating logical partitioning from physical placement:

**Layer 1 — Logical partitioning (LSH):** Pick k random unit vectors r_1..r_k. For each
data vector v, compute h(v) = [sign(dot(r_1, v)), ..., sign(dot(r_k, v))]. The k-bit
string is the partition ID. Similar vectors produce the same bit string with high probability
(collision probability for angle alpha: P = 1 - alpha/pi per bit). Expected partition count:
2^k. Expected partition size: n / 2^k.

**Layer 2 — Physical placement:** Map logical partitions to physical nodes via rendezvous
hashing (HRW). For each partition ID p and node set {s_1..s_m}, compute weight
w_i = hash(p, s_i) and assign p to argmax(w_i). When a node is added/removed, only ~1/m
of partitions migrate.

**Query routing:** Hash query vector with same LSH functions, identify candidate partitions,
optionally probe neighboring partitions (multi-probe LSH), fan out, merge results.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| k | Hash bits (hyperplanes per table) | 8-24 | Higher = smaller partitions, more precision, fewer recalls per table |
| L | Number of hash tables | 1-100 | Higher = better recall, linear memory increase |
| d | Vector dimensionality | 64-1024 | LSH quality degrades above ~200 dims without reduction |
| nprobe | Multi-probe bucket count | 1-50 | More probes = higher recall, slower queries |
| virtual_nodes | Per physical node (consistent hashing) | 50-200 | More = better load balance, more metadata |

## algorithm-steps

1. **Initialize:** Sample L sets of k random unit vectors from d-dimensional Gaussian
2. **Insert(v):** For each table t in 1..L: compute bit string b_t = [sign(dot(r_t_i, v)) for i in 1..k]; append v to bucket b_t in table t
3. **Split partition:** When bucket exceeds memory budget M, add one hash bit (k → k+1) using a pre-generated (k+1)-th hyperplane; re-hash bucket contents into two sub-buckets
4. **Query(q, top_k):** Hash q through all L tables; collect candidate set C = union of all matching buckets; optionally add multi-probe neighbors; compute exact distances for all c in C; return top_k
5. **Physical placement:** For each logical partition ID, compute rendezvous hash against all physical nodes; assign to highest-weight node

## implementation-notes

### data-structure-requirements
- L hash tables, each mapping k-bit keys to posting lists (append-only, LSM-friendly)
- L * k random projection vectors (small, fixed memory: L * k * d floats)
- Rendezvous hash: one hash computation per (partition, node) pair

### edge-cases-and-gotchas
- Highly clustered data causes severe bucket imbalance — some buckets get most vectors
- Curse of dimensionality: above ~200 dims, hash quality degrades; apply PCA first
- Uniform random data makes all partitioning approaches equivalent — LSH provides no benefit
- Multi-probe is essential for practical recall; single-probe recall is poor

## complexity-analysis

### build-phase
O(n * L * k * d) — hash each of n vectors through L tables with k projections of dimension d.
Cross-polytope LSH reduces per-hash cost to O(d log d) via Fast Hadamard Transform.

### query-phase
O(L * k * d) for hashing + O(|candidates| * d) for distance computation.
|candidates| ≈ L * n / 2^k in expectation. Sub-linear in n when k = O(log n).

### memory-footprint
Hash functions: L * k * d floats (fixed, small). Tables: O(n * L) pointers.
Per-partition memory bounded by: (n / 2^k) * d * sizeof(float) expected.

## tradeoffs

### strengths
- Fully online/streaming: no training, no data-dependent initialization
- Index-agnostic: any index type can be built within each partition
- Theoretically grounded sub-linear query time guarantees
- Trivially parallelizable across hash tables
- Elastic scaling via rendezvous hashing with minimal data movement

### weaknesses
- Lower recall at equal memory vs data-dependent methods (HNSW, IVF-PQ)
- Space overhead: L tables with O(n) entries each
- Curse of dimensionality limits practical usefulness above ~200 dims
- Bucket imbalance with non-uniform data distributions

### compared-to-alternatives
- vs [random-projection](random-projection.md): overlapping family; RP trees add hierarchy but lose streaming insert
- vs [streaming-kmeans](streaming-kmeans.md): k-means adapts to data but requires training; LSH is data-independent
- vs [graph-partitioning](graph-partitioning.md): graph-aware methods preserve navigability better but are index-specific
- vs [quantization-aware](quantization-aware.md): PQ-aligned partitioning achieves much higher compression but requires codebook training

## practical-usage

### when-to-use
- Streaming ingestion where no training phase is acceptable
- Multi-index deployments (different index types per partition)
- Elastic scaling requirements (nodes added/removed frequently)
- Moderate dimensionality (d <= 200) or with PCA pre-processing

### when-not-to-use
- Very high dimensions without dimensionality reduction
- When maximum recall is required (data-dependent methods win)
- Single-machine deployments (simpler approaches suffice)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| FALCONN | C++ | https://github.com/FALCONN-LIB/FALCONN | Active |
| FAISS IndexLSH | C++/Python | https://github.com/facebookresearch/faiss | Active |
| RendezvousHash | Java | https://github.com/clohfink/RendezvousHash | Stable |

## code-skeleton

```java
class LshPartitioner {
    private final float[][] projections; // [L*k][d]
    private final int k, L, d;

    LshPartitioner(int k, int L, int d) { /* sample random projections */ }

    int[] partitionIds(float[] vector) {
        int[] ids = new int[L];
        for (int t = 0; t < L; t++) {
            int hash = 0;
            for (int i = 0; i < k; i++) {
                if (dot(projections[t * k + i], vector) >= 0) hash |= (1 << i);
            }
            ids[t] = hash;
        }
        return ids;
    }

    Set<Integer> queryCandidatePartitions(float[] query, int nprobe) {
        Set<Integer> candidates = new HashSet<>();
        for (int id : partitionIds(query)) {
            candidates.add(id);
            // multi-probe: flip each bit for nearby buckets
            for (int bit = 0; bit < k && candidates.size() < nprobe; bit++)
                candidates.add(id ^ (1 << bit));
        }
        return candidates;
    }
}
```

## sources

1. [Practical and Optimal LSH for Angular Distance](https://arxiv.org/abs/1509.02897) — optimal cross-polytope LSH with rho = 1/c^2
2. [FALCONN Library](https://github.com/FALCONN-LIB/FALCONN) — production cross-polytope LSH with multi-probe
3. [Survey of Vector Database Management Systems](https://dbgroup.cs.tsinghua.edu.cn/ligl/papers/vldbj2024-vectordb.pdf) — comprehensive survey covering partitioning strategies

---
*Researched: 2026-03-16 | Next review: 2026-09-12*

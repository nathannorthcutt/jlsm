# Vector Partitioning — Category Index
*Topic: systems*

Strategies for partitioning large vector datasets across bounded-memory partitions, covering
index-agnostic approaches (usable with any ANN index) and index-specific optimizations
(tailored to IVF, HNSW, or PQ workflows). Key concern: streaming ingestion without requiring
the full dataset upfront.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [consistent-hashing.md](consistent-hashing.md) | LSH + Consistent/Rendezvous Hashing | mature | O(k*d) insert, no training | Index-agnostic streaming, elastic scaling |
| [random-projection.md](random-projection.md) | Random Projection Trees | mature | O(d*T*log(n/M)) query | Hierarchical partitions, manifold-aware |
| [streaming-kmeans.md](streaming-kmeans.md) | Streaming K-Means for IVF | active | O(k*d) insert, adaptive | IVF indexes, non-uniform distributions |
| [graph-partitioning.md](graph-partitioning.md) | Graph-Aware HNSW Partitioning | active | 2.14x QPS (GP-ANN) | HNSW indexes, preserving navigability |
| [quantization-aware.md](quantization-aware.md) | Quantization-Aware (PQ/OPQ/LOPQ) | active | 32-64x compression | IVF-PQ, memory-constrained billion-scale |

## Comparison Summary

**Index-agnostic approaches** (consistent-hashing, random-projection) make partition
decisions without knowledge of the underlying index. Consistent hashing + LSH is fully
streaming with zero training but achieves lower recall per memory unit. RP trees add
hierarchical structure but lose post-build insertion in canonical implementations.

**IVF-optimized** (streaming-kmeans, quantization-aware) produce Voronoi cells that
directly serve as IVF partitions. Streaming k-means handles the coarse quantizer online;
quantization-aware adds PQ/OPQ/LOPQ for 32-64x compression on top. Both require some
training/stabilization period.

**HNSW-optimized** (graph-partitioning) accounts for graph edge connectivity. Naive
sharding destroys navigability (80%+ cross-partition traversals). GP-ANN, SPFresh LIRE,
and merge algorithms address this but are HNSW-specific.

**For jlsm:** The most relevant approaches are streaming k-means (for IVF partitioning
with LSM-tree aligned append-only writes) and consistent hashing + LSH (for index-agnostic
partitioning without training). Graph partitioning becomes relevant if HNSW is scaled
beyond single-node.

## Recommended Reading Order
1. Start: [consistent-hashing.md](consistent-hashing.md) — simplest, fully streaming, index-agnostic
2. Then: [streaming-kmeans.md](streaming-kmeans.md) — IVF-specific, data-adaptive
3. Then: [graph-partitioning.md](graph-partitioning.md) — HNSW-specific challenges
4. Then: [quantization-aware.md](quantization-aware.md) — compression-aligned partitioning
5. Optional: [random-projection.md](random-projection.md) — hierarchical alternative to LSH

## Research Gaps
- Hybrid LSH + streaming k-means (LSH for initial routing, k-means for refinement)
- LSM-compaction-aware partition rebalancing strategies
- Formal recall guarantees for streaming IVF with centroid drift
- Benchmarks comparing approaches on jlsm's LSM-tree storage model

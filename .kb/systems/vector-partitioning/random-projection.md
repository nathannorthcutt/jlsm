---
title: "Random Projection Tree Partitioning"
aliases: ["rp-trees", "random-projection-forest", "annoy-partitioning"]
topic: "systems"
category: "vector-partitioning"
tags: ["partitioning", "random-projection", "space-partitioning", "index-agnostic"]
complexity:
  time_build: "O(n * d * T * log(n/M))"
  time_query: "O(d * T * log(n/M) + |candidates| * d)"
  space: "O(n * T + T * internal_nodes)"
research_status: "mature"
last_researched: "2026-03-16"
sources:
  - url: "https://cseweb.ucsd.edu/~dasgupta/papers/rptree-stoc.pdf"
    title: "Random Projection Trees and Low Dimensional Manifolds"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/1010.3812"
    title: "Random Projection Trees Revisited"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://github.com/spotify/annoy"
    title: "Annoy - Approximate Nearest Neighbors Oh Yeah"
    accessed: "2026-03-16"
    type: "repo"
  - url: "https://github.com/lyst/rpforest"
    title: "RPForest - Random Projection Forest"
    accessed: "2026-03-16"
    type: "repo"
  - url: "https://arxiv.org/html/2505.17152v1"
    title: "LSM-VEC: Large-Scale Disk-Based Dynamic Vector Search"
    accessed: "2026-03-16"
    type: "paper"
---

# Random Projection Tree Partitioning

## summary

Hierarchical space partitioning using random hyperplanes to recursively bisect the vector
space into balanced regions. Each internal node chooses a random direction and splits at
the median projection, producing a binary tree where leaves are partitions. A forest of T
trees provides redundancy for recall. Data-independent in the choice of direction (random)
but data-dependent in split position (median). Annoy (Spotify) is the canonical
implementation. Best for moderate-scale datasets where hierarchical partition structure
is valuable, though standard implementations do not support post-build insertion.

## how-it-works

**RP-Tree (Dasgupta-Freund, STOC 2008):** At each node, sample a random unit vector r
from the d-dimensional unit sphere. Project all points onto r, compute the median, split
into left (below median) and right (above median). Recurse until leaves contain <= M points.

**Annoy variant:** Instead of a purely random direction, sample two points from the current
subset and use the hyperplane equidistant from them (perpendicular bisector). This makes
splits slightly data-aware while remaining cheap.

**Key theoretical result:** Vector quantization error decays as e^{-O(r/d_intrinsic)}
where r is tree depth and d_intrinsic is the intrinsic dimensionality. The tree
automatically adapts to intrinsic dimensionality without knowing it.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| T (n_trees) | Number of trees in forest | 10-100 | More = better recall, linear memory increase |
| M (leaf_size) | Max points per leaf | 50-200 | Smaller = deeper trees, more precise, slower build |
| search_k | Nodes examined at query time | n * n_trees | Higher = better recall, slower queries |
| d | Vector dimensionality | 64-1024 | Tree depth proportional to d_intrinsic, not d |

## algorithm-steps

1. **Build forest:** For each tree t in 1..T:
2. **Build tree(points):** If |points| <= M: create leaf node, store point IDs, return
3. **Choose split:** Sample random direction r (uniform on unit sphere) OR sample two points and use perpendicular bisector
4. **Split:** Project all points onto r, find median m, partition into left (projection < m) and right (projection >= m)
5. **Recurse:** Build tree(left), build tree(right)
6. **Query(q, k):** Traverse each tree from root, following the side of each split the query falls on; collect all leaf candidates across T trees; compute exact distances; return top k

## implementation-notes

### data-structure-requirements
- Per tree: O(n/M) internal nodes, each storing a split direction (d floats) and threshold (1 float)
- Leaves store point IDs (or pointers to external storage)
- Annoy uses memory-mapped files for the index — multiple processes can share

### edge-cases-and-gotchas
- **No online insertion (Annoy):** Once `build()` is called, no new points can be added. Must rebuild entire index. RPForest separates fit/index phases as a workaround.
- **Degenerate splits:** When data lies on a low-dimensional manifold aligned with the chosen hyperplane, one child gets nearly all points. The median split prevents worst-case but doesn't eliminate imbalance.
- **Split position is data-dependent:** The median is computed from the current subset, so tree structure depends on insertion order for the initial build.

## complexity-analysis

### build-phase
O(n * d * T * log(n/M)) — T trees, each with O(log(n/M)) depth, O(n * d) projection work per level.

### query-phase
O(d * T * log(n/M)) for tree traversal + O(|candidates| * d) for distance computation.
|candidates| depends on search_k parameter.

### memory-footprint
Tree structure: O(T * n/M * d) for split vectors. Point storage: O(n * d) shared across trees (stored once). Annoy uses mmap for shared read-only access.

## tradeoffs

### strengths
- Hierarchical structure maps naturally to tree-of-SSTables storage
- Adapts to intrinsic dimensionality — effective even in high ambient dims if data lies on a manifold
- Simple implementation, fast build
- Memory-mappable (Annoy) for multi-process sharing

### weaknesses
- No post-build insertion in canonical implementations (Annoy)
- Split positions depend on data — not fully streaming-compatible
- Lower recall than HNSW at equal memory budget
- Tree depth issues in truly high-dimensional uniform data

### compared-to-alternatives
- vs [consistent-hashing](consistent-hashing.md): RP trees add hierarchy and data-adaptive splits; LSH is fully data-independent and streaming-native
- vs [streaming-kmeans](streaming-kmeans.md): k-means produces Voronoi cells optimized for the data; RP trees use random directions
- vs [graph-partitioning](graph-partitioning.md): graph methods preserve connectivity; RP trees are structure-agnostic

## practical-usage

### when-to-use
- Moderate-scale datasets (millions, not billions) where build-once-query-many is acceptable
- When hierarchical partition structure maps well to storage (tree of SSTables)
- Data with low intrinsic dimensionality (manifold structure)
- Multi-process read sharing via mmap

### when-not-to-use
- Streaming ingestion requiring online insert (use LSH or streaming k-means instead)
- Billion-scale datasets (graph-based methods dominate)
- When maximum recall is critical (HNSW wins)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Annoy | C++/Python | https://github.com/spotify/annoy | Active |
| RPForest | Python/Cython | https://github.com/lyst/rpforest | Stable |
| FALCONN | C++ | https://github.com/FALCONN-LIB/FALCONN | Active |
| FAISS IndexLSH | C++/Python | https://github.com/facebookresearch/faiss | Active |

## code-skeleton

```java
class RpTreePartitioner {
    private final int leafSize, numTrees, dims;
    record SplitNode(float[] direction, float threshold, Object left, Object right) {}
    record LeafNode(int[] pointIds) {}

    Object buildTree(float[][] points, int[] ids) {
        if (ids.length <= leafSize) return new LeafNode(ids);
        float[] dir = randomUnitVector(dims);
        float[] projections = project(points, ids, dir);
        float median = median(projections);
        var split = partition(ids, projections, median);
        return new SplitNode(dir, median,
            buildTree(points, split.left()),
            buildTree(points, split.right()));
    }

    List<Integer> query(Object node, float[] query) {
        if (node instanceof LeafNode leaf) return List.of(leaf.pointIds());
        var split = (SplitNode) node;
        float proj = dot(split.direction(), query);
        return query(proj < split.threshold() ? split.left() : split.right(), query);
    }
}
```

## sources

1. [Random Projection Trees (Dasgupta-Freund, STOC 2008)](https://cseweb.ucsd.edu/~dasgupta/papers/rptree-stoc.pdf) — foundational theory on RP trees and intrinsic dimensionality adaptation
2. [Random Projection Trees Revisited (NeurIPS 2010)](https://arxiv.org/abs/1010.3812) — adaptive cell-size guarantees proportional to local intrinsic dimension
3. [Annoy (Spotify)](https://github.com/spotify/annoy) — canonical production implementation with mmap support
4. [RPForest (Lyst)](https://github.com/lyst/rpforest) — two-phase fit/index separation for larger-than-memory datasets

---
*Researched: 2026-03-16 | Next review: 2026-09-12*

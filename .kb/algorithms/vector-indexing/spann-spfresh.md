---
title: "SPANN / SPFresh — Partition-Based ANN for Remote Storage"
aliases: ["SPANN", "SPFresh", "LIRE"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "object-storage", "partition-index", "inverted-file", "streaming-updates"]
complexity:
  time_build: "O(n log k) clustering + centroid graph"
  time_query: "O(nprobe * avg_posting_size) I/O + centroid search"
  space: "O(num_centroids * dim) in memory; O(n * dim) on disk"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://www.microsoft.com/en-us/research/publication/spann-highly-efficient-billion-scale-approximate-nearest-neighbor-search/"
    title: "SPANN: Highly-efficient Billion-scale ANN Search"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/abs/2410.14452"
    title: "SPFresh: Incremental In-Place Update for Billion-Scale Vector Search"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3600006.3613166"
    title: "SPFresh (SOSP 2023 proceedings)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2601.01937"
    title: "Vector Search for the Future: Memory-Resident to Cloud-Native (Jan 2026)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/abs/2511.14748"
    title: "Cloud-Native Vector Search: Comprehensive Performance Analysis (Nov 2025)"
    accessed: "2026-03-30"
    type: "paper"
---

# SPANN / SPFresh

## summary

SPANN (NeurIPS 2021, Microsoft Research) is an inverted-index ANN system that keeps only
cluster centroids in memory and stores full posting lists (raw vectors) on disk or remote
storage. SPFresh (SOSP 2023) extends SPANN with LIRE — a lightweight incremental
rebalancing protocol that enables streaming inserts/deletes without global index rebuilds.
The partition-based architecture is the best fit for object storage (S3/GCS) among all
current ANN approaches because queries translate to parallel batch reads of contiguous
posting-list objects.

## how-it-works

**SPANN core architecture:**
1. Hierarchical balanced k-means partitions all vectors into clusters
2. Each cluster's posting list is stored as a contiguous block on disk/remote storage
3. A lightweight navigational graph (SPTAG) over centroids lives in memory (~40 bytes/centroid)
4. Boundary vectors are replicated across 2-3 nearest posting lists for recall at edges

**Memory vs storage split:**

| Component | Location | Size (1B vectors, 128d) |
|-----------|----------|------------------------|
| Centroids + nav graph | Memory | ~hundreds of MB |
| Version maps / metadata | Memory | ~4-8 bytes/vector |
| Posting lists (vectors) | Disk/Remote | Bulk of data |

**SPFresh / LIRE protocol — five operations:**
1. **Insert:** append vector to nearest posting list (foreground, fast)
2. **Delete:** tombstone in version map (foreground, fast)
3. **Split:** when posting exceeds size threshold, balanced clustering into two new postings
4. **Merge:** when posting falls below minimum, combine with nearest neighbor posting
5. **Reassign:** after split/merge, ~1.5% of boundary vectors move to closer centroid

LIRE maintains the NPA (Nearest Partition Assignment) invariant: each vector belongs to
its closest centroid, adapting to data distribution drift over time.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| num_clusters | Number of centroids / posting lists | sqrt(n) to n/1000 | More = finer partitions, less I/O per query |
| nprobe | Posting lists probed per query | 8-64 | Higher = better recall, more I/O |
| replication_factor | Boundary vector copies | 2-3 | Higher = better recall at edges, more storage |
| split_threshold | Max posting list size | 2-4x avg size | Triggers background rebalancing |

## algorithm-steps

### build (SPANN)
1. **Cluster:** run hierarchical balanced k-means on all vectors
2. **Assign:** each vector → nearest centroid's posting list; replicate boundary vectors
3. **Write postings:** serialize each posting list as contiguous block to storage
4. **Build nav graph:** construct SPTAG graph over centroids in memory

### query
1. **Centroid search:** traverse in-memory nav graph to find top-nprobe nearest centroids
2. **Fetch postings:** load nprobe posting lists from storage (parallel batch reads)
3. **Scan + filter:** linear scan candidates, apply metadata filters inline
4. **Rerank:** return top-k by exact distance from merged candidate set

### update (SPFresh/LIRE)
1. **Foreground:** insert → append to nearest posting; delete → tombstone
2. **Background:** monitor posting sizes; trigger split/merge/reassign when thresholds crossed
3. **Crash recovery:** append-only writes with copy-on-write for posting rewrites

## implementation-notes

### data-structure-requirements
- Centroid vectors: contiguous float array in memory (SIMD-friendly)
- Nav graph: SPTAG or simple HNSW over centroids (~1M nodes for 1B vectors)
- Posting lists: contiguous byte blocks, each containing vector data + optional metadata
- Version map: concurrent hash map for tombstones (insert/delete tracking)

### edge-cases-and-gotchas
- Boundary replication increases total storage by 30-60% depending on replication factor
- Without SPFresh, distribution drift degrades recall — SPANN requires periodic full rebuild
- LIRE reassignment reads ~64 nearby postings per split — expensive on high-latency storage
- SPFresh was designed for NVMe (SPDK); adapting to S3 requires replacing block I/O layer

### object-storage-adaptation
- Map each posting list to one S3 object (or block within a larger object with byte-range reads)
- Buffer recent inserts in a local WAL / memory delta; periodically compact into new S3 postings
- Split/merge operations produce new immutable objects (natural fit for S3 write-once semantics)
- Speculative prefetching of likely-needed postings can mask S3 latency (50-200ms per GET)
- Expected latency overhead: 3-5x vs local SSD for the posting fetch phase

## complexity-analysis

### build-phase
O(n log k) for hierarchical balanced k-means + O(k log k) for centroid nav graph.

### query-phase
O(log k) centroid search in memory + O(nprobe * avg_posting_size * dim) for scanning.
I/O cost: nprobe sequential reads (parallelizable).

### memory-footprint
O(k * dim) for centroids + O(n * 8) for version maps. For 1B 128d vectors with 1M
centroids: ~512 MB centroids + ~8 GB version maps. Vectors themselves are on storage.

## tradeoffs

### strengths
- Lowest memory footprint of any billion-scale ANN system (centroids only in RAM)
- Sequential I/O pattern — posting lists are contiguous, ideal for batch reads
- Best fit for object storage among all current ANN approaches
- SPFresh enables streaming updates without global rebuild
- 2x+ faster than DiskANN at 90% recall with equivalent memory budget
- Metadata co-located in posting lists enables single-pass filtered search

### weaknesses
- Coarser search granularity than graph-based methods (cluster-level vs vertex-level)
- Recall depends heavily on cluster quality and nprobe tuning
- Boundary replication increases storage 30-60%
- SPFresh's LIRE reassignment is expensive on high-latency storage
- No native support for complex predicates (range, disjunction) — only inline filtering

### compared-to-alternatives
- vs [DiskANN/Filtered-DiskANN](filtered-diskann.md): much lower memory, sequential vs random I/O, but coarser pruning
- vs HNSW: orders of magnitude less memory, handles billion-scale, but higher query latency
- vs [ACORN](acorn.md): different tradeoff axis — SPANN optimizes storage, ACORN optimizes filter handling

## current-research

### key-papers
- Chen et al. "SPANN: Highly-efficient Billion-scale ANN Search." NeurIPS 2021.
- Zhang et al. "SPFresh: Incremental In-Place Update for Billion-Scale Vector Search." SOSP 2023.
- Jan 2026 survey: "Vector Search for the Future" — recommends partition-based for remote storage.
- Nov 2025 analysis: confirms network I/O is dominant bottleneck; cluster-based outperforms graph-based on remote.

### active-research-directions
- UBIS (2025): improved split operations and lock design over SPFresh
- OdinANN (FAST 2026): direct-insert alternative for graph-based disk indexes
- Amazon S3 Vectors (GA Dec 2025): native ANN in object storage, validating partition-based approach
- Adaptive nprobe: query-aware dynamic pruning of posting list probes

## practical-usage

### when-to-use
- Dataset exceeds available RAM (billions of vectors)
- Storage backend is SSD, object storage, or remote filesystem
- Queries are batch-tolerant (not sub-millisecond latency requirement)
- Metadata filtering is needed (co-located in posting lists)

### when-not-to-use
- Sub-millisecond latency required (graph-based in-memory HNSW is better)
- Dataset fits in memory (HNSW or IVF-Flat is simpler)
- Complex predicate logic required (ACORN or Compass handle this better)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| SPTAG | C++ | github.com/microsoft/SPTAG | Microsoft, active |
| SPFresh | C++ | github.com/microsoft/SPFresh | Microsoft Research |

## code-skeleton

```
class SpannIndex:
    centroids: float[][]         # k centroids in memory
    nav_graph: Graph             # SPTAG/HNSW over centroids
    storage: ObjectStore         # S3/GCS/local
    version_map: ConcurrentMap   # tombstones + sequence numbers

    def build(vectors, k, replication_factor):
        centroids = hierarchical_balanced_kmeans(vectors, k)
        nav_graph = build_sptag(centroids)
        for cluster in assignments:
            posting = serialize(cluster.vectors + cluster.metadata)
            storage.put(cluster_key(cluster.id), posting)

    def query(q, top_k, nprobe, predicate=None):
        nearest_centroids = nav_graph.search(q, nprobe)
        postings = storage.batch_get([cluster_key(c) for c in nearest_centroids])
        candidates = []
        for posting in postings:
            for vec, meta in deserialize(posting):
                if predicate is None or predicate(meta):
                    candidates.append((distance(q, vec), vec.id))
        return top_k_from(candidates, top_k)

    def insert(vec, metadata):
        nearest = nav_graph.search(vec, 1)[0]
        storage.append(cluster_key(nearest), serialize(vec, metadata))
        # background: LIRE monitors size, triggers split/merge/reassign
```

## sources

1. [SPANN (Microsoft Research)](https://www.microsoft.com/en-us/research/publication/spann-highly-efficient-billion-scale-approximate-nearest-neighbor-search/) — foundational paper, NeurIPS 2021
2. [SPFresh (SOSP 2023)](https://dl.acm.org/doi/10.1145/3600006.3613166) — streaming update protocol (LIRE)
3. [Vector Search for the Future (Jan 2026)](https://arxiv.org/html/2601.01937) — survey recommending partition-based for remote storage
4. [Cloud-Native Vector Search Analysis (Nov 2025)](https://arxiv.org/abs/2511.14748) — confirms cluster-based > graph-based on remote storage

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

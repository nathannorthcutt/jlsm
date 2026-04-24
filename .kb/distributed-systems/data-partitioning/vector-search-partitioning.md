---
title: "Partitioning Strategies for Distributed Vector Search with Hybrid Filtering"
aliases: ["vector sharding", "ANN partitioning", "hybrid search partitioning"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["vector-search", "ANN", "HNSW", "IVF", "hybrid-search", "filtered-search", "partitioning"]
complexity:
  time_build: "O(n log n) for HNSW, O(n * k) for IVF clustering"
  time_query: "O(log n) HNSW, O(nprobe * cluster_size) IVF"
  space: "O(n * M) HNSW graph, O(n * d) IVF centroids + vectors"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://yudhiesh.github.io/2025/05/09/the-achilles-heel-of-vector-search-filters/"
    title: "The Achilles Heel of Vector Search: Filters"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://aakashsharan.com/dense-vector-search-scaling-architecture/"
    title: "Dense Vector Search: The Hidden Scaling Challenge in AI Systems"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://dspace.mit.edu/bitstream/handle/1721.1/164256/3749167.pdf"
    title: "HARMONY: A Scalable Distributed Vector Database (MIT)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/html/2310.11703v2"
    title: "A Comprehensive Survey on Vector Database: Storage and Retrieval"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://weaviate.io/blog/hybrid-search-explained"
    title: "Hybrid Search Explained (Weaviate)"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://milvus.io/blog/understanding-ivf-vector-index-how-It-works-and-when-to-choose-it-over-hnsw.md"
    title: "How to Choose Between IVF and HNSW for ANN Vector Search (Milvus)"
    accessed: "2026-03-16"
    type: "blog"
related:
  - "distributed-systems/data-partitioning/decoupled-index-partitioning.md"
  - "distributed-systems/data-partitioning/cross-partition-query-planning.md"
---

# Partitioning Strategies for Distributed Vector Search with Hybrid Filtering

## summary

Distributing vector search across multiple nodes requires fundamentally
different partitioning strategies than key-value data. HNSW graph indices
resist partitioning (cutting the graph fragments connectivity), while IVF
cluster-based indices partition naturally (each cluster becomes a shard).
Hybrid queries that combine vector similarity with metadata filtering
and full-text search add a second dimension: the partition that owns a
document's key may not be the best partition for its vector search.
The dominant production pattern is **scatter-gather with IVF-aware routing**
for vector queries, combined with **co-located metadata indices** for
filtered search. This file covers the tradeoffs for each approach and
how they interact with range-partitioned LSM-tree storage.

## how-it-works

### the fundamental tension

Range partitioning (used by LSM-tree stores) distributes data by key order.
Vector similarity search distributes queries by geometric proximity in
embedding space. These two orderings are generally unrelated — documents
with adjacent keys can have distant embeddings and vice versa.

This means a system must either:
1. **Co-locate vectors with their documents** (range partitioned) and
   scatter-gather vector queries to all partitions
2. **Partition vectors by similarity** (IVF clusters) and maintain a
   separate routing path for vector queries
3. **Hybrid**: range-partition documents, but maintain per-partition
   local vector indices that are queried in parallel

### vector index partitioning strategies

#### HNSW: does not partition well

HNSW builds a navigable small-world graph where each node connects to
M neighbours across multiple layers. Sharding this graph means cutting
cross-partition edges:

- In a 100M-vector HNSW sharded across 5 nodes, >80% of search steps
  are cross-node traversals
- Options: full replication (memory-expensive) or partial graphs with
  degraded recall
- Best for: single-node or small-cluster, latency-sensitive, RAM-rich

#### IVF: natural distributed partitioning

IVF clusters vectors into coarse buckets (centroids). This provides
**ANN-aware sharding**: each cluster maps to a shard, queries route
only to the `nprobe` closest centroids.

- Deterministic routing: compare query to centroids → select top-nprobe
- Balanced distribution: clusters are explicit shards
- Scaling: add nodes, move clusters — no graph rewiring
- Best for: large-scale distributed deployments

#### per-partition local index (hybrid with range partitioning)

Each range partition maintains its own local vector index (HNSW or flat).
Vector queries scatter to all partitions, each returns local top-k,
coordinator merges results.

- Preserves range partitioning for key-value and range queries
- Vector search latency = slowest partition + merge
- Each partition's index is small enough for HNSW to work well
- Rebalancing (range splits) requires rebuilding local vector indices

### filtered vector search

Combining vector similarity with property filters (e.g., "find similar
images where category='outdoor' AND price < 100") is the hardest
distributed query pattern.

#### pre-filtering (filter → search)

1. Apply metadata filter via inverted index to get candidate set
2. Run ANN search only on candidates

**Pros:** Guarantees k results matching the filter.
**Cons:** If filter is broad, candidate set is huge → ANN degrades to
near-linear scan. If filter is narrow, ANN index structure may be
underutilized.

#### post-filtering (search → filter)

1. Run ANN search for m >> k candidates
2. Apply metadata filter, keep top-k matching

**Pros:** ANN operates at full speed on the complete index.
**Cons:** Restrictive filters discard most results → may return < k.
Requires oversampling factor tuning.

#### in-algorithm filtering (integrated)

Modify the ANN traversal itself to skip filtered-out vectors:

- **Qdrant (Filterable HNSW):** Adds extra intra-category graph links
  to maintain connectivity among filtered nodes
- **Weaviate (ACORN):** Retains unpruned edges, uses two-hop expansions
  to skip filtered nodes without disconnecting the graph
- **Filter fusion:** Concatenate metadata as weighted one-hot vectors
  to the embedding: `[x_i, α·m_i]`. Category mismatches incur
  geometric penalty `2α²`, pushing non-matches out of top-k.
  Eliminates ~80% of non-matching candidates before distance computation.

**Benchmark results (2025):** Integrated filtering systems (Pinecone,
Zilliz) improve throughput 1.2-1.5x under selective filters. Pre/post
fallback systems lose throughput, sometimes reverting to brute-force
(200-300ms P95 vs 30-50ms for integrated).

#### performance by filter selectivity

| Selectivity | Pre-filter | Post-filter | In-algorithm |
|-------------|-----------|-------------|--------------|
| Loose (>50% pass) | Good | Good | Best |
| Medium (10-50%) | Degraded | Needs oversampling | Good |
| Selective (<10%) | Near-linear | Returns < k | Good with ACORN/fusion |
| Very selective (<1%) | Linear scan | Fails | Acceptable with fusion |

### hybrid search: vector + full-text (BM25)

Hybrid retrieval runs vector search and keyword search in parallel,
then fuses results:

1. **Vector path:** ANN search returns top-k by embedding similarity
2. **Keyword path:** BM25/inverted index returns top-k by term relevance
3. **Fusion:** Reciprocal Rank Fusion (RRF) or weighted score combination
   `final = α·vector_score + (1-α)·bm25_score`

In a distributed system, both paths execute on each partition in parallel.
Each partition returns local top-k for both paths, coordinator fuses
globally. This naturally co-locates with range partitioning since both
the inverted index and vector index live on the same partition as the
documents.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| nprobe (IVF) | Clusters searched per query | 1-64 | More = better recall, higher latency |
| M (HNSW) | Max connections per node | 16-64 | More = better recall, more memory |
| efSearch (HNSW) | Candidates examined per query | 100-500 | More = better recall, higher latency |
| oversampling (post-filter) | Fetch m = k * factor | 2-10x | Higher = more likely to get k results |
| α (hybrid fusion) | Vector vs keyword weight | 0.5-0.8 | Task-dependent |

## implementation-notes

### topology for jlsm

Given jlsm's architecture (range-partitioned LSM-tree with remote storage,
document model with secondary indices, existing IvfFlat and HNSW vector
indices), the recommended topology is:

**Per-partition local vector index:**
- Each range partition maintains its own vector index (IvfFlat or HNSW)
- Vector queries scatter to all partitions, merge results at coordinator
- Metadata filters execute locally per partition (co-located with documents)
- Full-text search via `LsmInvertedIndex` also co-located per partition
- Range splits rebuild the local vector index from the partition's documents

**Why this works:**
- Documents, metadata indices, vector indices, and inverted indices are
  all co-located → filtered vector search is partition-local
- No cross-partition graph edges (each partition has its own HNSW/IVF)
- Range queries and key lookups use the standard range routing
- Vector queries use scatter-gather (acceptable when partition count is
  moderate, e.g., 10-100 partitions)

**When it breaks down:**
- Very high vector query throughput with many partitions → scatter-gather
  fan-out becomes expensive
- Partition split/merge requires vector index rebuild (IVF clustering or
  HNSW graph construction)
- Uneven document distribution means uneven vector index sizes

### data-structure-requirements

- **Per-partition:** vector index (IvfFlat or HNSW), inverted index
  (LsmInvertedIndex), secondary indices (FieldIndex), LSM-tree (documents)
- **Coordinator:** range map, scatter-gather query executor, result merger
  (top-k merge for vector, RRF for hybrid)
- **Partition metadata:** vector index type, dimension, index build state

### edge-cases-and-gotchas

- **Filter fusion limits:** High-cardinality metadata causes dimensional
  explosion in the fused vector. Range predicates need bucketing.
- **Dynamic metadata updates:** Changing a document's metadata requires
  updating the vector index if using filter fusion. In-algorithm filtering
  (ACORN/Qdrant style) handles this better.
- **Recall degradation:** Per-partition HNSW with small partitions may have
  insufficient graph density for good recall. Minimum partition size matters.
- **IVF centroid staleness:** If data distribution shifts after clustering,
  centroids become unrepresentative. Periodic re-clustering needed.

## tradeoffs

### strengths

- Co-located indices enable single-partition filtered vector search
- Range partitioning preserved for key-value and range query workloads
- Hybrid search (vector + BM25) executes entirely within partition
- Simple mental model: each partition is a self-contained mini-database

### weaknesses

- Scatter-gather for vector queries scales linearly with partition count
- Partition split/merge triggers vector index rebuild
- Per-partition HNSW graphs are smaller → potentially lower recall vs global
- No global IVF routing optimisation (can't skip irrelevant partitions for
  vector queries without additional metadata)

### compared-to-alternatives

- vs **global IVF sharding** (Milvus/HAKES): better for mixed workloads
  (key-value + vector + text), worse for pure vector throughput
- vs **full replication** of vector index: much less memory, but higher
  vector query latency due to scatter-gather
- vs **separate vector and document stores**: simpler operations (single
  system), but less specialised for either workload

## practical-usage

### when-to-use

Per-partition local vector index when:
- Mixed workload: key-value CRUD + vector search + text search + filters
- Moderate partition count (10-100)
- Documents and their vectors change together (insert/update/delete)
- Filter selectivity varies widely across queries

### when-not-to-use

- Pure vector similarity workload with billions of vectors → use dedicated
  vector database with global IVF sharding
- Very high vector QPS with >100 partitions → scatter-gather overhead
  dominates

## reference-implementations

| System | Strategy | Hybrid Support | Notes |
|--------|----------|---------------|-------|
| Milvus | Global IVF sharding | Vector + BM25 sparse | Disaggregated compute/storage |
| Qdrant | Per-shard HNSW + filterable graph | In-algorithm filtering | Rust, strong filter perf |
| Weaviate | Per-shard HNSW + ACORN | Pre/post/in-algorithm | Two-hop expansion for filtered |
| Elasticsearch | Per-shard HNSW + BM25 | RRF fusion | Established hybrid search |
| SingleStore-V | Per-partition vector index | SQL + vector | Parallel across partitions |

## code-skeleton

```java
// Scatter-gather vector search across range partitions
class DistributedVectorQuery {
    List<ScoredResult> search(float[] queryVector, int k, Predicate filter) {
        List<Partition> partitions = rangeRouter.allPartitions();

        // Scatter: query all partitions in parallel
        List<Future<List<ScoredResult>>> futures = partitions.stream()
            .map(p -> executor.submit(() ->
                p.localVectorSearch(queryVector, k, filter)))
            .toList();

        // Gather: merge top-k from all partitions
        PriorityQueue<ScoredResult> merged = new PriorityQueue<>(
            Comparator.comparingDouble(ScoredResult::score).reversed());
        for (Future<List<ScoredResult>> f : futures) {
            merged.addAll(f.get());
        }

        // Return global top-k
        List<ScoredResult> result = new ArrayList<>(k);
        for (int i = 0; i < k && !merged.isEmpty(); i++) {
            result.add(merged.poll());
        }
        return result;
    }
}

// Per-partition local search: vector + filter in one pass
class Partition {
    List<ScoredResult> localVectorSearch(float[] query, int k, Predicate filter) {
        // Option A: pre-filter via secondary index, then ANN on subset
        // Option B: ANN search, post-filter
        // Option C: in-algorithm filter (ACORN-style)
    }
}
```

## sources

1. [The Achilles Heel of Vector Search: Filters](https://yudhiesh.github.io/2025/05/09/the-achilles-heel-of-vector-search-filters/) — deep analysis of pre/post/in-algorithm filtering with benchmark data across six vector engines
2. [Dense Vector Search: The Hidden Scaling Challenge](https://aakashsharan.com/dense-vector-search-scaling-architecture/) — HNSW vs IVF sharding tradeoffs, cross-partition traversal costs
3. [HARMONY: Scalable Distributed Vector Database (MIT)](https://dspace.mit.edu/bitstream/handle/1721.1/164256/3749167.pdf) — academic evaluation of vector-based and dimension-based partitioning
4. [Comprehensive Survey on Vector Database (arXiv)](https://arxiv.org/html/2310.11703v2) — storage and retrieval techniques across vector database systems
5. [Hybrid Search Explained (Weaviate)](https://weaviate.io/blog/hybrid-search-explained) — BM25 + vector fusion with RRF
6. [IVF vs HNSW for ANN Vector Search (Milvus)](https://milvus.io/blog/understanding-ivf-vector-index-how-It-works-and-when-to-choose-it-over-hnsw.md) — practical guidance on index selection for distributed systems

## Updates 2026-03-16

### Recent papers (2025-2026)

**HAKES: Scalable Vector Database for Embedding Search** (VLDB 2025,
Hu et al.) — disaggregated architecture with two-stage filter-and-refine:
- IndexWorkers hold replicated compressed vectors (4-bit PQ + dim reduction)
  for fast filtering; RefineWorkers hold sharded full-precision vectors
- Two sharding policies: by vector ID (even distribution) or by IVF
  assignment (co-locates same-cluster vectors, reduces cross-node traffic)
- Achieves 16x throughput over baselines (Weaviate, Cassandra) at 0.99 recall
- Key insight: decouple search-index parameters from insert-index parameters
  to handle concurrent read/write workloads

**SIEVE: Effective Filtered Vector Search with Collection of Indexes**
(VLDB 2025, also arXiv) — builds multiple specialized sub-indexes for
different predicate patterns instead of constraining a single graph:
- M-downscaling: smaller sub-indexes need lower M (connectivity), enabling
  more indexes within memory budget (M_h ∝ log(cardinality))
- Greedy index selection maximizes marginal benefit-per-byte under memory
  constraints
- Up to 8x speedup over existing filtered search at <2.15x memory overhead
- Key insight for jlsm: per-partition, build small sub-indexes for common
  filter patterns rather than one monolithic filtered graph

**Attribute Filtering in ANN Search: In-depth Experimental Study**
(arXiv 2025) — comprehensive evaluation of filtering strategies across
HNSW, IVF, Vamana, and specialized structures:
- At <1% selectivity: IVF-based methods and binary search tree structures
  (iRangeGraph) outperform graph-based approaches
- At >50% selectivity: standard HNSW becomes competitive (filtering overhead
  exceeds benefit)
- ACORN (2-hop expansion) most flexible for arbitrary filters
- Key finding: "RNG pruning breaks down at low selectivity" — important for
  HNSW-based per-partition indices with narrow filter predicates

**CrackIVF: Adaptive Vector Search Indexing** (arXiv 2025) — defers
index construction until query patterns emerge:
- CRACK: introduces new partitions based on observed queries
- REFINE: applies localized k-means to optimize centroid placement
- 10-1000x faster startup than pre-built IVF; converges to near-optimal
  after sufficient queries
- Relevant to jlsm: new partitions start with no vector index; CrackIVF
  could build the index incrementally as queries arrive

**Filtered Vector Search: State-of-the-art and Research Opportunities**
(VLDB 2025 Tutorial, Caminal et al.) — identifies core research challenges:
- Graph-based filtered search fails the "just-a-few-hops" property for
  diverse predicates
- Proposes building many indexes for different predicate forms (aligns with
  SIEVE approach)
- Target: user declares desired recall, system auto-configures parameters

**GaussDB-Vector** (VLDB 2025) — large-scale persistent vector database:
- Shards data by both scalar columns and vector columns
- Vectors split into clusters, each assigned to a data node
- Centroids stored on coordinator node for routing

### New sources
1. [HAKES (VLDB 2025)](https://arxiv.org/html/2505.12524v1) — disaggregated filter-refine vector search
2. [SIEVE (VLDB 2025)](https://arxiv.org/html/2507.11907v2) — multi-index filtered vector search
3. [Attribute Filtering in ANN (arXiv 2025)](https://arxiv.org/html/2508.16263v1) — comprehensive filtering benchmark
4. [CrackIVF (arXiv 2025)](https://arxiv.org/html/2503.01823v1) — adaptive deferred index construction
5. [Filtered Vector Search Tutorial (VLDB 2025)](https://dx.doi.org/10.14778/3750601.3750700) — state-of-the-art survey
6. [GaussDB-Vector (VLDB 2025)](https://dbgroup.cs.tsinghua.edu.cn/ligl/papers/VLDB25-GaussVector.pdf) — persistent distributed vector DB

### Corrections
None — initial research findings remain valid. New papers reinforce the
per-partition local index recommendation for mixed workloads. SIEVE and
CrackIVF suggest refinements: build multiple small sub-indexes per partition
for common filter patterns, and consider deferred index construction for
new/cold partitions.

---
*Researched: 2026-03-16 | Next review: 2026-09-16*

---
title: "Compass — General Filtered Search Across Vector and Structured Data"
aliases: ["Compass"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "filtered-search", "hybrid-index", "range-query", "modular"]
complexity:
  time_build: "O(n log n) HNSW + O(n log n) B+-trees + O(n) IVF clustering"
  time_query: "Adaptive — switches strategy based on selectivity"
  space: "~50% of SeRF, ~2.5% of DSG"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2510.27141"
    title: "Compass: General Filtered Search across Vector and Structured Data"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2508.16263v1"
    title: "Attribute Filtering in ANN Search: In-depth Experimental Study (SIGMOD 2026)"
    accessed: "2026-03-30"
    type: "paper"
---

# Compass

## summary

Compass (Oct 2025) is a modular filtered ANN architecture combining three index
structures: HNSW proximity graph for vector similarity, IVF clustering with a cluster
graph for coarse routing, and clustered B+-trees for relational attribute filtering.
A progressive search with shared candidate queue adaptively switches between one-hop
expansion, two-hop expansion, and B+-tree consultation based on neighborhood pass-rate
thresholds. Supports the most general predicate types: conjunctions, disjunctions, and
range queries. Up to 10.71x speedup over NaviX on 3D conjunctions.

## how-it-works

**Three index components:**
1. **HNSW proximity graph:** standard vector similarity navigation
2. **IVF cluster graph:** clusters vectors (10K-20K clusters), builds a navigation graph
   over cluster centroids for coarse routing
3. **Clustered B+-trees:** one per relational attribute, with vectors clustered within
   leaf nodes for cache-friendly access

**Adaptive search strategy (per-hop decision):**
- Monitor neighborhood pass-rate (fraction of visited neighbors passing the filter)
- If pass-rate > alpha (0.3): standard one-hop HNSW expansion (filter is not restrictive)
- If pass-rate < alpha but > beta (0.05): two-hop expansion (similar to ACORN)
- If pass-rate < beta: inject candidates from B+-tree range scan (graph is trapped)

**Counter-intuitive property:** QPS improves as relational filters become more restrictive,
because more pruning means fewer nodes to evaluate.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| M | HNSW max out-degree | 16-32 | Graph quality |
| ef | Search width | 64-256 | Recall vs latency |
| nlist | IVF cluster count | 10,000-20,000 | Cluster granularity |
| alpha | Expansion strategy threshold | 0.3 | Switch to two-hop |
| beta | B+-tree injection threshold | 0.05 | Switch to tree consultation |

## algorithm-steps

### build
1. **HNSW:** build standard proximity graph over all vectors
2. **IVF:** cluster vectors into nlist groups, build navigation graph over centroids
3. **B+-trees:** for each relational attribute, build clustered B+-tree over vector IDs
4. **Index all three** structures; they share vector storage

### query (with predicate)
1. **Initialize** shared candidate queue from HNSW entry point
2. **At each step**, evaluate neighborhood pass-rate:
   a. **High pass-rate (> alpha):** one-hop HNSW expansion, filter inline
   b. **Medium pass-rate (alpha to beta):** two-hop expansion through filtered-out neighbors
   c. **Low pass-rate (< beta):** query B+-tree for attribute ranges, inject matching vector
      IDs into candidate queue
3. **Merge** candidates from all sources in shared priority queue
4. **Return** top-k from queue

### predicate handling
- **Conjunctions (AND):** all filters must pass; B+-tree used for most selective attribute
- **Disjunctions (OR):** union of B+-tree results for each clause
- **Range predicates:** direct B+-tree range scan
- **Mixed:** decompose into conjunctive normal form, process per-clause

## implementation-notes

### data-structure-requirements
- Standard HNSW multi-layer graph
- IVF cluster assignments + centroid graph
- One B+-tree per filterable attribute
- Shared candidate priority queue across all index components

### edge-cases-and-gotchas
- Three threshold parameters (ef, alpha, beta) require tuning for the dataset
- B+-tree consultation adds random reads to a different index structure
- Slightly underperforms specialized single-attribute indices in their specific domain
- Index size is small (~50% of SeRF, 2.5% of DSG) but three structures to maintain

### object-storage-considerations
- HNSW component has same random-read issues as standard HNSW on remote storage
- B+-tree consultation adds additional random reads to a different storage location
- IVF cluster graph is small (in-memory), but the three-structure approach increases
  total I/O diversity on remote storage
- Better suited for local SSD where random read latency is bounded

## complexity-analysis

### build-phase
O(n log n) for HNSW + O(n) for IVF clustering + O(n log n) for B+-trees.

### query-phase
Adaptive. Best case (high pass-rate): O(log n * ef) like HNSW. Worst case (low pass-rate):
additional O(log n) B+-tree lookups per injection. In practice, the B+-tree path is faster
because it skips graph traversal in regions where the graph is trapped.

### memory-footprint
HNSW graph + B+-tree indexes + IVF metadata. Compact: ~50% of SeRF index size.

## tradeoffs

### strengths
- Most general predicate support (conjunctions, disjunctions, ranges, mixed)
- Adaptive strategy handles all selectivity levels without manual tuning
- Counter-intuitive QPS improvement with more restrictive filters
- Small index size relative to alternatives
- Decoupled vector/relational indexing (independent scaling)
- Stable high recall across 1%-100% selectivity

### weaknesses
- Three threshold parameters to tune
- Slightly underperforms specialized single-attribute indices
- Three index structures increase implementation and maintenance complexity
- Not disk/remote-friendly (multiple random read patterns across different structures)
- Newer algorithm with less production validation

### compared-to-alternatives
- vs [ACORN](acorn.md): Compass handles richer predicates; ACORN is simpler (one structure)
- vs [SIEVE](sieve.md): Compass is workload-agnostic; SIEVE is faster for known workloads
- vs [Filtered-DiskANN](filtered-diskann.md): Compass handles range/disjunction; DiskANN is disk-native
- vs [SPANN/SPFresh](spann-spfresh.md): different axis — Compass optimizes predicates, SPANN optimizes storage

## current-research

### key-papers
- Ye et al. "Compass: General Filtered Search across Vector and Structured Data." arXiv:2510.27141, Oct 2025.
- SIGMOD 2026 experimental study (arXiv:2508.16263): independent validation of adaptive strategy.

### active-research-directions
- Disk-based Compass variants
- Integration with learned indexes for attribute filtering
- Extending to full-text predicates (hybrid vector + keyword search)

## practical-usage

### when-to-use
- Complex predicates: range queries, disjunctions, multi-attribute conjunctions
- Variable selectivity across queries
- Dataset fits in memory (or local SSD with bounded latency)
- Need a single system for vector + relational filtering

### when-not-to-use
- Simple equality predicates only (Filtered-DiskANN or CAPS is simpler)
- Remote/object storage backend (use SPANN/SPFresh)
- Known stable workload (SIEVE is faster)
- Need minimal implementation complexity (ACORN is simpler)

## code-skeleton

```
class CompassIndex:
    hnsw: HnswGraph
    ivf: IvfClusters
    btrees: dict           # attribute_name -> BPlusTree
    alpha: float = 0.3
    beta: float = 0.05

    def build(vectors, attributes):
        hnsw = build_hnsw(vectors, M=32)
        ivf = build_ivf(vectors, nlist=10000)
        for attr_name, attr_values in attributes.items():
            btrees[attr_name] = build_clustered_bptree(attr_values, vectors)

    def query(q, k, ef, predicate):
        candidates = priority_queue()
        visited = set()
        current = hnsw.entry_point
        while len(candidates) < ef:
            neighbors = hnsw.neighbors(current)
            pass_rate = count_passing(neighbors, predicate) / len(neighbors)
            if pass_rate > alpha:
                expand_one_hop(neighbors, predicate, candidates, visited)
            elif pass_rate > beta:
                expand_two_hop(neighbors, predicate, candidates, visited)
            else:
                inject_from_btree(predicate, candidates, visited)
            current = candidates.next_unvisited()
        return candidates.top_k(k)
```

## sources

1. [Compass (arXiv Oct 2025)](https://arxiv.org/html/2510.27141) — full paper
2. [SIGMOD 2026 experimental study](https://arxiv.org/html/2508.16263v1) — independent validation

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

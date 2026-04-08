---
title: "ACORN — Predicate-Agnostic Filtered ANN Search"
aliases: ["ACORN", "Adaptive Connectivity and Organization for Range-filtered NN"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "filtered-search", "hnsw", "predicate-agnostic", "graph-index"]
complexity:
  time_build: "O(n * gamma * log(n) * log(gamma))"
  time_query: "O((d + gamma) * log(s*n) + log(1/s)) where s=selectivity"
  space: "O(n * M * gamma)"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2403.04871v1"
    title: "ACORN: Performant and Predicate-Agnostic Search Over Vector Embeddings and Structured Data"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3654923"
    title: "ACORN (ACM SIGMOD 2024 proceedings)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2509.07789v1"
    title: "Filtered ANN Search: A Unified Benchmark and Systematic Experimental Study"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p5488-caminal.pdf"
    title: "Filtered Vector Search: State-of-the-art and Research Opportunities (VLDB 2025)"
    accessed: "2026-03-30"
    type: "paper"
---

# ACORN

## summary

ACORN (SIGMOD 2024) modifies HNSW graph construction to remain navigable under arbitrary
filter predicates. The key insight: build a denser graph so that the subgraph induced by
any predicate retains connectivity. Unlike pre-filtering (which destroys graph connectivity)
or post-filtering (which wastes work on disqualified vectors), ACORN integrates filtering
into graph traversal via two-hop neighbor expansion. A single index serves all predicates
without knowing the predicate space at build time.

## how-it-works

**The fundamental problem with filtered graph search:**
- **Post-filtering:** search unconstrained, then discard non-matching results. At low
  selectivity, most top-k results are discarded — "recall cliff."
- **Pre-filtering:** remove non-matching nodes before search. Graph hub nodes get removed,
  leaving disconnected components — search gets trapped.

**ACORN's solution — denser graph + two-hop expansion:**

1. During construction, collect M*gamma neighbors per node (vs HNSW's M), where
   gamma = 1/s_min (inverse of minimum expected selectivity)
2. Replace HNSW's RNG-based pruning with a two-hop strategy: keep the nearest M_beta
   edges directly, prune remaining candidates only if reachable as two-hop neighbors
3. At query time, when immediate neighbors are filtered out, expand to two-hop neighbors
   before applying the predicate — pruned neighbors remain discoverable

**ACORN-1 variant:** gamma=1, builds 9-53x faster than full ACORN-gamma with ~5x lower
QPS but still significantly outperforms standard HNSW for filtered search.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| gamma | Neighbor expansion factor (1/s_min) | 1-20 | Higher = better at low selectivity, more memory/build time |
| M | Degree bound for traversed nodes | 16-64 | Standard HNSW parameter |
| M_beta | Compression parameter (direct edges) | M/2 to M | Controls direct vs two-hop reachability |
| ef/efs | Candidate list size at query time | 64-512 | Quality-efficiency tradeoff |

## algorithm-steps

### build
1. **Initialize** hierarchical layer structure (same as HNSW)
2. **For each vector** being inserted:
   a. Find M*gamma approximate nearest neighbors via greedy search
   b. Keep M_beta nearest as direct edges
   c. For remaining candidates, check two-hop reachability — prune only if reachable
   d. Insert into all layers up to randomly assigned level
3. **Result:** graph with ~1.3x the edges of standard HNSW

### query (with predicate)
1. **Enter** at top layer, traverse down (same as HNSW)
2. **At each visited node:** check if neighbors pass the predicate
3. **If too few neighbors pass:** expand to two-hop neighbors of the current node,
   then apply predicate to the expanded set
4. **Truncate** expanded neighbor list to ef candidates, continue greedy search
5. **Return** top-k from candidate set that all satisfy the predicate

## implementation-notes

### data-structure-requirements
- Standard HNSW multi-layer graph structure
- Neighbor lists ~gamma times larger than standard HNSW
- Predicate evaluation function (bitset or callback per node)
- Two-hop expansion requires reading neighbor-of-neighbor lists

### edge-cases-and-gotchas
- Below 1/gamma selectivity, falls back to pre-filtering behavior (no guarantee)
- Two-hop expansion doubles random read amplification per hop — problematic for disk/remote
- No theoretical guarantee on predicate subgraph connectivity (empirical only)
- Memory is ~gamma times standard HNSW — can be significant for large gamma

### filtered-search-predicate-support
- Equality predicates on categorical attributes
- Range predicates on numeric attributes
- Regex and containment predicates
- Arbitrary boolean combinations (AND, OR, NOT)
- **Key advantage:** predicate space need not be known at build time

## complexity-analysis

### build-phase
O(n * gamma * log(n) * log(gamma)) — gamma*log(gamma) factor over standard HNSW.
ACORN-1 (gamma=1): same as HNSW. Full ACORN: up to 11x HNSW build time.

### query-phase
O((d + gamma) * log(s*n) + log(1/s)) where s is selectivity, d is dimensionality.
Sublinear in dataset size for reasonable selectivity.

### memory-footprint
~1.3x standard HNSW for ACORN-1, up to gamma*M edges per node for full ACORN.

## tradeoffs

### strengths
- Single index serves arbitrary predicates (no label knowledge at build time)
- Supports unbounded predicate cardinality including regex/range/contains
- 2-10x higher QPS than alternatives at 0.9 recall on low-cardinality predicates
- Up to 1000x speedup at 25M scale
- Sublinear search despite variable selectivity

### weaknesses
- In-memory algorithm — two-hop expansion requires random access to neighbor lists
- Higher memory than vanilla HNSW (gamma factor on edges)
- No theoretical connectivity guarantee under predicates
- Two-hop expansion doubles random read amplification — poor for remote storage
- Build time up to 11x standard HNSW for high gamma

### compared-to-alternatives
- vs [Filtered-DiskANN](filtered-diskann.md): ACORN is predicate-agnostic (no label knowledge needed), but memory-only; DiskANN is disk-native but limited to ~1000 labels
- vs [SIEVE](sieve.md): ACORN builds one index; SIEVE builds a collection. SIEVE can be 8x faster but requires historical workload
- vs [Compass](compass.md): both handle complex predicates; Compass adds B+-tree for relational attributes
- vs [SPANN/SPFresh](spann-spfresh.md): different axis — ACORN optimizes filtering, SPANN optimizes storage access

## current-research

### key-papers
- Luan et al. "ACORN: Performant and Predicate-Agnostic Search." SIGMOD 2024.
- Sep 2025 benchmark: ACORN-gamma best for equality predicates; label-independent methods offer faster construction and greater update flexibility.
- VLDB 2025 survey: positions ACORN as state-of-the-art for predicate-agnostic filtered search.

### active-research-directions
- SIEVE (Jul 2025): 8x speedup over ACORN via collection-of-indexes approach
- Compass (Oct 2025): modular architecture handling more general predicates
- Integration with disk-based indexes for larger-than-memory filtered search

## practical-usage

### when-to-use
- Unknown or highly variable predicate patterns at query time
- Complex predicates (range, regex, boolean combinations)
- Dataset fits in memory (or can be cached)
- Need a single index to serve all filter types

### when-not-to-use
- Storage is remote/object-based (two-hop expansion = 2x random reads per hop)
- Predicate space is small and known in advance (Filtered-DiskANN is better)
- Stable workload patterns (SIEVE is faster with less memory)
- Need sub-millisecond latency with very low selectivity

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| ACORN | C++ | github.com/MIT-OGP/acorn | MIT, research |

## code-skeleton

```
class AcornIndex:
    graph: HnswGraph      # multi-layer graph with gamma-expanded neighbor lists
    gamma: int             # 1/s_min — neighbor expansion factor

    def build(vectors, M, gamma):
        for vec in vectors:
            neighbors = greedy_search(vec, M * gamma)
            direct = neighbors[:M_beta]
            for remaining in neighbors[M_beta:]:
                if not two_hop_reachable(remaining, direct):
                    direct.append(remaining)
            graph.insert(vec, direct)

    def query(q, k, ef, predicate):
        candidates = priority_queue()
        for node in greedy_traverse(q, ef):
            filtered_neighbors = [n for n in node.neighbors if predicate(n)]
            if len(filtered_neighbors) < threshold:
                # two-hop expansion
                for n in node.neighbors:
                    filtered_neighbors += [nn for nn in n.neighbors if predicate(nn)]
            candidates.add_all(filtered_neighbors)
        return candidates.top_k(k)
```

## sources

1. [ACORN (arXiv)](https://arxiv.org/html/2403.04871v1) — full paper with proofs and experiments
2. [ACORN (ACM SIGMOD 2024)](https://dl.acm.org/doi/10.1145/3654923) — published proceedings
3. [Filtered ANN Benchmark (Sep 2025)](https://arxiv.org/html/2509.07789v1) — comparative evaluation
4. [VLDB 2025 Survey](https://www.vldb.org/pvldb/vol18/p5488-caminal.pdf) — landscape positioning

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

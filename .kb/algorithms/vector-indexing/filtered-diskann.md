---
title: "Filtered-DiskANN / Stitched-DiskANN — Label-Aware Disk-Resident ANN"
aliases: ["Filtered-DiskANN", "StitchedVamana", "FilteredVamana", "DiskANN filtered"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "disk-resident", "filtered-search", "label-aware", "graph-index"]
complexity:
  time_build: "O(n * R * |labels|) for StitchedVamana"
  time_query: "O(L * beam_width) random I/O hops"
  space: "O(n * (R + dim)) on disk; O(beam_width * (R + dim)) in memory"
research_status: "mature"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://dl.acm.org/doi/10.1145/3543507.3583552"
    title: "Filtered-DiskANN: Graph Algorithms for ANN Search with Filters (WWW 2023)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2509.07789v1"
    title: "Filtered ANN Benchmark (Sep 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2602.11443v1"
    title: "Filtered ANN in Vector Databases: System Design and Performance Analysis (Feb 2026)"
    accessed: "2026-03-30"
    type: "paper"
---

# Filtered-DiskANN

## summary

Filtered-DiskANN (WWW 2023, Microsoft Research) incorporates label information directly
into Vamana graph construction so that the subgraph induced by any single label remains
well-connected and navigable. Two variants exist: FilteredVamana (single-graph, label-aware
routing from medoids) and StitchedVamana (per-label subgraphs stitched into a unified
graph). The SSD-friendly co-located layout (vector + neighbor list per node) makes it the
most disk-compatible filtered ANN algorithm, though it is limited to equality predicates
on categorical attributes with cardinality under ~1000.

## how-it-works

### FilteredVamana
1. Compute a medoid (geometric center) for each label's vector subset
2. During graph construction, route from each of the new node's label-specific medoids
3. Use RobustPrune with label overlap as a factor — edges between same-label nodes preferred
4. Result: single graph where each label's subgraph is well-connected

### StitchedVamana
1. Build a separate Vamana subgraph per label (degree R_stitched)
2. "Stitch" subgraphs into unified graph — multi-label points serve as bridge nodes
3. Run RobustPrune on every node exceeding degree R to control final index size

### disk layout
Each node's record on disk contains: vector data + neighbor list + label set. A single
random read retrieves everything needed to process that node. This co-located layout is
the key advantage for disk-based operation.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| R | Maximum out-degree | 32-128 | Higher = better recall, more disk per node |
| L | Search list size during build | 100-200 | Build quality vs time |
| alpha | Pruning parameter | 1.0-1.2 | Controls long-range edges |
| R_stitched | Per-label subgraph degree | R/2 to R | StitchedVamana only |
| R_small | Stitching degree | 4-16 | Bridge connectivity |

## algorithm-steps

### build (StitchedVamana)
1. **For each label** l: build Vamana subgraph over vectors with label l (degree R_stitched)
2. **Stitch:** for each node, union edges from all label-specific subgraphs it appears in
3. **Prune:** run RobustPrune on nodes exceeding degree R
4. **Write:** serialize graph to disk with co-located layout (vector + neighbors + labels)

### query (with label filter)
1. **Start** from label-specific medoid (or global entry point)
2. **Beam search:** maintain beam of size L; at each step, read node from disk
3. **Filter:** skip neighbors that do not match the label predicate
4. **Expand:** add matching neighbors to beam, continue until convergence
5. **Return** top-k from beam

## implementation-notes

### data-structure-requirements
- On-disk: co-located node records (vector + adjacency list + label bitset)
- In-memory: medoid lookup table (one per label), beam search working set
- Label bitset per node for fast membership testing

### edge-cases-and-gotchas
- Practical label cardinality limit of ~1000 (each label needs a medoid + possibly a subgraph)
- Only supports equality predicates on labels — no range, regex, or disjunction
- Labels must be known at build time (not predicate-agnostic)
- Build time scales linearly with label count
- StitchedVamana build is parallelizable across labels; FilteredVamana is not

### object-storage-considerations
- Co-located layout means one read per node — translates to one S3 GET per graph hop
- Graph traversal is sequential (hop depends on previous hop) — latency compounds on S3
- Better than ACORN (no two-hop expansion) but worse than SPANN (sequential I/O)
- Possible optimization: batch-prefetch the beam's neighbor lists speculatively

## complexity-analysis

### build-phase
O(n * R) per label for StitchedVamana, O(n * R * |labels|) total.
FilteredVamana: O(n * R * log n) with label-aware routing overhead.

### query-phase
O(L * beam_width) disk reads. Each read fetches one co-located node record.

### memory-footprint
In-memory: O(|labels| * dim) for medoids + O(L * (R + dim)) working set.
On-disk: O(n * (R + dim + |labels|/8)) for full graph.

## tradeoffs

### strengths
- Natively disk/SSD-friendly (co-located layout, single read per node)
- Strong recall at low selectivity for known labels
- 6x better than IVF inline processing at 90% recall
- Well-understood from DiskANN lineage

### weaknesses
- Label cardinality limit (~1000 distinct labels)
- Only equality predicates on categorical attributes
- Labels must be known at build time
- Graph traversal = sequential random reads (poor for high-latency remote storage)
- Build time scales with label count

### compared-to-alternatives
- vs [ACORN](acorn.md): DiskANN is disk-native but label-limited; ACORN handles arbitrary predicates but is memory-only
- vs [SPANN/SPFresh](spann-spfresh.md): DiskANN has finer-grained search but random I/O; SPANN has sequential I/O
- vs [Compass](compass.md): DiskANN is simpler but far less general in predicate support

## current-research

### key-papers
- Gollapudi et al. "Filtered-DiskANN: Graph Algorithms for ANN Search with Filters." WWW 2023.
- FreshDiskANN: streaming updates via merge-based approach (predecessor to SPFresh's direct approach).
- OdinANN (FAST 2026): direct-insert alternative to FreshDiskANN's batch merge.

### active-research-directions
- Extending beyond equality predicates (combining with B+-tree for range queries)
- OdinANN's direct-insert approach for more stable update latency
- GaussDB-Vector (VLDB 2025): production Vamana variant at Huawei

## practical-usage

### when-to-use
- Categorical label filtering with <1000 distinct labels
- Dataset on SSD (not remote/object storage)
- Labels known at index build time
- Need disk-resident index with strong filtered recall

### when-not-to-use
- High-cardinality or unknown predicates (use ACORN)
- Object storage backend (use SPANN/SPFresh)
- Range or boolean predicates needed (use Compass)
- Streaming updates needed without rebuild (use SPFresh)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| DiskANN | C++ | github.com/microsoft/DiskANN | Microsoft, active |

## code-skeleton

```
class FilteredDiskAnnIndex:
    graph: DiskGraph       # co-located node records on disk
    medoids: dict          # label -> medoid node id

    def build(vectors, labels, R, L, alpha):
        for label in unique(labels):
            subset = vectors_with_label(label)
            medoids[label] = geometric_medoid(subset)
            subgraph = build_vamana(subset, R_stitched, L, alpha)
            stitch_into(graph, subgraph)
        prune_all(graph, R)  # RobustPrune on over-degree nodes
        write_colocated(graph)  # vector + neighbors + labels per node

    def query(q, k, L, label_filter):
        entry = medoids.get(label_filter, global_entry)
        beam = [entry]
        visited = set()
        while beam has unexplored nodes:
            node = read_from_disk(beam.pop())
            visited.add(node)
            for neighbor in node.neighbors:
                if label_filter in neighbor.labels and neighbor not in visited:
                    beam.add(neighbor)
            beam.keep_top(L)
        return beam.top_k(k)
```

## sources

1. [Filtered-DiskANN (WWW 2023)](https://dl.acm.org/doi/10.1145/3543507.3583552) — foundational paper
2. [Filtered ANN Benchmark (Sep 2025)](https://arxiv.org/html/2509.07789v1) — comparative evaluation
3. [System Design Analysis (Feb 2026)](https://arxiv.org/html/2602.11443v1) — production system comparison

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

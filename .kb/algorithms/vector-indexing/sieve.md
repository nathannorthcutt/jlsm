---
title: "SIEVE — Collection-of-Indexes Filtered ANN"
aliases: ["SIEVE"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "filtered-search", "collection-index", "workload-aware", "graph-index"]
complexity:
  time_build: "O(sum of per-index HNSW build costs) + O(submodular optimization)"
  time_query: "O(HNSW query on smallest matching sub-index)"
  space: "< 2.15x standard HNSW (memory-budgeted)"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2507.11907"
    title: "SIEVE: Effective Filtered Vector Search with Collection of Indexes"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2509.07789v1"
    title: "Filtered ANN Benchmark (Sep 2025)"
    accessed: "2026-03-30"
    type: "paper"
---

# SIEVE

## summary

SIEVE (Jul 2025) builds a memory-budgeted collection of specialized HNSW indexes, each
targeting a subset of the data defined by a filter predicate observed in historical
workloads. A submodular optimization (GreedyRatio) selects which indexes to build for
maximum benefit per memory unit. At query time, a DAG-based router finds the smallest
sub-index whose filter subsumes the query filter. Achieves up to 8x speedup over ACORN
with memory under 2.15x standard HNSW.

## how-it-works

**Index selection (offline, one-time):**
1. Analyze historical query workload to extract observed filter predicates
2. Build a DAG of candidate indexes organized by logical subsumption (filter A subsumes B
   if every vector matching B also matches A)
3. Run GreedyRatio: iteratively select the index with highest unit marginal benefit
   (improvement / memory cost) until memory budget is exhausted
4. Build HNSW sub-indexes for each selected filter predicate

**Query routing (online):**
1. BFS through the index DAG to find the smallest sub-index whose filter subsumes the query
2. Compare estimated cost of indexed search vs brute-force on the filtered subset
3. Execute whichever is cheaper
4. HNSW parameters (M, ef) are downscaled per sub-index based on subset size

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Memory budget | Total memory for all indexes | 1.5-3x base HNSW | Constrains how many sub-indexes |
| M | HNSW degree (downscaled per index) | 8-32 | Per-index quality |
| sef | Search exploration factor (downscaled) | 32-256 | Per-index recall |
| Workload sample | Historical queries for optimization | 1000+ queries | Quality of index selection |

## algorithm-steps

### build
1. **Extract** unique filter predicates from historical workload
2. **Build DAG** of filter subsumption relationships
3. **GreedyRatio:** while memory budget allows:
   a. For each candidate index, compute marginal benefit / memory cost
   b. Select highest ratio candidate
   c. Build HNSW sub-index for that filter predicate
4. **Always include** base index covering all data (fallback)

### query
1. **Route:** BFS on DAG from query filter → find smallest matching sub-index
2. **Estimate:** compare indexed search cost vs brute-force cost on filtered subset
3. **Execute:** search selected HNSW sub-index (or brute-force if cheaper)
4. **Return** top-k results

## implementation-notes

### data-structure-requirements
- Multiple HNSW indexes (each a standard HNSW on a data subset)
- DAG of filter subsumption for routing
- Cost model for search vs brute-force decision
- Historical workload log for optimization

### edge-cases-and-gotchas
- Relies on filter stability assumption: future workload must resemble historical
- Complex predicate spaces reduce subsumption opportunities (fewer reusable indexes)
- New filter patterns not seen in workload fall back to base index
- Requires representative historical workload — cold-start problem for new deployments

## complexity-analysis

### build-phase
Sum of per-index HNSW build costs + submodular optimization (polynomial in candidate count).
Construction overhead is low relative to total index time.

### query-phase
O(HNSW query on subset) — typically faster than full HNSW because sub-indexes are smaller.
Routing: O(DAG BFS) which is fast (tens of nodes typically).

### memory-footprint
Budgeted: user-specified maximum. Typically < 2.15x standard HNSW.
Each sub-index stores only its subset's vectors + graph.

## tradeoffs

### strengths
- Up to 8.06x speedup over ACORN
- Memory-budgeted — controllable overhead
- Predicate-agnostic framework (works for any filter type seen in workload)
- Handles the "unhappy middle" selectivities well (where both pre- and post-filter struggle)
- Theoretical grounding in small-world network theory
- Low construction overhead relative to total index time

### weaknesses
- Requires representative historical workload (cold-start problem)
- Complex predicate spaces reduce subsumption opportunities
- New unseen filter patterns fall back to base index
- Multiple indexes increase memory and management complexity
- Not inherently disk-friendly (each sub-index is in-memory HNSW)

### compared-to-alternatives
- vs [ACORN](acorn.md): 8x faster but requires historical workload; ACORN is workload-agnostic
- vs [Filtered-DiskANN](filtered-diskann.md): SIEVE is memory-only but handles arbitrary predicates
- vs [Compass](compass.md): SIEVE optimizes for observed workload; Compass is workload-agnostic

## current-research

### key-papers
- "SIEVE: Effective Filtered Vector Search with Collection of Indexes." arXiv:2507.11907, Jul 2025.
- Sep 2025 benchmark: validated SIEVE's speedup claims across multiple datasets.

### active-research-directions
- Online workload adaptation (re-optimizing index collection as queries evolve)
- Combining with disk-based indexes for larger-than-memory operation
- Integration with partition-based approaches (SPANN + SIEVE routing)

## practical-usage

### when-to-use
- Stable, well-characterized query workload
- Memory budget allows 1.5-3x base index size
- Dataset fits in memory
- Need maximum QPS for known filter patterns

### when-not-to-use
- Cold-start / no historical workload available (use ACORN)
- Storage is remote/object-based (use SPANN/SPFresh)
- Memory-constrained (base HNSW + sub-indexes may exceed budget)
- Rapidly changing filter patterns

## code-skeleton

```
class SieveIndex:
    base_index: HnswIndex           # fallback covering all data
    sub_indexes: dict                # filter_predicate -> HnswIndex
    routing_dag: DAG                 # subsumption relationships

    def build(vectors, workload_log, memory_budget):
        predicates = extract_unique_predicates(workload_log)
        dag = build_subsumption_dag(predicates)
        selected = greedy_ratio(dag, memory_budget)
        base_index = build_hnsw(vectors)
        for pred in selected:
            subset = vectors.filter(pred)
            sub_indexes[pred] = build_hnsw(subset, downscaled_params(len(subset)))

    def query(q, k, predicate):
        matching_index = dag.bfs_smallest_subsumer(predicate)
        if matching_index and cost_estimate(matching_index) < brute_force_cost(predicate):
            return matching_index.search(q, k)
        else:
            return brute_force(vectors.filter(predicate), q, k)
```

## sources

1. [SIEVE (arXiv Jul 2025)](https://arxiv.org/html/2507.11907) — full paper
2. [Filtered ANN Benchmark (Sep 2025)](https://arxiv.org/html/2509.07789v1) — validation

## Updates 2026-04-13

### HAKES Filter-Refine as Alternative Architecture (VLDB 2025)

HAKES (arXiv:2505.12524, PVLDB 2025) takes a fundamentally different approach to
filtered search than SIEVE: instead of building specialized per-predicate indexes,
it uses a disaggregated filter-refine pipeline with compressed vectors in the filter
stage. This creates a useful contrast point.

**How it changes the recommendation:**
- SIEVE remains superior for known, stable workloads where filter patterns repeat —
  the 8x speedup over ACORN holds because specialized sub-indexes avoid scanning
  irrelevant vectors entirely.
- HAKES is better when: (a) the workload is unpredictable (no historical queries for
  GreedyRatio), (b) the dataset is too large for multiple in-memory HNSW copies, or
  (c) the deployment needs to scale horizontally (stateless filter nodes).
- The two approaches are not mutually exclusive: SIEVE's routing DAG could dispatch to
  HAKES-style filter-refine for unseen predicates instead of falling back to the base
  index.

**Comparison summary:**

| Dimension | SIEVE | HAKES |
|-----------|-------|-------|
| Cold-start | Needs workload history | Works immediately |
| Memory | 1.5-3x base (budgeted) | Compressed filter fits in memory |
| Scaling | Single-node (in-memory) | Disaggregated, horizontal |
| Best for | Known predicates, max QPS | Unknown predicates, large scale |
| Throughput | 8x over ACORN | 16x over graph baselines |

### Attribute Filtering Benchmark Validation (arXiv 2025)

The comprehensive attribute filtering benchmark (referenced in data-partitioning Key
Papers) provides independent validation of SIEVE's positioning:

- IVF-based approaches perform best at low selectivity (large result sets) — this is
  where SIEVE's per-predicate sub-indexes also excel, since each sub-index is
  effectively a pre-filtered IVF partition.
- At high selectivity (small result sets), brute-force on the filtered subset wins —
  consistent with SIEVE's cost-model fallback that chooses brute-force when the
  filtered subset is small enough.
- Confirms that no single algorithm dominates across all selectivity ranges, validating
  SIEVE's adaptive routing approach.

**Implications for jlsm-vector:**
- If jlsm-vector implements SIEVE-style routing, the fallback path for unseen predicates
  could use HAKES-style compressed-vector filtering rather than full base-index search.
- The cost model for "indexed vs brute-force" decision should incorporate selectivity
  estimates, as the benchmark confirms this is the primary determinant of optimal strategy.

### Updated Source List

3. [HAKES (PVLDB 2025)](https://arxiv.org/abs/2505.12524) — disaggregated filter-refine alternative
4. [Attribute Filtering in ANN (arXiv 2025)](https://arxiv.org/search/?query=attribute+filtering+ANN+benchmark) — selectivity-based validation

---
*Researched: 2026-03-30 | Updated: 2026-04-13 | Next review: 2026-09-30*

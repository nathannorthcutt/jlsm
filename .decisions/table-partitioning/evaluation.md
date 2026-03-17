---
problem: "table-partitioning"
evaluated: "2026-03-16"
candidates:
  - path: ".kb/distributed-systems/data-partitioning/partitioning-strategies.md"
    name: "Range Partitioning with Per-Partition Co-located Indices"
  - path: ".kb/distributed-systems/data-partitioning/partitioning-strategies.md"
    name: "Hash Partitioning (Consistent Hashing)"
  - path: ".kb/distributed-systems/data-partitioning/vector-search-partitioning.md"
    name: "Global IVF Sharding (Milvus/HAKES-style)"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 2
  accuracy: 2
  operational: 2
  fit: 3
---

# Evaluation — table-partitioning

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The partitioning strategy must support efficient combined queries across all
modalities (property, full-text, vector, and combinations) on a distributed
LSM-tree backed document store. It must be composable within jlsm's existing
JPMS module structure and Java-only stack, and must not lose data during
partition operations. The combined query efficiency constraint is the most
narrowing — it effectively requires co-location of all index types with the
documents they reference.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Must scale horizontally, but exact volumes TBD |
| Resources | 1 | Pure Java + remote storage is a given, not a differentiator |
| Complexity | 2 | Small team, composable library — complexity matters |
| Accuracy | 2 | Exact for property/text, approximate for vector — both must work |
| Operational | 2 | Non-blocking splits, replication goal |
| Fit | 3 | Must integrate with existing jlsm modules — highest weight |

---

## Candidate 1: Range Partitioning with Per-Partition Co-located Indices

**KB source:** [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) + [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md)
**Relevant sections read:** `#range-partitioning`, `#tradeoffs`, `#topology-for-jlsm`, `#filtered-vector-search`, `#hybrid-search`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Dynamic splitting scales horizontally; CockroachDB/TiKV/FoundationDB production-proven (partitioning-strategies#range-partitioning) |
| Resources | 1 | 5 | 5 | Pure sorted key ranges map directly to LSM-tree SSTables; no GPU needed (partitioning-strategies#why-it-fits-lsm-trees) |
| Complexity | 2 | 3 | 6 | More complex than hash (range map, meta-ranges, split logic) but well-documented pattern (partitioning-strategies#edge-cases-and-gotchas) |
| Accuracy | 2 | 5 | 10 | Property/range queries exact within partition; vector ANN per-partition with scatter-gather merge; full-text co-located (vector-search-partitioning#topology-for-jlsm) |
| Operational | 2 | 4 | 8 | Splits are key-range midpoint operations; epoch mechanism detects staleness; vector index rebuild on split is a cost (vector-search-partitioning#edge-cases-and-gotchas) |
| Fit | 3 | 5 | 15 | LSM-tree sorted order preserved; all jlsm index types co-locate per partition; composable as a routing layer above existing JlsmTable (vector-search-partitioning#topology-for-jlsm) |
| **Total** | | | **52** | |

**Hard disqualifiers:** None.

**Key strengths for this problem:**
- **Combined query efficiency (binding constraint #1):** All indices (secondary, vector, inverted) co-located with documents on the same partition. Filtered vector search, hybrid vector+text, and property-only queries all execute partition-locally without cross-partition joins.
- **Fit (highest weight):** Range boundaries align with LSM-tree SSTable key ranges. Existing `StandardLsmTree`, `FieldIndex`, `LsmVectorIndex`, and `LsmInvertedIndex` all operate on a key-sorted document set — a range partition is exactly that.
- **SIEVE/CrackIVF enhancements:** Per-partition, can build multiple small sub-indexes for common filter patterns (SIEVE, VLDB 2025) and defer vector index construction on new partitions (CrackIVF, arXiv 2025).

**Key weaknesses for this problem:**
- **Vector query fan-out:** Scatter-gather to all P partitions for vector-only queries. Acceptable at 10-100 partitions; problematic at 1000+.
- **Split cost:** Range splits require rebuilding the local vector index (HNSW graph or IVF clusters) for the affected partition.
- **Sequential insert hotspot:** Mitigable with prefix hashing on the partition key.

---

## Candidate 2: Hash Partitioning (Consistent Hashing)

**KB source:** [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md)
**Relevant sections read:** `#hash-partitioning`, `#consistent-hashing`, `#tradeoffs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Consistent hashing handles node addition/removal well; only k/n keys move (partitioning-strategies#consistent-hashing) |
| Resources | 1 | 4 | 4 | Simple implementation, low metadata overhead (partitioning-strategies#hash-partitioning) |
| Complexity | 2 | 4 | 8 | Simpler than range partitioning — hash function determines placement (partitioning-strategies#tradeoffs) |
| Accuracy | 2 | 2 | 4 | **Range scans touch ALL partitions** — O(N) fan-out. Property range queries, ordered scans, and secondary index range lookups all degrade severely (partitioning-strategies#why-it-hurts-lsm-trees) |
| Operational | 2 | 3 | 6 | No split/merge logic needed, but rebalancing requires data movement; no hotspot protection for range queries |
| Fit | 3 | 1 | 3 | **Destroys LSM-tree key ordering** — the fundamental invariant jlsm depends on. Secondary indices, range queries, and ordered scans all broken at the distribution layer (partitioning-strategies#compared-to-alternatives) |
| **Total** | | | **33** | |

**Hard disqualifiers:**
- **Combined query efficiency:** Property range queries (`price BETWEEN 100 AND 500`) require visiting ALL partitions because hash destroys key ordering. This violates the binding constraint.
- **Fit:** jlsm's entire stack assumes sorted key order — MemTable (skip list), SSTable (sorted blocks), compaction (sorted merge), secondary indices (sorted composite keys). Hash partitioning breaks this at the distribution layer.

**Key strengths for this problem:**
- Simpler implementation, even load distribution for point lookups

**Key weaknesses for this problem:**
- Fatal for combined queries — every range-based operation becomes O(N) fan-out
- Destroys the sorted-order invariant that jlsm is built around

---

## Candidate 3: Global IVF Sharding (Milvus/HAKES-style)

**KB source:** [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md)
**Relevant sections read:** `#ivf-natural-distributed-partitioning`, `#hakes`, `#compared-to-alternatives`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Purpose-built for billion-scale vector search; HAKES achieves 16x throughput (vector-search-partitioning#hakes) |
| Resources | 1 | 3 | 3 | Requires separate IndexWorkers and RefineWorkers; more infrastructure than co-located approach (vector-search-partitioning#hakes) |
| Complexity | 2 | 2 | 4 | Disaggregated architecture with separate node types; dual sharding policies; significantly more complex to operate (vector-search-partitioning#hakes) |
| Accuracy | 2 | 4 | 8 | Excellent vector recall (0.99 with refine stage); but property/text queries require separate routing path or cross-system join (vector-search-partitioning#compared-to-alternatives) |
| Operational | 2 | 3 | 6 | Excellent for pure vector; but combined queries require coordinating across document store + vector store (vector-search-partitioning#compared-to-alternatives) |
| Fit | 3 | 1 | 3 | **Separates vectors from documents** — requires maintaining two separate distributed systems (one for documents + properties + text, one for vectors). Violates composability constraint and combined query efficiency. (vector-search-partitioning#compared-to-alternatives) |
| **Total** | | | **34** | |

**Hard disqualifiers:**
- **Combined query efficiency:** Filtered vector search requires joining results from the vector shard system with the document store. A query like "find similar images where category='outdoor'" needs to: (1) run ANN on vector shards, (2) fetch metadata from document partitions, (3) filter. This is a cross-system join on every combined query.
- **Fit:** jlsm is a single composable library. Global IVF sharding is a separate distributed system architecture that doesn't compose with the existing module structure.

**Key strengths for this problem:**
- Best pure vector throughput at extreme scale (16x over baselines)
- ANN-aware routing reduces fan-out for vector-only queries

**Key weaknesses for this problem:**
- Fatal for combined queries — vectors separated from documents
- Requires separate infrastructure for vector and document storage
- Does not compose with jlsm's single-library design

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [Range + Co-located](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) | partitioning-strategies + vector-search-partitioning | 8 | 5 | 6 | 10 | 8 | 15 | **52** |
| [Hash (Consistent)](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) | partitioning-strategies | 8 | 4 | 8 | 4 | 6 | 3 | **33** |
| [Global IVF](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md) | vector-search-partitioning | 10 | 3 | 4 | 8 | 6 | 3 | **34** |

## Preliminary Recommendation
**Range Partitioning with Per-Partition Co-located Indices** wins with a weighted total of 52, significantly ahead of Hash (33) and Global IVF (34). The gap is driven by the Fit (15 vs 3) and Accuracy (10 vs 4) dimensions — both of which are consequences of the binding "combined query efficiency" constraint. Hash and Global IVF both have hard disqualifiers against this constraint.

## Risks and Open Questions
- **Vector fan-out at high partition counts:** If partition count exceeds ~100, scatter-gather for vector queries may become a bottleneck. Mitigation: partition-level metadata (min/max vectors, cluster summaries) could allow skipping irrelevant partitions.
- **Split cost for vector indices:** Range splits force vector index rebuild. CrackIVF (deferred construction) mitigates startup cost but doesn't eliminate rebuild entirely.
- **Sequential insert hotspot:** Common in time-series workloads. Mitigable with prefix hashing or compound partition keys.
- **Replication protocol not decided:** Raft/Paxos per-partition is the expected pattern (TiKV model) but is a separate decision.

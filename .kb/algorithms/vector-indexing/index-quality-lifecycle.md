---
title: "Index Quality Lifecycle — Degradation Detection and Rebuild Strategies"
aliases: ["quality-degradation", "rebuild-detection", "deletion-control", "RP-Tuning"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "quality", "degradation", "rebuild", "lifecycle", "metrics"]
complexity:
  time_build: "varies by strategy"
  time_query: "degraded linearly with tombstone ratio"
  space: "varies by strategy"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2512.06200"
    title: "How Should We Evaluate Data Deletion in Graph-Based ANN Indexes?"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/abs/2602.08097"
    title: "RP-Tuning: Prune, Don't Rebuild (Feb 2026)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3725344"
    title: "EcoTune: Rethinking Compaction Policies in LSM-trees (SIGMOD 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2411.00970v1"
    title: "Ada-IVF: Adaptive Incremental IVF Maintenance (Nov 2024)"
    accessed: "2026-03-30"
    type: "paper"
---

# Index Quality Lifecycle

## summary

All ANN indexes degrade under churn. This file covers *how to detect* degradation, *when
to trigger* maintenance, and *what partial rebuild strategies* exist that avoid full
reconstruction. The Deletion Control algorithm provides a principled framework: alternate
between cheap logical deletion and expensive physical rebuild based on accuracy thresholds.
RP-Tuning (Feb 2026) offers a 43x speedup for graph parameter tuning via pruning instead
of rebuilding. For partition indexes, Ada-IVF's indicator function quantifies drift and
size imbalance per partition.

## how-it-works

### degradation-mechanisms

**Graph indexes (HNSW, DiskANN):**
- **Tombstone overhead:** deleted vertices traversed but excluded from results — wasted
  distance computations. QPS degrades linearly with tombstone ratio
- **Unreachable points:** vertices with outgoing edges but no incoming edges in any layer.
  Accumulates steadily (~3-4% after ~3000 delete/reinsert cycles). Invisible to greedy search
- **Hub degradation:** critical routing vertices deleted, fragmenting search paths
- **Recall degradation:** approximately linear per delete/reinsert step:
  `Delta = (Rs - R0) / S` where Rs = recall after S steps

**Partition indexes (IVF, SPANN):**
- **Centroid drift:** true cluster center shifts from stored centroid as vectors change.
  Measured as `fd = ||mu - mu_0|| / ||mu_0||`
- **Size imbalance:** some postings grow large (slow scan), others shrink (poor coverage)
- **Tombstone accumulation:** wasted I/O reading dead vectors in posting scans
- **Reconstruction error:** `epsilon = avg(dist(v, nearest_centroid))` — the global quality metric

### detection-metrics

| Metric | Type | How to Measure | Threshold Guidance |
|--------|------|----------------|-------------------|
| 1-Recall@k | Global | Sample queries against ground truth | Application-specific |
| Unreachable % | Graph | Periodic reachability sampling | Rebuild at 3-5% |
| Tombstone ratio | Both | Count(deleted) / Count(total) | Compact at 10-15% |
| Reconstruction error | Partition | avg(dist(v, centroid)) | Ada-IVF: tau_G tunable |
| Size std deviation | Partition | stdev(partition_sizes) | Ada-IVF: Gs component |
| QPS-Recall Pareto | Both | Benchmark at current state | Rebuild when curve shifts |

### key-parameters

| Parameter | Description | Source |
|-----------|-------------|--------|
| alpha | Minimum acceptable recall | Deletion Control |
| theta | Recall achievable with physical deletion alone | Deletion Control |
| pi | Max logical deletion steps before rebuild | Deletion Control |
| tau_G | Global maintenance trigger threshold | Ada-IVF |
| tau_f | Per-partition re-clustering threshold | Ada-IVF |

## algorithm-steps

### deletion-control (principled rebuild scheduling)
1. **Measure** theta: the minimum stable recall achievable via physical deletion alone
   (no graph/centroid repair, just tombstone removal)
2. **Set** alpha: the application's minimum acceptable recall
3. **If alpha < theta:** physical deletion suffices indefinitely — just remove tombstones
4. **If alpha >= theta:** alternate between:
   a. Logical deletion for pi steps (cheap, recall degrades linearly)
   b. Full rebuild (expensive, restores recall to maximum)
5. **Compute** pi: max steps where recall stays above alpha before rebuild required

### ada-ivf-indicator-function
1. **Per-partition local indicator:**
   ```
   f(c, c0) = fT(c.T) * (beta * fs(c.s, tau_s) + (1-beta) * fd(c, c0))
   ```
   - fT(T) = alpha * T (temperature: query frequency as priority weight)
   - fs: size imbalance factor
   - fd: centroid drift factor
2. **Global indicator:**
   ```
   G = gamma * Gs(sigma) + (1-gamma) * Gd(epsilon, epsilon')
   ```
   Fires when 2.5% of vectors modified. Error estimation accurate within 1-2.5%
3. **Action:** re-cluster only partitions where local f > tau_f

### rp-tuning-prune-dont-rebuild (feb 2026)
1. **Build once** with high alpha parameter (dense graph, high recall)
2. **To reduce graph for speed:** prune edges instead of rebuilding with lower alpha
3. **Preserves** worst-case reachability guarantees
4. **Speedup:** up to 43x faster than rebuilding for parameter exploration
5. **Use case:** tuning recall/speed tradeoff without full rebuild

### partial-rebuild-strategies

| Strategy | Scope | Cost | Quality Guarantee |
|----------|-------|------|------------------|
| Tombstone compaction | Remove dead entries | O(live entries) per posting | Restores I/O efficiency only |
| Centroid update (running mean) | Per-partition | O(1) per update | Approximate — drift accumulates |
| Selective re-clustering | Drifted partitions + rc neighbors | O(rc * partition_size) | Good within re-clustered region |
| Monotonic path repair (Wolverine) | Disrupted search paths | O(2-hop neighbors) per deletion | High — preserves path quality |
| Localized page repair (Greator) | Affected disk pages | O(affected pages) | Good — 96% single-neighbor |
| RP-Tuning (pruning) | Full graph, but prune-only | O(n * degree) | Preserves reachability |
| Full rebuild | Entire index | O(n log n) | Maximum quality restored |

## implementation-notes

### monitoring-for-jlsm
To detect when incremental maintenance is insufficient:
1. **Track tombstone ratio** per posting list / graph segment — cheap, always available
2. **Sample recall periodically** — run k-NN on small ground-truth set; compare to brute force
3. **Track centroid drift** per partition — running mean vs stored centroid distance
4. **Monitor QPS trend** — declining QPS at constant recall suggests structural degradation
5. **Count unreachable points** (graph only) — periodic BFS from entry point, count unreached

### lsm-compaction-analogy
The Deletion Control algorithm maps directly to LSM compaction scheduling:
- **Logical deletion** = tombstone markers in SSTable (cheap write, degrades read)
- **Physical rebuild** = major compaction (expensive, restores read performance)
- **pi (max steps)** = compaction trigger threshold
- **EcoTune (SIGMOD 2025):** treats compaction as investment for future query throughput
  via dynamic programming — same framework applies to vector index maintenance

### edge-cases-and-gotchas
- Recall measurement requires ground truth — expensive to maintain at scale
- Sampling-based recall estimation has variance; use confidence intervals
- Tombstone ratio alone is insufficient — centroid drift can degrade recall even at 0% tombstones
- RP-Tuning only works for graph indexes with alpha-reachability guarantees
- Temperature scaling (Ada-IVF) biases toward hot partitions — cold partition drift goes undetected

## tradeoffs

### strengths
- Principled framework for rebuild scheduling (not ad-hoc thresholds)
- Quantitative drift detection (Ada-IVF) enables proactive maintenance
- RP-Tuning eliminates redundant rebuilds for parameter exploration
- Partial strategies cover 80-90% of quality issues at fraction of full rebuild cost

### weaknesses
- Ground-truth recall measurement is expensive at scale
- All partial strategies eventually need full rebuild
- No single metric captures all degradation modes (need multiple signals)
- Deletion Control assumes linear recall degradation (may not hold for all distributions)

## practical-usage

### when-to-use
- Any ANN index under continuous insert/delete workload
- Want to minimize rebuild frequency while maintaining recall guarantees
- Need to distinguish "compact tombstones" from "rebuild structure" decisions

### when-not-to-use
- Static dataset (no degradation)
- Recall requirements are loose (degradation may be acceptable indefinitely)
- Full rebuild is cheap relative to query volume (just rebuild on schedule)

## sources

1. [Deletion evaluation (2025)](https://arxiv.org/html/2512.06200) — Deletion Control algorithm
2. [RP-Tuning (Feb 2026)](https://arxiv.org/abs/2602.08097) — prune-based graph tuning, 43x speedup
3. [EcoTune (SIGMOD 2025)](https://dl.acm.org/doi/10.1145/3725344) — compaction as investment framework
4. [Ada-IVF (Nov 2024)](https://arxiv.org/html/2411.00970v1) — indicator-driven quality detection

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

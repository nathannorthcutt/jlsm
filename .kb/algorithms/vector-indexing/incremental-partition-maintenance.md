---
title: "Incremental Partition Index Maintenance — LIRE, Ada-IVF, UBIS, CrackIVF"
aliases: ["partition-maintenance", "LIRE", "Ada-IVF", "UBIS", "CrackIVF", "centroid-drift"]
topic: "algorithms"
category: "vector-indexing"
tags: ["ann", "partition-index", "incremental-update", "tombstone", "compaction", "centroid-drift"]
complexity:
  time_build: "O(1) per insert; O(k) per split/merge; O(rc * partition_size) per reindex"
  time_query: "O(nprobe * posting_size) — same as base IVF"
  space: "1x base + tombstones; 5.47x with SPANN boundary replication"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://arxiv.org/html/2410.14452v1"
    title: "SPFresh: Incremental In-Place Update (SOSP 2023)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2411.00970v1"
    title: "Ada-IVF: Adaptive Incremental IVF Index Maintenance (Nov 2024)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2602.00563v1"
    title: "UBIS: Updatable Balanced Index for Stable Streaming (Jan 2026)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://arxiv.org/html/2503.01823v1"
    title: "CrackIVF: Cracking Vector Search Indexes (Mar 2025)"
    accessed: "2026-03-30"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p4951-sun.pdf"
    title: "GaussDB-Vector: Large-Scale Persistent Real-Time (VLDB 2025)"
    accessed: "2026-03-30"
    type: "paper"
---

# Incremental Partition Index Maintenance

## summary

Partition-based (IVF/SPANN) indexes degrade as insert/delete patterns cause centroid
drift, posting size imbalance, and tombstone accumulation. Four systems address this
without full rebuild: LIRE (SPFresh) uses split/merge/reassign with NPA invariant
maintenance; Ada-IVF uses indicator-driven selective re-clustering; UBIS optimizes
lock design and split operations; CrackIVF adapts database cracking to amortize index
refinement across queries. All share the pattern of lazy deletion + background
compaction, with the key differentiator being *how* and *when* they detect and respond
to quality degradation.

## how-it-works

### four-approaches-compared

| System | Detection | Response | Granularity | Object Storage Fit |
|--------|-----------|----------|-------------|-------------------|
| LIRE (SPFresh) | Size thresholds | Split/merge/reassign | Per-posting | Good (append-only) |
| Ada-IVF | Indicator function (drift + size) | Selective re-clustering | Per-partition + neighbors | Good (batch rewrite) |
| UBIS | Balance factor + size bounds | Split/merge with improved locks | Per-posting | Good (atomic swap) |
| CrackIVF | Query-driven (amortized) | Crack + refine during queries | Per-partition | Moderate (extra I/O) |

### key-parameters

| Parameter | LIRE | Ada-IVF | UBIS |
|-----------|------|---------|------|
| Split threshold | Posting > max length | Local indicator > tau_f | > 80 vectors |
| Merge threshold | Posting < min length | (part of reindex) | < 10 vectors |
| Reassign scope | 64 nearest postings | Radius rc=25 neighbors | Balance factor 0.15 |
| Maintenance trigger rate | 0.4% inserts → split | 2.5% total modifications | Periodic background |
| Target partition size | Not specified | 1000 (500-2000 range) | ~40 (split at 80) |
| Thread model | 2:1 foreground:background | Synchronous batches | 1 fg : 4 bg : 4 search |

## algorithm-steps

### lire-protocol (spfresh)
1. **Insert (foreground):** assign to nearest centroid via graph search; append to posting
2. **Delete (foreground):** set 1-bit deletion flag in version map; skipped during search
3. **Split (background):** when posting exceeds max length, balanced k-means into two new
   postings with new centroids. Triggered by only 0.4% of inserts
4. **Merge (background):** when posting falls below min length, combine with nearest. 0.1% rate
5. **Reassign (background):** after split/merge, evaluate vectors in 64 nearest postings.
   Average: 5,094 evaluated, 79 moved (~1.5%). Two distance conditions:
   - Condition 1: vectors in old posting — should they stay?
   - Condition 2: vectors in nearby postings — should they move in?
6. **NPA invariant:** each vector must reside in posting of its nearest centroid

### ada-ivf-indicator-driven (nov 2024)
1. **Track per-partition:** running mean (incremental centroid), size, query frequency
2. **Compute local indicator:** `f = fT(T) * (beta * fs(size, tau_s) + (1-beta) * fd(drift))`
   where `fT = alpha * T` (temperature scaling — hot partitions prioritized)
3. **Global trigger:** `G = gamma * Gs(size_stdev) + (1-gamma) * Gd(error, error')` > tau_G
   fires when 2.5% of vectors modified
4. **Selective re-cluster:** only partitions where local indicator > tau_f
   a. Split violating partitions via balanced k-means
   b. Identify neighbors within radius rc=25
   c. Iterative k-means initialized with previous centroids
5. **Centroid update (between re-clusters):** `mu_{i+1} = mu_i + (s_delta/(s_i+s_delta)) * (mu_delta - mu_i)`

### ubis-balanced (jan 2026)
1. **Insert:** assign to nearest posting; append
2. **Delete:** tombstone in Posting Recorder (2-bit status + 16-bit version)
3. **Split:** when posting > 80 vectors; improved lock design via CAS on 8-byte entries
4. **Merge:** when posting < 10 vectors; combine with nearest
5. **Balance factor 0.15:** determines reassignment aggressiveness after structural changes
6. **Snapshot isolation:** global version counter; readers see consistent version

### crackivf-query-driven (mar 2025)
1. **No upfront build:** index starts empty or minimal
2. **On each query:** identify relevant partitions; partially refine (crack) them
3. **Budget model:** cracking consumes at most alpha * total_runtime (default 0.5)
4. **Progressive:** each query improves index quality for future queries
5. **Converges** toward quality of fully-built index over time

## implementation-notes

### tombstone-compaction-patterns
- **Lazy deletion:** all systems use tombstone-then-compact. Vector marked deleted,
  skipped during search, physically removed during background compaction
- **Garbage ratio:** SPFresh triggers split when posting oversized (implicitly bounds
  garbage); Ada-IVF triggers at 2.5% total modification; GaussDB-Vector at 10% shifted
- **Copy-on-write posting rewrite:** SPFresh's Block Controller writes new blocks, then
  atomically swaps block mapping via CAS. Old blocks remain readable until all readers finish
- **Version-based GC:** SPFresh uses 7-bit reassign version + 1-bit deletion per vector.
  Readers capture version at query start; stale versions invisible
- **Space amplification:** SPANN boundary replication averages 5.47 replicas/vector (86%
  have >1 replica). Merge operations clean excess replicas

### centroid-drift-detection
Ada-IVF formalizes drift as reconstruction error:
```
epsilon = avg(dist(v, nearest_centroid))  -- global
fd = ||mu - mu_0|| / ||mu_0||            -- per-partition
```
Drift threshold tau_f determines when partial re-clustering fires. Error estimation
accurate within 1-2.5%. Without maintenance, recall degrades as drift grows.

### lsm-tree-parallels
- **Posting rewrite ≈ compaction:** merge valid entries into new structure, discard tombstones
- **Append-only inserts ≈ WAL/MemTable:** SPFresh Block Controller does read-modify-write
  on last block, analogous to MemTable flush
- **Tombstone-driven compaction:** EcoTune (SIGMOD 2025) triggers compaction by garbage
  ratio — directly maps to posting rewrite threshold
- **Write amplification:** LIRE reassignment (1.5% of vectors per split) is the
  vector-index equivalent of compaction write amplification
- **Leveled ≈ SPFresh** (tight balance, higher write amp, lower read amp)
- **Size-tiered ≈ FreshDiskANN** (lazy merge, lower write amp, query fans out)

### concurrent-operation
- **SPFresh:** reads are lock-free; block mapping updated via CAS; version-based isolation
- **UBIS:** 2-bit status per posting (normal/splitting/merging/deleted) + CAS on 8-byte
  Posting Recorder entries; snapshot isolation via global version counter
- **Ada-IVF:** synchronous batch maintenance; no concurrent update discussion
- **CrackIVF:** cracking offloadable to separate thread pool; results available before crack

### object-storage-adaptation
- **Posting lists as S3 objects:** natural fit for all four systems
- **Split/merge → new immutable objects:** fits S3 write-once semantics
- **Reassignment:** requires reading nearby postings — feasible but latency-sensitive
- **Version-based GC:** old posting objects retained until all readers finish; S3 versioning
  or explicit lifecycle policies for cleanup
- **CrackIVF less suitable:** query-driven refinement adds latency per query on slow storage

### edge-cases-and-gotchas
- SPANN boundary replication (5.47x) dominates space amplification
- LIRE reassignment evaluates 5,094 vectors per event — 64 posting reads on object storage
- Ada-IVF's rc=25 neighbor radius for re-clustering may be too broad for large clusters
- UBIS's small partition size (40 target) may produce too many partitions for large datasets
- CrackIVF's budget model may not converge fast enough for recall-sensitive workloads

## tradeoffs

### strengths
- Sequential I/O pattern preserved — posting reads remain batch-friendly
- Natural fit for object storage (immutable posting rewrites)
- LIRE maintains NPA invariant without full rebuild
- Ada-IVF detects drift quantitatively (not just size-based)
- CrackIVF requires zero upfront index build time

### weaknesses
- Centroid quality bounded by incremental updates (full re-clustering is eventually better)
- Reassignment scope is a tuning parameter with no clear optimum
- Space amplification from boundary replication (SPANN-specific)
- All systems eventually need full rebuild when drift exceeds incremental repair capability

### compared-to-alternatives
- vs [graph-based maintenance](incremental-graph-maintenance.md): partition maintenance preserves sequential I/O but has coarser granularity
- vs full rebuild: 2-5x throughput improvement but bounded quality maintenance
- vs [SPANN/SPFresh](spann-spfresh.md): this file details the maintenance algorithms; that file covers the base index architecture

## practical-usage

### when-to-use
- Continuous insert/delete workload on IVF/SPANN index
- Object storage or SSD backend (sequential I/O pattern)
- Need to remove tombstones and maintain recall without downtime

### when-not-to-use
- Dataset is static (no maintenance needed)
- Full rebuild is acceptable (offline/batch workload)
- Extreme recall requirements (incremental centroid updates are approximate)

## sources

1. [SPFresh/LIRE (SOSP 2023)](https://arxiv.org/html/2410.14452v1) — split/merge/reassign protocol
2. [Ada-IVF (Nov 2024)](https://arxiv.org/html/2411.00970v1) — indicator-driven selective re-clustering
3. [UBIS (Jan 2026)](https://arxiv.org/html/2602.00563v1) — improved lock design, balance factor
4. [CrackIVF (Mar 2025)](https://arxiv.org/html/2503.01823v1) — query-driven adaptive cracking
5. [GaussDB-Vector (VLDB 2025)](https://www.vldb.org/pvldb/vol18/p4951-sun.pdf) — production system

---
*Researched: 2026-03-30 | Next review: 2026-09-30*

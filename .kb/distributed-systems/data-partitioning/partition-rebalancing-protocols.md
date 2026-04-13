---
title: "Partition Rebalancing and Data Migration Protocols"
aliases: ["rebalancing", "partition migration", "range split", "data movement"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["rebalancing", "migration", "split", "merge", "ownership-transfer", "streaming", "consistency"]
complexity:
  time_build: "O(data size / bandwidth)"
  time_query: "O(1) ownership lookup"
  space: "O(partition size) temporary during transfer"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
decision_refs: ["rebalancing-trigger-policy", "weighted-node-capacity", "partition-affinity", "partition-takeover-priority", "concurrent-wal-replay-throttling", "in-flight-write-protection", "un-walled-memtable-data-loss", "sequential-insert-hotspot", "partition-aware-compaction"]
sources:
  - url: "https://www.cockroachlabs.com/docs/stable/load-based-splitting"
    title: "CockroachDB: Load-Based Splitting"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://smazumder05.gitbooks.io/design-and-architecture-of-cockroachdb/content/architecture/splitting__merging_ranges.html"
    title: "Design and Architecture of CockroachDB: Splitting/Merging Ranges"
    accessed: "2026-04-13"
    type: "book"
  - url: "https://tikv.org/docs/5.1/concepts/explore-tikv-features/replication-and-rebalancing/"
    title: "TiKV: Replication and Rebalancing"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://notes.shichao.io/dda/ch6/"
    title: "DDIA Chapter 6: Partitioning (Kleppmann)"
    accessed: "2026-04-13"
    type: "book-notes"
  - url: "https://highlyscalable.wordpress.com/2012/09/18/distributed-algorithms-in-nosql-databases/"
    title: "Distributed Algorithms in NoSQL Databases"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://arxiv.org/pdf/2504.14802"
    title: "ReCraft: Self-Contained Split, Merge, and Membership Change of Raft Protocol"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://aijcst.org/index.php/aijcst/article/view/132"
    title: "High-Performance Distributed Database Partitioning Using ML-Driven Workload Forecasting"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/article/10.1007/s11704-024-40509-4"
    title: "LRP: Learned Robust Data Partitioning for Efficient Processing of Large Dynamic Queries"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p5527-xiangyao.pdf"
    title: "Disaggregation: A New Architecture for Cloud Databases (VLDB 2025)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://arxiv.org/html/2508.01931v1"
    title: "Marlin: Efficient Coordination for Autoscaling Cloud DBMS"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/article/10.1007/s41019-024-00276-5"
    title: "Aion: Live Migration for In-Memory Databases with Zero Downtime"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3722212.3724432"
    title: "CockroachDB Serverless: Sub-second Scaling from Zero (SIGMOD 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

# Partition Rebalancing and Data Migration Protocols

## summary

When a range-partitioned LSM-tree store detects imbalanced load, oversized
partitions, or membership changes, it must move data between nodes without
losing writes or violating consistency. This article covers trigger policies,
split/merge mechanics, migration protocols, and consistency mechanisms.

## trigger-policies

| Policy | Trigger | Strength | Weakness | Decision ref |
|--------|---------|----------|----------|--------------|
| Size-based | Partition exceeds byte threshold (CRDB: 64 MB, TiKV: 96 MB) | Predictable, low overhead | Blind to load skew | rebalancing-trigger-policy |
| Load-based | QPS or bytes/sec exceeds threshold | Catches hotspots | Sampling overhead, oscillation risk | sequential-insert-hotspot |
| Membership | Node join/leave | Essential for elasticity | Burst of transfers | concurrent-wal-replay-throttling |
| Scheduled | Periodic background evaluation | Smooths drift | Delayed correction | rebalancing-trigger-policy |

Production systems combine all four. CockroachDB runs `splitQueue` and
`replicaQueue` on each leaseholder continuously. Load-based triggers require
a decayed request histogram (10-60s window) to identify the split point.

## split-strategies

**Size midpoint.** Key at byte-offset midpoint of SSTable data. Simple but
unequal halves if key/value sizes vary.

**Key-count midpoint.** Median key by entry count. Equal counts but ignores
value size and access frequency.

**Load-based split point.** CockroachDB samples recent requests, picks the key
dividing observed QPS in half. Best for hotspot mitigation.

**Atomic split protocol (Raft-based).** Leader proposes split through the Raft
log. All replicas apply deterministically at the same log index, producing two
ranges on the same replica set. No data moves -- rebalancing is separate.

## merge-strategies

Adjacent ranges both below size and load thresholds are merged. CockroachDB
merges a range with the smaller of its neighbors. Requires coordinating two
Raft groups: the subsumed range freezes writes, its state transfers to the
surviving range, which extends its key span. More complex than splitting
because two independent consensus groups must agree.

## migration-protocols

### bulk SSTable transfer

Ship immutable SSTable files directly; target ingests via `IngestExternalFile`
or equivalent. Fast for large partitions. Requires memtable flush first.

### Raft snapshot + learner (CockroachDB / TiKV)

1. Target added as non-voting **learner replica**
2. Source streams point-in-time snapshot to learner
3. Learner replays Raft log from snapshot timestamp to catch up
4. Learner promoted to voter; old replica removed

Dominant pattern in Raft-based systems. The learner phase ensures the new
replica is caught up before participating in consensus.

### SSTable streaming (Cassandra)

SSTables streamed for repair/bootstrap/decommission. No consensus governs
transfer -- consistency relies on anti-entropy repair and read-repair.

## consistency-during-migration

**Epoch-based ownership.** Each partition carries a monotonic epoch incremented
on any config change. Stale-epoch requests are rejected, forcing routing cache
refresh. TiKV's Placement Driver is the epoch authority.

**Range lease.** CockroachDB assigns a lease to one replica. All reads/writes
go through the leaseholder. Lease is not transferred until new replica is
caught up -- no window where two nodes both accept writes.

**Fence tokens.** New owner presents fence token (epoch + 1) to storage. Writes
with token <= current fence are rejected. Prevents split-brain if old owner
hasn't learned it lost ownership.

## in-flight-write-protection

**Write forwarding (recommended for Raft).** Old owner continues accepting
writes, forwarded via Raft log during learner phase. No client disruption and
no special code beyond Raft membership change.

**Write rejection + retry.** Old owner returns "not leader"; clients refresh
routing and retry. Simpler but causes brief latency spikes.

**Dual-write.** Both owners accept writes; reconcile after. Requires conflict
resolution -- only viable in eventually-consistent systems (Cassandra).

## un-walled-memtable-data-loss

Source memtable may hold unflushed writes during ownership transfer.

**Flush-before-transfer.** Force memtable flush before snapshot. Simplest
correct approach; adds latency to migration start.

**WAL replay on target.** Ship WAL segment with SSTables; target replays
unflushed entries. Requires WAL to be partitioned or tagged per range.

**Raft log as WAL.** In Raft-based systems the Raft log is the WAL. Learner
replays it to reconstruct all state. Eliminates the problem entirely -- no
"un-WAL'd" data category exists.

For jlsm: if per-partition Raft, the Raft log subsumes the WAL. If shared WAL
below the partition layer, flush-before-transfer is the safe default.

## takeover-priority

| Factor | Weight | Rationale |
|--------|--------|-----------|
| Available capacity (disk + memory) | High | Prevents node overload |
| Current load (QPS, CPU) | High | Balances work, not just data |
| Network locality (rack/zone) | Medium | Reduces transfer time |
| Existing replica count | Medium | Failure domain spread |
| Partition affinity | Low | Co-locate for cross-partition joins |

## concurrent-rebalancing-throttling

**Transfer limit.** Cap simultaneous transfers per node (CRDB:
`kv.snapshot_rebalance.max_rate`, 32 MiB/s default).

**Backpressure.** Pause transfer if target's compaction backlog grows too
large. Prevents write stalls from bulk ingestion write amplification.

**Staggered scheduling.** Avoid moving multiple partitions from/to the same
node simultaneously. Complete one transfer before starting the next.

Bulk SSTable ingestion triggers expensive compaction. Partition-aware policies
(place ingested SSTables at L0, prioritize their compaction) reduce foreground
latency impact.

## reference-implementations

| System | Split trigger | Migration | Consistency | Throttling |
|--------|--------------|-----------|-------------|------------|
| CockroachDB | Size + load QPS | Raft learner + snapshot | Range lease + epoch | Per-store rate limit |
| TiKV | Size + key count | Raft learner + snapshot | PD epoch | PD scheduling limits |
| Cassandra | Fixed token ring | SSTable streaming | Anti-entropy repair | Stream throughput cap |
## sources

1. [CockroachDB: Load-Based Splitting](https://www.cockroachlabs.com/docs/stable/load-based-splitting)
2. [CockroachDB: Splitting/Merging Ranges](https://smazumder05.gitbooks.io/design-and-architecture-of-cockroachdb/content/architecture/splitting__merging_ranges.html)
3. [TiKV: Replication and Rebalancing](https://tikv.org/docs/5.1/concepts/explore-tikv-features/replication-and-rebalancing/)
4. [DDIA Ch.6: Partitioning](https://notes.shichao.io/dda/ch6/) (Kleppmann)
5. [Distributed Algorithms in NoSQL Databases](https://highlyscalable.wordpress.com/2012/09/18/distributed-algorithms-in-nosql-databases/)
6. [ReCraft: Split, Merge, and Membership Change](https://arxiv.org/pdf/2504.14802)
7. [ML-Driven Workload Forecasting for Partition Rebalancing](https://aijcst.org/index.php/aijcst/article/view/132)
8. [LRP: Learned Robust Data Partitioning](https://link.springer.com/article/10.1007/s11704-024-40509-4)
9. [Disaggregation: A New Architecture for Cloud Databases](https://www.vldb.org/pvldb/vol18/p5527-xiangyao.pdf) (VLDB 2025)
10. [Marlin: Autoscaling Coordination for Cloud DBMS](https://arxiv.org/html/2508.01931v1)
11. [Aion: Live Migration with Zero Downtime](https://link.springer.com/article/10.1007/s41019-024-00276-5)
12. [CockroachDB Serverless: Sub-second Scaling from Zero](https://dl.acm.org/doi/10.1145/3722212.3724432) (SIGMOD 2025)

## Updates 2026-04-13

### learned-and-predictive-rebalancing

ML-driven workload forecasting is replacing reactive threshold triggers. Recent
architectures feed telemetry streams (query arrival rate, key access frequencies,
resource utilization) into time-series and deep sequence models to predict
hotspots *before* they materialize. An adaptive partitioning engine then selects
split, merge, or migration actions while explicitly constraining data-movement
overhead — fewer repartitioning actions than static approaches, faster
convergence to balanced state. (Source: AIJCST 2025, ML-Driven Workload
Forecasting and Query Optimization)

LRP (Learned Robust Partitioning, Frontiers of CS 2025) encodes column access
patterns from historical queries into learned partition boundaries. Adapts to
dynamic workloads without full repartitioning — incremental boundary adjustment
based on query distribution drift.

### zero-copy-migration-via-disaggregated-storage

Disaggregated architectures (compute-storage separation) fundamentally change
migration economics. When SSTables live on shared storage (S3, EBS, distributed
FS), partition ownership transfer becomes a metadata operation — update the
ownership mapping, no data copies. CockroachDB Serverless (SIGMOD 2025) splits
SQL and KV layers into separate processes; new compute nodes attach to existing
storage without data redistribution. Marlin (arXiv 2025) uses fine-grained
"granules" (64 KB) with a distributed ownership table (GTable); scale-out
migrates only metadata, achieving 2.3x higher migration throughput than
ZooKeeper-coordinated approaches.

### workload-aware-split-points

Beyond CockroachDB's QPS-midpoint split: PROADAPT (IS 2023) uses a proactive
framework that monitors query patterns to pre-compute split points before
partitions become unbalanced. SWARM uses a probabilistic cost model to predict
per-machine workload from spatial distribution changes, focusing splits on
machines with emerging hotspots rather than reacting after overload.

### live-migration-with-zero-downtime

Aion (Data Science and Engineering, 2025) targets zero-downtime shard migration
for in-memory databases. Key insight: track deltas during migration and transfer
only changed data incrementally, eliminating redundant transfer of unchanged
pages. This reduces both migration duration and foreground latency impact
compared to full-snapshot approaches.

### multi-objective-rebalancing

Production rebalancing optimizes multiple competing objectives simultaneously:
load balance, migration cost, data locality/affinity, and failure domain spread.
DualMap (arXiv 2026) formalizes the tension between cache affinity and load
balance for distributed serving, using SLO-aware routing with hotspot-aware
rebalancing. For storage systems, the Pareto frontier typically trades migration
bandwidth against time-to-balance — aggressive rebalancing converges faster but
saturates network and triggers compaction storms.

### jlsm-implications

For a composable LSM library: (1) disaggregated storage support means partition
transfer can be metadata-only when SSTables are on shared storage — design the
ownership layer to support both copy-based and reference-based transfer;
(2) split-point selection should accept a pluggable strategy (midpoint, load-
based, learned) rather than hardcoding one policy; (3) rebalancing triggers
should expose a hook for external predictive models rather than relying solely
on threshold-based triggers.

---
*Researched: 2026-04-13 | Next review: 2026-10-13*

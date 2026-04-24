---
title: "Data Partitioning Strategies for Distributed LSM-Tree Storage"
aliases: ["sharding", "data distribution", "partition routing"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["partitioning", "range-partitioning", "hash-partitioning", "consistent-hashing", "lsm-tree"]
complexity:
  time_build: "O(n) initial distribution"
  time_query: "O(log R) range lookup where R = number of ranges"
  space: "O(R) metadata for range map"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://danthegoodman.substack.com/p/range-partitioning-zero-to-one"
    title: "Range Partitioning: Zero to One"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://www.pingcap.com/blog/building-a-large-scale-distributed-storage-system-based-on-raft/"
    title: "Building a Large-scale Distributed Storage System Based on Raft (TiKV)"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://arpitbhayani.me/blogs/some-data-partitioning-strategies-for-distributed-data-stores/"
    title: "Partitioning Data - Range, Hash, and When to Use Them"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://www.foundationdb.org/files/fdb-paper.pdf"
    title: "FoundationDB: A Distributed Unbundled Transactional Key Value Store"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://blog.algomaster.io/p/consistent-hashing-explained"
    title: "Consistent Hashing Explained"
    accessed: "2026-03-16"
    type: "blog"
related:
  - "systems/vector-partitioning/consistent-hashing.md"
  - "distributed-systems/data-partitioning/decoupled-index-partitioning.md"
  - "distributed-systems/data-partitioning/cross-partition-query-planning.md"
---

# Data Partitioning Strategies for Distributed LSM-Tree Storage

## summary

Data partitioning distributes key-value data across multiple nodes so that no
single node holds the entire dataset. The three primary strategies are
**range partitioning** (contiguous key ranges per node), **hash partitioning**
(hash function maps keys to nodes), and **consistent hashing** (hash ring with
virtual nodes). For LSM-tree backed storage that needs range scans and ordered
iteration, **range partitioning is the dominant choice** — used by CockroachDB,
TiKV, FoundationDB, BigTable, and Spanner. Hash partitioning sacrifices range
query efficiency for simpler load distribution. Hybrid approaches (hash
globally, range locally) combine strengths.

## how-it-works

### range partitioning

The keyspace is divided into contiguous, non-overlapping ranges. Each range
owns all keys where `key >= low && key < high`. The system starts with a
single range `[min, max)` and splits dynamically as data grows.

```
Range 1: [aaa, ggg)  → Node A
Range 2: [ggg, mmm)  → Node B
Range 3: [mmm, zzz)  → Node C
```

A **range map** (metadata structure) tracks which node owns which range.
Lookups binary-search the range map to find the owning node. Systems like
CockroachDB use a two-level meta-range: meta1 points to meta2 ranges,
meta2 ranges point to data ranges.

**Why it fits LSM-trees:** LSM-trees store data in sorted order (SSTables).
Range partitioning preserves this ordering across the distributed system,
so range scans stay within a single partition (or a small number of
adjacent partitions) instead of touching every node.

### hash partitioning

A hash function maps each key to a partition number:
`partition = hash(key) % N`. Data is distributed pseudo-randomly.

**Why it hurts LSM-trees:** Range scans require visiting all N partitions
because related keys are scattered. The natural sorted order of SSTables
is lost at the distribution layer.

### consistent hashing

A hash ring maps both nodes and keys to positions on a circle. Each key
is owned by the first node clockwise from its position. Virtual nodes
(100-200 per physical node) improve distribution uniformity.

**Rebalancing:** Adding a node absorbs ~1/N of keys from neighbours.
Removing a node distributes its keys to the next clockwise nodes. Only
k/n keys move (k=total keys, n=total nodes).

**Limitation for LSM-trees:** Same as hash partitioning — key ordering is
destroyed by the hash function, making range scans expensive.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Range size threshold | Max bytes per range before split | 64-512 MB | Smaller = more ranges, finer balancing, more metadata |
| Split policy | Size-based vs load-based | Both common | Load-based prevents hotspots better |
| Replication factor | Copies per range | 3-5 | Higher = more fault tolerance, more write cost |
| Virtual nodes (hash) | Hash ring positions per physical node | 100-200 | More = better distribution, more metadata |

## algorithm-steps

### range partitioning lifecycle

1. **Init:** Create single range `[min, max)` on any node
2. **Route:** For key k, binary-search range map to find range where `low <= k < high`
3. **Split:** When range exceeds size threshold, pick midpoint, create two new ranges
4. **Rebalance:** Move ranges between nodes to equalize load (independent of split)
5. **Replicate:** Each range is replicated to R nodes via consensus (Raft/Paxos)
6. **Merge:** When adjacent ranges both fall below threshold, merge back

### routing with meta-ranges (CockroachDB/TiKV pattern)

1. Client sends key to any node
2. Node checks local range cache for owning range
3. On cache miss: look up meta2 range (which maps key ranges to node locations)
4. Meta2 itself is a range, located via meta1 (a single well-known range)
5. Cache the result for subsequent lookups

## implementation-notes

### data-structure-requirements

- **Range map:** Sorted structure (B-tree or skip list) mapping range boundaries
  to node assignments. Must support concurrent reads and infrequent writes.
- **Range metadata:** Per-range: id, [low, high), leader node, replica set,
  epoch (logical clock for staleness detection).
- **Epoch mechanism (TiKV):** Logical clock incremented on every range config
  change (split, merge, leader transfer). Stale routing requests are rejected
  by comparing epochs.

### edge-cases-and-gotchas

- **Sequential insert hotspot:** Time-series or auto-increment keys concentrate
  all writes on the last range. Mitigation: prefix keys with a hash of a
  secondary attribute, or use CockroachDB-style hash-sharded indexes for
  sequential workloads.
- **Empty ranges after delete:** Bulk deletes can leave many empty ranges.
  Need a merge policy to consolidate them.
- **Split during compaction:** If the LSM-tree is compacting data that spans
  a range boundary, the compaction must be range-aware or operate below
  the partitioning layer (TiKV approach: single RocksDB per node, ranges
  are logical overlays).

## tradeoffs

### range partitioning

**Strengths:**
- Preserves key ordering — range scans are efficient (single partition)
- Dynamic splitting — elastic scaling without data rewrites
- Natural fit for LSM-tree sorted storage
- Enables locality-aware query routing

**Weaknesses:**
- Sequential insert hotspots (mitigable with prefix hashing)
- Implementation complexity higher than hash partitioning
- Cold start: single range initially, must split to scale
- Metadata management overhead (range map, meta-ranges)

### hash partitioning

**Strengths:**
- Simpler implementation — hash function determines placement
- Even distribution for random-access workloads
- No hotspot on sequential inserts

**Weaknesses:**
- Range scans touch all partitions — O(N) fan-out
- Partition count often fixed at creation (modulo-based)
- Consistent hashing improves this but still loses key ordering

### hybrid (DynamoDB pattern)

**Strengths:**
- Hash partition key distributes load globally
- Range sort key preserves ordering within each partition
- Efficient range scans within a partition

**Weaknesses:**
- Cross-partition range scans still require fan-out
- Partition key choice is critical and hard to change

### compared-to-alternatives

- Range partitioning is preferred when **range scans and ordered iteration**
  are primary access patterns (relational, document, time-series workloads)
- Hash partitioning is preferred for **point lookups only** (caching, session
  stores, content-addressable storage)
- Consistent hashing is preferred when **cluster membership changes frequently**
  and only point lookups are needed

## practical-usage

### when-to-use

**Range partitioning** for jlsm because:
- LSM-trees store data in sorted order — range partitioning preserves this
- `JlsmTable` supports range queries, secondary indices, and ordered scans
- Compaction operates on sorted key ranges — partition boundaries align naturally
- Remote storage backend (S3/GCS) already supports prefix-based listing

### when-not-to-use

- Pure key-value cache with no range queries → hash partitioning is simpler
- Very high churn in cluster membership → consistent hashing handles joins/leaves better

## reference-implementations

| System | Language | Strategy | URL | Notes |
|--------|----------|----------|-----|-------|
| CockroachDB | Go | Range + meta-ranges | https://github.com/cockroachdb/cockroach | Raft per range |
| TiKV | Rust | Range (Regions) + Raft | https://github.com/tikv/tikv | Single RocksDB per node |
| FoundationDB | C++ | Range + automatic splitting | https://github.com/apple/foundationdb | Unbundled arch |
| Cassandra | Java | Consistent hashing | https://github.com/apache/cassandra | Virtual nodes |
| DynamoDB | — | Hash + range (hybrid) | Proprietary | Partition + sort key |

## code-skeleton

```java
// Range-based partition router for a distributed LSM-tree store
class RangePartitionRouter<K extends Comparable<K>> {
    // Sorted map: range-start → RangeInfo(start, end, nodeId, epoch)
    private final TreeMap<K, RangeInfo<K>> rangeMap;

    RangeInfo<K> routeKey(K key) {
        // Find the range whose start <= key
        Map.Entry<K, RangeInfo<K>> entry = rangeMap.floorEntry(key);
        assert entry != null : "key below minimum range";
        assert key.compareTo(entry.getValue().end()) < 0
            : "key above range end — stale range map";
        return entry.getValue();
    }

    void splitRange(K splitPoint) {
        RangeInfo<K> existing = routeKey(splitPoint);
        // Create two new ranges: [existing.start, splitPoint) and [splitPoint, existing.end)
        // Increment epoch on both
        // Assign new range to a target node (load balancer decides)
    }
}

record RangeInfo<K>(K start, K end, String nodeId, long epoch, int replicaCount) {}
```

## sources

1. [Range Partitioning: Zero to One](https://danthegoodman.substack.com/p/range-partitioning-zero-to-one) — comprehensive implementation guide covering splits, routing, meta-ranges, and comparison to hash partitioning
2. [TiKV: Building a Large-scale Distributed Storage System Based on Raft](https://www.pingcap.com/blog/building-a-large-scale-distributed-storage-system-based-on-raft/) — production architecture using range partitioning (Regions) with per-region Raft on top of RocksDB LSM-tree
3. [Partitioning Data - Range, Hash, and When to Use Them](https://arpitbhayani.me/blogs/some-data-partitioning-strategies-for-distributed-data-stores/) — clear tradeoff comparison of hash vs range for distributed data stores
4. [FoundationDB Paper](https://www.foundationdb.org/files/fdb-paper.pdf) — unbundled architecture with automatic range-based partitioning and team-based replication
5. [Consistent Hashing Explained](https://blog.algomaster.io/p/consistent-hashing-explained) — virtual nodes, rebalancing mechanics, and when consistent hashing is appropriate

## Updates 2026-03-16

### Recent papers (2025-2026)

**Rethinking LSM-tree based Key-Value Stores: A Survey** (arXiv 2025,
Wang et al.) — comprehensive survey covering 100+ papers from 2020-2025.
Key findings for distributed partitioning:
- Modern systems implement hybrid approaches: dynamically adjusted range
  sharding with hash-based secondary distribution
- In distributed settings, lower levels (L0-L1) remain node-local for
  write performance; upper levels are dynamically redistributed
- Compute-storage disaggregation enables offloading compaction to remote
  nodes (Nova-LSM, Hailstorm, LightPool)

**EcoTune: Rethinking Compaction Policies in LSM-trees** (SIGMOD 2025,
Wang et al.) — proposes dynamic compaction policy selection. Relevant to
distributed partitioning because compaction strategy interacts with range
splits: compaction-aware splitting can reduce write amplification.

**Coordinated Sorted Runs Partitioning** (Journal of Big Data, 2025) —
introduces Local-Range and Global-Range partitioning algorithms for
stack-based compaction strategies. Relevant to per-range compaction in
distributed LSM-trees.

### New sources
1. [Rethinking LSM-tree KV Stores Survey](https://arxiv.org/html/2507.09642v1) — 100+ paper survey, distributed LSM architecture patterns
2. [EcoTune: Compaction Policies (SIGMOD 2025)](https://people.iiis.tsinghua.edu.cn/~huanchen/publications/ecotune-sigmod25.pdf) — dynamic compaction selection
3. [Coordinated Sorted Runs Partitioning](https://link.springer.com/article/10.1186/s40537-025-01298-0) — range-aware compaction partitioning

---
*Researched: 2026-03-16 | Next review: 2026-09-16*

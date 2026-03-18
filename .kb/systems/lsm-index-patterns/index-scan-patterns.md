---
title: "Index Scan Patterns over LSM Storage"
aliases: ["lsm scan patterns", "index access patterns", "scan vs get"]
topic: "systems"
category: "lsm-index-patterns"
tags: ["lsm-tree", "scan", "range-query", "inverted-index", "vector-index", "block-cache"]
complexity:
  time_build: "N/A"
  time_query: "O(k) per range scan of k entries"
  space: "O(block_size) per active iterator"
research_status: "active"
last_researched: "2026-03-18"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-indexing/src/main/java/jlsm/indexing/LsmInvertedIndex.java"
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/StringKeyedTable.java"
sources:
  - url: "https://arxiv.org/html/2402.10460v2"
    title: "A survey of LSM-Tree based Indexes, Data Systems and KV-stores"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://github.com/facebook/rocksdb/wiki/Partitioned-Index-Filters"
    title: "Partitioned Index Filters - RocksDB Wiki"
    accessed: "2026-03-18"
    type: "docs"
  - url: "https://arxiv.org/html/2505.17152v1"
    title: "LSM-VEC: A Large-Scale Disk-Based System for Dynamic Vector Search"
    accessed: "2026-03-18"
    type: "paper"
    note: "(not fetched — timeout/error)"
---

# Index Scan Patterns over LSM Storage

## summary

Different index types built on LSM trees exhibit fundamentally different access
patterns at the SSTable layer. Inverted indices use tight prefix-bounded range
scans. IVF-Flat vector indices use fixed-namespace range scans plus point gets.
HNSW vector indices use exclusively point gets with no scans. Table-level range
queries use application-bounded scans. These patterns have distinct implications
for block cache strategy and scan-path optimization in compressed SSTables.

## how-it-works

LSM trees expose three read primitives to higher-level indices: `get(key)` for
point lookups, `scan(from, to)` for bounded range iteration, and `scan()` for
full iteration. Each index type maps its logical operations to these primitives
in characteristic ways that determine I/O patterns at the SSTable block level.

### access-pattern-taxonomy

| Pattern | Block Access | Sequential? | Block Reuse | Examples |
|---------|-------------|-------------|-------------|----------|
| Point get | Single block | No | Low (unless hot key) | HNSW node lookup |
| Prefix-bounded scan | Few contiguous blocks | Yes | High within scan | Inverted index term lookup |
| Namespace scan | Many contiguous blocks | Yes | High within scan | IVF-Flat centroid load |
| Full scan | All blocks | Yes | Each block visited once | Table export, compaction |
| Application-bounded scan | Variable contiguous blocks | Yes | High within scan | Table range query |

## index-specific-patterns

### inverted-index (LsmInvertedIndex)

**Primary pattern: prefix-bounded range scan**

Composite key: `[4-byte BE term length][term bytes][doc-id bytes]`. A term
lookup scans from `[prefix]` to `[prefix+1)` via `incrementPrefix()`. All
posting list entries for a term are contiguous in sort order, making scans
sequential and block-friendly.

**Scan characteristics:**
- Tight bounds — typically spans a few blocks for common terms
- Sequential block access within the scan
- Multiple consecutive entries share the same block (high intra-block reuse)
- No full-table scans in normal operation
- Falls back to unbounded `scan()` only on prefix overflow (unreachable for
  realistic term sizes)

**Cache implications:** Scanned blocks are unlikely to be re-scanned for the
same term, but different terms may hit the same blocks if posting lists are
interleaved. A scan-local buffer is sufficient; BlockCache integration adds
little value since term lookups rarely revisit blocks.

### ivf-flat-vector-index (LsmVectorIndex.IvfFlat)

**Primary patterns: namespace scan + per-centroid bounded scan + point gets**

Three key namespaces in one LSM tree:
1. **Centroids** (`0x00`): full namespace scan `[0x00..0x01)` — loads all
   centroid coordinates. Done on every search.
2. **Posting lists** (`0x01`): per-centroid bounded scan
   `[0x01][cid..cid+1)` — loads vectors assigned to the selected centroids.
   Done for top `nprobe` centroids per query.
3. **Reverse lookup** (`0x02`): single point get per doc-id.

**Scan characteristics:**
- Centroid scan is fixed-size and repeated on every query — strong candidate for
  caching if the centroid set is stable between queries
- Posting list scans are bounded per centroid — sequential within each scan
- Multiple queries in a session re-scan the same centroid blocks repeatedly
- Point gets are isolated (reverse lookup only)

**Cache implications:** Centroid blocks are the highest-value cache target in
the entire system — they're read on every search and change only on index
rebuild. Posting list blocks are less cacheable unless the same centroids are
queried repeatedly (depends on query distribution). A scan-aware cache that
pins centroid blocks would significantly reduce I/O.

### hnsw-vector-index (LsmVectorIndex.Hnsw)

**Primary pattern: point gets only**

HNSW traverses a navigable small-world graph stored in the LSM tree. Each step
fetches a node's neighbors and vector via `get(nodeKey)`. The access pattern
is inherently random — graph traversal jumps between arbitrary keys.

**Scan characteristics:**
- **No range scans at all** — exclusively point gets
- Access is random across the key space (graph neighbors are not sorted)
- Upper graph layers (fewer nodes) exhibit high temporal locality
- Lower layers exhibit poor locality — graph neighbors are scattered

**Cache implications:** BlockCache is valuable here for upper-layer nodes that
are frequently revisited. Scan-path optimization has zero impact on HNSW
workloads. Block cache pollution from other indices' scans could degrade HNSW
point-get cache hit rates — this is the strongest argument for keeping scan
I/O isolated from the shared BlockCache.

### table-range-queries (StringKeyedTable / LongKeyedTable)

**Primary patterns: point gets + application-bounded range scans**

- CRUD operations use `get(key)` — standard point lookups
- `getAllInRange(from, to)` uses `scan(from, to)` — user-driven bounds
- Full table scan possible but uncommon in normal usage

**Scan characteristics:**
- Bounds are application-specified — scan width varies widely
- Sequential block access within each scan
- Typical use: pagination, export, range-filtered queries

**Cache implications:** Scan blocks may be valuable if the same range is queried
repeatedly (pagination). However, large export-style scans would pollute the
BlockCache with blocks unlikely to be re-read.

## block-cache-strategy-analysis

### current-state (jlsm)

- Point gets and scan reads share a single `BlockCache` (if configured)
- Scan reads via compressed SSTable go through `readAndDecompressBlock()` which
  populates the BlockCache
- Full scans via `decompressAllBlocks()` bypass the BlockCache entirely (but
  allocate a large temporary array)

### scan-cache-pollution-problem

RocksDB's experience confirms that scan I/O can pollute block caches designed
for point lookups. Large index/filter blocks evict hot data blocks, degrading
point-get performance. RocksDB addresses this with partitioned index/filter
blocks, not scan-specific cache policies.

### recommendations-for-jlsm

1. **Iterator-local buffer (immediate):** Each scan iterator maintains its own
   current-block buffer. No BlockCache interaction during scans. This prevents
   scan pollution of point-get cache entries. Sufficient for inverted index and
   table range scans.

2. **Centroid pinning (future):** IVF-Flat centroid blocks are read on every
   search query. A pinned/priority cache tier for centroid namespace blocks
   would eliminate repeated I/O. This is the highest-value cache optimization
   for vector workloads.

3. **HNSW cache isolation (future):** HNSW benefits most from BlockCache for
   upper-layer nodes. Scan-path isolation (recommendation 1) protects HNSW
   cache entries from scan pollution — this is a strong argument for the
   iterator-local buffer approach.

4. **No scan-path caching needed now:** No current index type benefits from
   caching scan-path blocks across queries. Inverted index term lookups rarely
   revisit the same blocks. Posting list scans are per-query. Table range scans
   are user-driven and unpredictable.

## tradeoffs

### strengths
- Iterator-local buffer is simple, correct, and prevents cache pollution
- Matches the actual access patterns of all current jlsm index types
- Defers complexity of scan-aware caching until workload data justifies it

### weaknesses
- Does not optimize for IVF-Flat centroid re-reads (deferred to future work)
- No cross-query scan block reuse (acceptable given current patterns)

## practical-usage

### when-to-cache-scan-blocks
- When the same key range is scanned repeatedly (pagination, centroid reload)
- When scan width is small enough that cached blocks aren't immediately evicted

### when-not-to-cache-scan-blocks
- Full table exports or large range scans (one-time sequential reads)
- When point-get cache pressure is high (HNSW workloads)
- When scan blocks would evict higher-value cached data

---
*Researched: 2026-03-18 | Next review: 2026-09-18*

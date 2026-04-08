# Vector Indexing — Category Index
*Topic: algorithms*

Query algorithms, index structures, and optimization strategies for approximate nearest
neighbor (ANN) search over high-dimensional vectors. Covers partition-based (IVF/SPANN),
graph-based (HNSW/DiskANN), and hybrid approaches, with emphasis on filtered search and
remote/object storage compatibility.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [partitioned-fanout-queries.md](partitioned-fanout-queries.md) | Partitioned Fanout Query Optimization | active | 30–50% nprobe reduction | Multi-partition/sharded vector search |
| [spann-spfresh.md](spann-spfresh.md) | SPANN / SPFresh | active | 2x faster than DiskANN at 90% recall | Object storage, billion-scale, streaming updates |
| [acorn.md](acorn.md) | ACORN | active | 2-10x QPS over baselines at 0.9 recall | Arbitrary/unknown predicates, in-memory |
| [filtered-diskann.md](filtered-diskann.md) | Filtered-DiskANN | mature | 6x over IVF inline at 90% recall | Equality filters on SSD, <1K labels |
| [sieve.md](sieve.md) | SIEVE | active | 8x speedup over ACORN, <2.15x memory | Known workloads, memory-budgeted filtering |
| [compass.md](compass.md) | Compass | active | 10.71x over NaviX on 3D conjunctions | Complex predicates (range, AND/OR) |
| [object-storage-ann-landscape.md](object-storage-ann-landscape.md) | Object Storage ANN Landscape | active | Decision matrix | Choosing algorithm by storage tier + predicate |
| [incremental-graph-maintenance.md](incremental-graph-maintenance.md) | Graph Index Incremental Maintenance | active | OdinANN: <1ms stable latency | Streaming updates on graph indexes (SSD/memory) |
| [incremental-partition-maintenance.md](incremental-partition-maintenance.md) | Partition Index Incremental Maintenance | active | LIRE: 1.5% vector movement per split | Tombstone removal, centroid drift repair (S3-friendly) |
| [index-quality-lifecycle.md](index-quality-lifecycle.md) | Index Quality Lifecycle | active | Deletion Control: principled rebuild scheduling | Detecting degradation, deciding when to rebuild |

## Comparison Summary

**Storage tier determines architecture family.** Graph-based indexes (HNSW, DiskANN, ACORN)
require random access and suffer 5-10x latency degradation on remote storage. Partition-based
indexes (SPANN/SPFresh) use sequential batch reads that map to S3/GCS access patterns.

**Predicate complexity determines algorithm within a family.** For in-memory/SSD:
- No/simple filters: standard HNSW or DiskANN
- Equality filters with known labels: Filtered-DiskANN (disk) or CAPS (memory)
- Arbitrary/complex predicates: ACORN (single index) or Compass (modular HNSW + B+-tree)
- Known stable workload: SIEVE (collection of indexes, 8x over ACORN)

**For object storage with filters:** SPANN with co-located metadata in posting lists.
Range filters require per-posting min/max metadata or secondary indexes.

## Recommended Reading Order
1. Start: [object-storage-ann-landscape.md](object-storage-ann-landscape.md) — decision matrix and architecture overview
2. Then: [spann-spfresh.md](spann-spfresh.md) — primary recommendation for object storage
3. Then: [acorn.md](acorn.md) — state-of-the-art for filtered search (in-memory)
4. Then: [compass.md](compass.md) — most general predicate support
5. Reference: [filtered-diskann.md](filtered-diskann.md) and [sieve.md](sieve.md) — alternative approaches
6. Cross-cutting: [partitioned-fanout-queries.md](partitioned-fanout-queries.md) — query optimization across partitions
7. Maintenance: [index-quality-lifecycle.md](index-quality-lifecycle.md) → [incremental-partition-maintenance.md](incremental-partition-maintenance.md) → [incremental-graph-maintenance.md](incremental-graph-maintenance.md)

## Research Gaps
- RaBitQ binary quantization for filtered re-ranking on remote storage
- Product quantization integration with IVF (IVF-PQ) for object storage
- Hybrid IVF+HNSW intra-partition indexing
- Streaming filtered search (predicates changing mid-query)
- Cost-based query optimizer choosing between index strategies at runtime
- Concurrent graph updates with formal consistency guarantees (current: relaxed isolation only)

## Shared References Used
@../../_refs/complexity-notation.md

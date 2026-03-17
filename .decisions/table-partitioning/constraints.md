---
problem: "table-partitioning"
slug: "table-partitioning"
captured: "2026-03-16"
status: "draft"
---

# Constraint Profile — table-partitioning

## Problem Statement
Choose a data partitioning strategy for jlsm-table that distributes documents
across multiple nodes in a distributed system, supports horizontal scaling,
and enables efficient combined queries across all supported search modalities
(property filters, full-text search, vector similarity, and any combination).

## Constraints

### Scale
Multi-node distributed system. Tables span multiple nodes with horizontally
growing datasets. Exact volume TBD but the architecture must support adding
nodes to increase capacity. Remote storage (S3/GCS) backed via NIO FileSystem SPI.

### Resources
Pure Java 25 library. No external runtime dependencies. No GPU. Memory bounded
by JVM heap + off-heap via ArenaBufferPool. Storage is remote-capable (S3/GCS)
via NIO FileSystem providers.

### Complexity Budget
Small team. Library is composable — consumers wire components together. The
partitioning layer must be a composable component, not a monolithic framework.
Implementation must fit within the existing JPMS module structure.

### Accuracy / Correctness
- Property queries and range scans must return correct, complete results (no
  data loss from partition operations)
- Full-text search must return complete results across the queried scope
- Vector search allows approximate results (ANN) — recall degradation from
  partitioning is acceptable if bounded and documented
- No data loss during partition split/merge operations

### Operational Requirements
- Partition splits and merges should not block reads/writes for extended periods
- Replication across nodes for fault tolerance is a goal
- Query routing must be efficient — avoid unnecessary fan-out where possible
- Combined queries (vector + property, vector + full-text, etc.) must execute
  efficiently without requiring separate round-trips to different node types

### Fit
- Java 25, JPMS modules
- Existing jlsm-table: JlsmDocument, JlsmSchema, secondary indices (FieldIndex),
  fluent query API (TableQuery, Predicate)
- Existing jlsm-vector: IvfFlat, HNSW (LsmVectorIndex)
- Existing jlsm-indexing: LsmInvertedIndex (full-text)
- Existing jlsm-core: LSM-tree (StandardLsmTree), SSTable, WAL, bloom filter,
  block cache, compaction (SpookyCompactor)
- Remote storage via NIO FileSystem SPI (S3, GCS providers)

## Key Constraints (most narrowing)
1. **Combined query efficiency** — the partitioning strategy must allow all
   query modalities (property, full-text, vector, and combinations) to execute
   efficiently. This rules out strategies that separate vector indices from
   document storage or that require cross-partition joins for filtered vector search.
2. **Composability** — must be a wirable component, not a monolithic system.
   Consumers should be able to configure partitioning independently of other
   concerns (replication, caching, etc.).
3. **Correctness on partition operations** — splits and merges must not lose
   data or produce incorrect query results during or after the operation.

## Unknown / Not Specified
- Exact data volumes and growth rate (architecture must be volume-agnostic)
- Target partition count range (affects scatter-gather fan-out cost)
- Replication factor and consensus protocol (deferred — separate decision)
- Cross-partition transaction semantics (deferred — separate decision)

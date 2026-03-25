---
title: "table-partitioning"
type: feature-footprint
domains: ["data-partitioning", "lsm-index-patterns"]
constructs: ["PartitionDescriptor", "PartitionConfig", "ScoredEntry", "PartitionClient", "RangeMap", "InProcessPartitionClient", "ResultMerger", "PartitionedTable"]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/Partition*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/ScoredEntry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/RangeMap.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/InProcessPartitionClient.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/ResultMerger.java"
research_status: stable
last_researched: "2026-03-25"
audit_rounds: 2
---

# table-partitioning

## What it built
Range-based table partitioning for jlsm-table via PartitionedTable coordinator. Each partition is a self-contained JlsmTable with co-located indices. Key-based CRUD routes to one partition via O(log P) binary search; vector/text queries scatter to all partitions and merge results.

## Key constructs
- `PartitionDescriptor` — immutable record: half-open key range + node ID + epoch
- `PartitionConfig` — validated partition layout (contiguous, non-overlapping)
- `ScoredEntry<K>` — ranked query result with relevance score
- `PartitionClient` — SPI interface for partition dispatch (remote-capable)
- `RangeMap` — O(log P) key routing + range overlap queries
- `InProcessPartitionClient` — wraps JlsmTable.StringKeyed for in-process dispatch
- `ResultMerger` — top-k merge (scored) + N-way ordered merge (range scans)
- `PartitionedTable` — coordinator: routing + scatter-gather + lifecycle

## Adversarial findings

### Round 1
- mutable-array-in-record (MemorySegment variant): PartitionDescriptor stored MemorySegment without copying → [KB entry](../../data-structures/mutable-array-in-record.md)
- builder-resource-leak-on-failure: PartitionedTable.Builder leaked clients on partial failure → [KB entry](../../systems/lsm-index-patterns/builder-resource-leak-on-failure.md)
- nan-score-ordering-corruption: ResultMerger ranked NaN above finite scores → [KB entry](../../systems/lsm-index-patterns/nan-score-ordering-corruption.md)
- range-query-inverted-bounds: RangeMap.overlapping returned results for empty range → [KB entry](../../systems/lsm-index-patterns/range-query-inverted-bounds.md)

### Round 2
- mutable-array-in-record (accessor variant): PartitionDescriptor.lowKey()/highKey() returned mutable segments → [KB entry](../../data-structures/mutable-array-in-record.md)
- segment-identity-equality: PartitionDescriptor equals/hashCode used MemorySegment address identity → [KB entry](../../systems/lsm-index-patterns/segment-identity-equality.md)
- deferred-close-catch-scope: PartitionedTable.close() only caught IOException, skipped remaining clients on RuntimeException → [KB entry](../../systems/lsm-index-patterns/deferred-close-catch-scope.md)
- nan-at-construction: ScoredEntry allowed NaN score at construction → [KB entry](../../systems/lsm-index-patterns/nan-at-construction.md)
- duplicate-id-no-validation: PartitionConfig.of() did not validate unique partition IDs

## Cross-references
- ADR: .decisions/table-partitioning/adr.md
- KB: .kb/distributed-systems/data-partitioning/partitioning-strategies.md
- KB: .kb/distributed-systems/data-partitioning/vector-search-partitioning.md

---
title: "Table Partitioning"
slug: table-partitioning
domain: distribution
status: active
type: core
tags: ["partitioning", "range", "scatter-gather", "multi-partition", "routing"]
features:
  - slug: table-partitioning
    role: core
    description: "Range-based table partitioning with scatter-gather queries and per-partition co-located indices"
composes: []
spec_refs: ["F11"]
decision_refs: ["table-partitioning"]
kb_refs: ["distributed-systems/data-partitioning"]
depends_on: ["query/secondary-indices"]
enables: ["distribution/engine-clustering"]
---

# Table Partitioning

Range-based partitioning for JlsmTable via a PartitionedTable coordinator.
Each partition is a self-contained JlsmTable with co-located secondary
indices, vector index, and inverted index. The coordinator routes operations
by key range and executes scatter-gather for cross-partition queries.

## What it does

PartitionedTable holds a range map of partition descriptors and routes
writes to the correct partition based on key range. Reads against a single
partition are routed directly; queries spanning multiple partitions are
scattered to all relevant partitions and gathered into a unified result.
Vector queries scatter to all partitions and merge with top-k selection.
Hybrid queries use reciprocal rank fusion (RRF) for merging.

## Features

**Core:**
- **table-partitioning** — range-based partitioning, scatter-gather query routing, per-partition index co-location

## Key behaviors

- Static partition assignment — partition boundaries are fixed at construction time
- Each partition is a full JlsmTable with its own indices, WAL, memtable, and compaction
- Write routing: key → range map → single partition
- Property/equality/range queries: route to relevant partitions only (partition pruning)
- Vector and full-text queries: scatter to all partitions, merge results
- Top-k merge for vector results, RRF for hybrid queries
- In-process execution — interfaces designed for future remote/networked partitions
- PartitionedTable implements the same Table interface for transparent usage

## Related

- **Specs:** F11 (table partitioning)
- **Decisions:** table-partitioning (range partitioning with co-located indices)
- **KB:** distributed-systems/data-partitioning (partitioning strategies, vector search topology)
- **Depends on:** query/secondary-indices (per-partition indices)
- **Enables:** distribution/engine-clustering (clustering distributes partitions across nodes)
- **Deferred work:** partition-replication-protocol, cross-partition-transactions, vector-query-partition-pruning, sequential-insert-hotspot, partition-aware-compaction

---
problem: "vector-query-partition-pruning"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["table-partitioning"]
spec_refs: ["F30"]
---

# Vector Query Partition Pruning

## Problem

Vector similarity queries scatter to all partitions because vector space
doesn't map to key-range boundaries. At moderate-to-large partition counts,
O(P) fan-out for every vector search wastes resources when most partitions
cannot contain top-k results. How should partitions be pruned for vector queries?

## Decision

**Pluggable PartitionPruner SPI with centroid-based and metadata-based
strategies** — a `PartitionPruner` interface evaluates which partitions
can be safely skipped. Three implementations: `AllPartitionsPruner` (default,
no pruning), `MetadataPartitionPruner` (field range bounds), and
`CentroidPartitionPruner` (vector centroid distance threshold).
`CompositePartitionPruner` combines strategies.

Key design choices resolved by F30 R20-R41:
- Pruner is a pure function — same inputs produce same outputs (R21)
- Metadata pruning uses per-partition min/max field range bounds (R29-R30)
- Centroid pruning uses representative vector per partition with configurable
  distance threshold and expansion factor (default: 2.0) (R32-R35)
- Missing metadata or centroids treated as non-prunable — safety over
  performance (R31, R36)
- Composite pruner takes union of pruned sets — partition skipped if ANY
  delegate prunes it (R37)
- Pruner invoked before scatter-gather; empty result if all pruned (R39)
- Pruner operates in physical key space when WriteDistributor active (R62)
- Centroid pruner unaffected by WriteDistributor — distance is in embedding
  space, not key space (R63)

## Context

Originally deferred during `table-partitioning` decision (2026-03-30) because
not needed at current scale. Resolved by F30 (Partition Data Operations) which
specified the full PartitionPruner interface and three implementations. The
specification was adversarially hardened and is in APPROVED state.

## Alternatives Considered

- **Global IVF centroid routing (Milvus-style)**: Centralized centroid index
  decoupled from range partitioning. Incompatible with the co-located index
  topology chosen in table-partitioning ADR. CentroidPartitionPruner achieves
  similar skip behavior within the existing architecture.
- **SIEVE-style multi-index approach**: Per-partition multi-index for filtered
  search. Listed as a research gap in the KB but not pursued — the
  MetadataPartitionPruner + CentroidPartitionPruner composition handles the
  common case.

## Consequences

- Vector queries no longer require full scatter at moderate partition counts —
  centroid pruning can eliminate partitions with distant embeddings
- Recall guarantee preserved: missing metadata/centroids force inclusion, so
  pruning never causes false negatives
- Centroid maintenance is a new per-partition concern — centroids must be
  updated as documents are added/removed
- Expansion factor (default 2.0) trades pruning aggressiveness for recall
  safety — consumers can tune for their accuracy requirements

---
problem: "partition-aware-compaction"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["table-partitioning"]
spec_refs: ["F30"]
---

# Partition-Aware Compaction Strategy

## Problem

How should compaction interact with partition boundaries? When multiple
partitions are co-located on a single node, compaction must be scheduled
fairly across partitions and must respect partition lifecycle states
(SERVING, DRAINING, CATCHING_UP).

## Decision

**Scheduler-based compaction coordination** — a `CompactionScheduler` SPI
manages cross-partition compaction scheduling with configurable concurrency
limits. Two strategies provided: round-robin (fair cycling by partition ID)
and size-weighted (prioritize highest pending-compaction-bytes ratio).

Key design choices resolved by F30 R42-R63:
- Shared concurrency limit across co-located partitions (default: 2)
- Only SERVING partitions are eligible for compaction (R46)
- Round-robin as default scheduler for fairness (R51)
- Partitions deregistered from scheduler on DRAINING transition (R58)
- Compaction operates on physical key space when WriteDistributor is active (R61)
- Scheduler is thread-safe and Closeable with idempotent close (R59-R60)

## Context

Originally deferred during `table-partitioning` decision (2026-03-30) because
interaction between range splits and SpookyCompactor was not yet analyzed.
Resolved by F30 (Partition Data Operations) which specified the full
CompactionScheduler interface and two implementations. The specification was
adversarially hardened and is in APPROVED state.

## Alternatives Considered

- **Per-partition compaction thread pools**: True parallel compaction per
  partition but doubles or triples thread count. Rejected — shared scheduler
  with concurrency limit achieves the same I/O bound with fewer resources.
- **Compaction-aware range splitting**: Compaction produces SSTables aligned to
  partition boundaries. Deferred to the compaction subsystem spec as a
  compaction-layer concern (noted in F30 design narrative).

## Consequences

- Compaction scheduling is now a first-class SPI — consumers can implement
  custom schedulers for workload-specific policies
- The concurrency limit bounds I/O contention but may delay compaction for
  partitions with heavy write loads when many partitions share a node
- SpookyCompactor interaction during active splits is noted but deferred to
  the compaction subsystem (F30 design narrative)

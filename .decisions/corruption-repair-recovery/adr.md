---
problem: "corruption-repair-recovery"
date: "2026-04-14"
version: 2
status: "deferred"
---

# Corruption Repair and Recovery — Re-Deferred

## Problem
When per-block CRC32C detects corruption, there is no repair or recovery
mechanism. The current behavior is to throw `CorruptBlockException`. A
higher-level strategy is needed: skip-and-quarantine, WAL-based rebuild,
replica fetch, or compaction-based repair.

## Why Deferred
Repair requires a source of truth. Single-node strategies (skip-and-quarantine,
WAL-based rebuild, compaction-based repair) exist but are limited — WAL segments
are typically discarded after flush, and compaction-based repair only works if
the same key range exists in another level. The most robust repair strategies
(read repair, anti-entropy, targeted replica fetch) require replication, which
depends on WD-07 (Partitioning & Rebalancing) — currently unspecified.

## Resume When
When WD-07 (Partitioning) is specified and partition-replication-protocol is
decided. At that point, the repair source (replica) is defined and the full
strategy space can be evaluated.

## What Is Known So Far
- Detection: per-block CRC32C in v3+ format (`.decisions/per-block-checksums/adr.md`)
- KB coverage: `.kb/systems/database-engines/corruption-detection-repair.md` —
  comprehensive survey of single-node and replica-based repair strategies
- KB coverage: `.kb/systems/database-engines/wal-recovery-patterns.md` —
  WAL-specific recovery modes
- Single-node strategies from KB:
  - Skip-and-quarantine: mark blocks as corrupt, serve from other levels
  - WAL-based rebuild: replay WAL segments if retained
  - Compaction-based repair: targeted compaction of affected key range
- Replica-based strategies from KB (requires replication):
  - Read repair: opportunistic fix during multi-replica reads
  - Anti-entropy: Merkle tree divergence detection + range streaming
  - Targeted replica fetch: per-block key range request

## Next Step
Run `/architect "corruption-repair-recovery"` after WD-07 specifies
partition-replication-protocol.

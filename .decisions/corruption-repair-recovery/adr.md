---
problem: "corruption-repair-recovery"
date: "2026-04-15"
version: 3
status: "accepted"
decision_refs: ["per-block-checksums", "sstable-end-to-end-integrity"]
spec_refs: ["F48"]
---

# Corruption Repair and Recovery

## Problem

When per-block CRC32C detects corruption, there is no repair or recovery
mechanism. The current behavior is to throw `CorruptBlockException`. A
higher-level strategy is needed for quarantine, repair, and verification.

## Decision

**Layered repair strategies — quarantine + compaction (single-node), read
repair + anti-entropy + targeted replica fetch (replicated)** — composable
strategies selected based on replication configuration.

Key design choices resolved by F48:
- Quarantine preserves corrupt SSTables, excludes from read path (R1-R6)
- Background scrubbing with rate-limited I/O, resumable (R7-R13)
- Compaction-based repair for overlapping key ranges (R14-R17)
- Read repair: opportunistic, sub-second fix during leader reads (R18-R24)
- Anti-entropy: Merkle tree comparison, O(tree size) divergence detection (R25-R33)
- Targeted replica fetch: precise block-level repair from followers (R34-R37)
- Strategy selection based on replication factor (R38-R39)
- Scrub rate default: 10 MiB/s; anti-entropy rate: 32 MiB/s (R40-R41)
- Read repair timeout: 5 seconds (R42)
- Merkle tree bounded at 32,768 leaves (R43)

## Context

Originally deferred during `per-block-checksums` decision (2026-04-10) because
repair requires a source of truth — single-node strategies are limited, and the
most robust strategies (read repair, anti-entropy, targeted replica fetch)
require replication. With F32 (Partition Replication, APPROVED) specifying
Raft-based replication, the repair source is now defined.

F26 (SSTable End-to-End Integrity) provides the detection layer: per-block
CRC32C, VarInt-prefixed self-describing blocks, and recovery scan. F48 builds
on this by defining what happens after detection: quarantine, repair selection,
and verification.

KB coverage: `.kb/systems/database-engines/corruption-detection-repair.md`
provides a comprehensive survey of detection and repair strategies from
RocksDB, Cassandra, Badger, and Google Spanner. The Merkle tree approach is
modeled on Cassandra's anti-entropy repair.

## Alternatives Considered

- **Erasure coding within SSTables**: Intra-file parity blocks for local repair
  without replica fetch. Deferred — complexity of managing parity across
  compaction rewrites is significant.
- **WAL-based SSTable rebuild**: Replay WAL segments to reconstruct corrupt
  SSTables. Rarely applicable — WAL segments are discarded after flush unless
  explicit retention is configured.
- **Automatic scrub scheduling**: Library runs background scrub threads on its
  own schedule. Rejected — a library should not run background threads
  autonomously; the consumer controls thread lifecycle.
- **ML-based anomaly detection**: Statistical prediction of failing media.
  Interesting but requires telemetry infrastructure the library cannot assume.

## Consequences

- Single-node deployments get quarantine + compaction repair (partial coverage)
- Replicated deployments additionally get automatic read repair and periodic
  anti-entropy (comprehensive coverage)
- Quarantine preserves corrupt data for forensic analysis — no automatic deletion
- Scrub and anti-entropy are rate-limited to avoid starving foreground I/O
- The layered approach lets consumers choose their durability/cost tradeoff

---
problem: "table-migration-protocol"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["table-catalog-persistence", "catalog-replication"]
spec_refs: ["F33"]
---

# Table Migration Protocol

## Problem

How should table partitions be migrated between nodes for rebalancing and
scaling, ensuring data integrity and minimal disruption to read/write
availability?

## Decision

**Raft-based learner replica with phased state machine** — migration uses
a five-phase state machine (PREPARE -> SNAPSHOT -> TRANSFER -> CATCHUP ->
CUTOVER) with rollback from every phase. The target node joins as a Raft
learner, receives a snapshot, catches up via log replication, and is promoted
to voting member at cutover. Catalog metadata is updated atomically through
the catalog Raft group (F37).

Key design choices resolved by F33 (73 reqs):
- MigrationCoordinator as sole entry point for lifecycle transitions (R5)
- At most one active migration per partition (R6)
- PREPARE: add target as Raft learner (R9), validate source is leader (R7)
- SNAPSHOT: point-in-time snapshot with continued write acceptance (R12-R14)
- TRANSFER: chunked InstallSnapshot via F32 R46, I/O throttled per F29 (R16-R17)
- CATCHUP: Raft log replication until lag threshold reached (R29-R30)
- CUTOVER: catalog metadata update via F37 Raft group (R34-R35)
- Rollback supported from all phases except post-CUTOVER (R20-R28)
- STALLED terminal state when cutover cannot complete (R44)

## Context

Originally deferred during `table-catalog-persistence` decision (2026-03-30)
as future cluster work. Depended on catalog-replication and
partition-replication-protocol — both now resolved (F37, F32). Resolved by
F33 (Table Migration) which specified the full migration protocol.

## Alternatives Considered

- **Stop-the-world migration:** Drain source, copy data, switch ownership.
  Rejected — unacceptable downtime for the partition during transfer.
- **Dual-write migration:** Write to both source and target during transition.
  Rejected — complex conflict resolution, doubles write amplification.
- **Snapshot-only (no Raft learner):** Transfer snapshot then hand off.
  Rejected — gap between snapshot and cutover means data loss for writes
  during transfer.

## Consequences

- Zero-downtime partition migration with continuous read/write availability
- Migration bandwidth is bounded by I/O throttling (F29) — no disruption
  to normal workload
- Rollback from any phase ensures migration failures don't corrupt data
- Catalog atomicity via F37 prevents split-brain during cutover

---
{
  "id": "partitioning.table-migration",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "partitioning.table-partitioning",
    "transport.multiplexed-framing",
    "partitioning.rebalancing-safety",
    "partitioning.rebalancing-policy",
    "partitioning.rebalancing-operations",
    "partitioning.partition-data-operations",
    "partitioning.partition-replication",
    "engine.catalog-operations"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-catalog-persistence"
  ],
  "kb_refs": [
    "distributed-systems/replication/catalog-replication-strategies",
    "distributed-systems/data-partitioning/partition-rebalancing-protocols"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F33"
  ]
}
---
# F33 -- Table Migration

## Requirements

### Migration state machine

R1. The system must define a `MigrationState` enum with exactly seven values: five lifecycle phases (`PREPARE`, `SNAPSHOT`, `TRANSFER`, `CATCHUP`, `CUTOVER`) and two terminal states (`FAILED`, `STALLED`). Each lifecycle phase represents a distinct phase of partition migration between nodes. `FAILED` is the terminal state after a rollback completes or times out. `STALLED` is the terminal state when CUTOVER cannot complete (R44).

R2. A partition migration must proceed through lifecycle phases in the strict order: PREPARE -> SNAPSHOT -> TRANSFER -> CATCHUP -> CUTOVER. No phase may be skipped. The only exceptions are: rollback transitions (R20-R28) which lead to FAILED, and cutover timeout (R44) which leads to STALLED.

R3. The system must define a `MigrationDescriptor` record containing: migration ID (long, unique per migration), source node ID (String), target node ID (String), partition ID (long), source ownership epoch (long), and the current `MigrationState`. The migration ID must be monotonically increasing within a node.

R4. The `MigrationDescriptor` must reject a null source node ID, null target node ID, or negative partition ID with the appropriate null/argument exception. The source and target node IDs must not be equal (reject with `IllegalArgumentException`).

R5. The system must define a `MigrationCoordinator` interface with methods to initiate a migration, query migration status, and cancel a migration. The coordinator must be the sole entry point for all migration lifecycle transitions.

R6. The `MigrationCoordinator` must enforce that at most one active migration exists per partition at any time. An attempt to initiate a migration for a partition that already has an active migration must fail with an `IllegalStateException` identifying the partition ID and the existing migration's state.

### PREPARE phase

R7. On entering PREPARE, the coordinator must validate that the source node is the current Raft leader (F32 R5) for the partition's Raft group. If the source node is not the leader, the migration must fail with an `IOException` identifying the partition ID and the actual leader's node ID.

R8. On entering PREPARE, the coordinator must validate that the partition is in the SERVING state (F27 R19) on the source node. If the partition is in any other state, the migration must fail with an `IllegalStateException` identifying the partition ID and its current state.

R9. During PREPARE, the coordinator must add the target node as a non-voting learner to the partition's Raft group (F32 R52). The learner addition must be proposed through the Raft configuration change protocol (F32 R49-R51).

R10. The PREPARE phase must have a configurable timeout (default: 30 seconds). If the learner addition is not committed within the timeout, the migration must transition to rollback (R20).

R11. The coordinator must not proceed to SNAPSHOT until the Raft configuration change adding the learner has been committed by a majority of the existing group members.

### SNAPSHOT phase

R12. On entering SNAPSHOT, the Raft leader must initiate a point-in-time snapshot of the partition's state. The snapshot must include all SSTable files for the partition and a flush of the current MemTable to an SSTable (F32 R45).

R13. The snapshot must record the last included Raft log index and term at the point the snapshot is taken. All entries up to and including this index must be included in the snapshot data.

R14. During SNAPSHOT, the Raft leader must continue accepting client writes. New writes after the snapshot point are captured in the Raft log and will be transferred during the CATCHUP phase.

R15. The SNAPSHOT phase must have a configurable timeout (default: 60 seconds). If the snapshot is not complete within the timeout, the migration must transition to rollback (R20).

### TRANSFER phase

R16. On entering TRANSFER, the Raft leader must begin streaming the snapshot to the target node using the `InstallSnapshot` chunked transfer protocol (F32 R46). Each chunk must be sent over the multiplexed transport (F19).

R17. The snapshot transfer must respect the I/O throughput limit defined by the rebalancing operations spec (F29 R16–R20). Snapshot chunks must acquire allowance from the shared throughput limiter before transmission.

R18. The target node must acknowledge each snapshot chunk. If a chunk acknowledgment is not received within a configurable per-chunk timeout (default: 10 seconds), the coordinator must retry the chunk up to a configurable retry count (default: 3).

R18a. If all chunk retries are exhausted for any single chunk, the migration must transition to rollback (R20).

R19. On successful receipt of all snapshot chunks, the target node must install the snapshot: load the SSTables, set `lastApplied` and `commitIndex` to the snapshot's last included index (F32 R47b), and report installation success to the coordinator.

### Rollback semantics

R20. The migration must support rollback from PREPARE, SNAPSHOT, TRANSFER, and CATCHUP. On rollback, the coordinator must restore the partition to its pre-migration configuration. Rollback from CUTOVER is addressed separately: a failed catalog update (R37) reverts to CATCHUP before rollback; after the catalog update is committed and the target is promoted (R45), rollback is not supported.

R21. Rollback from PREPARE must remove the target node from the Raft group configuration if the learner addition was committed. If the configuration change was not yet committed, no Raft group change is required.

R22. Rollback from SNAPSHOT must discard the in-progress snapshot on the source node and remove the target from the Raft group.

R23. Rollback from TRANSFER must: (a) instruct the target node to discard any partially received snapshot data, (b) remove the target from the Raft group, and (c) cancel any in-flight chunk transfers.

R24. Rollback from CATCHUP must: (a) instruct the target node to discard all received state (snapshot and replayed log entries), (b) remove the target from the Raft group.

R25. After rollback completes, the partition must remain in the SERVING state on the source node with no change to the ownership epoch. The migration must transition to the FAILED terminal state (R1).

R26. Rollback must not affect writes that were committed to the Raft group during the migration. The source node continues to serve as leader throughout rollback, and committed writes remain durable.

R27. Rollback from any state must have a configurable timeout (default: 30 seconds). If rollback does not complete within the timeout, the coordinator must log a warning identifying the migration ID and the state at which rollback was initiated, and transition the migration to the FAILED terminal state (R1). The partition remains owned by the source node.

R28. The coordinator must emit a structured event on rollback initiation containing: migration ID, partition ID, the state from which rollback was triggered, and the reason for rollback (timeout, chunk failure, leader change, or explicit cancellation).

### CATCHUP phase

R29. On entering CATCHUP, the target node must begin receiving Raft log entries from the leader starting from the snapshot's last included index. The target replays these entries to its MemTable identically to follower log replication (F32 R29).

R30. The coordinator must monitor the target node's replication lag (difference between the leader's last log index and the target's `matchIndex`). When the lag falls within the learner lag threshold (F32 R48a, default: 100 entries), the target is considered caught up.

R31. During CATCHUP, the Raft leader must continue accepting client writes. These writes are replicated to the target as part of normal Raft log replication, so no separate catch-up mechanism is required.

R32. The CATCHUP phase must have a configurable maximum duration (default: 5 minutes). If the target does not reach the lag threshold within this duration, the migration must transition to rollback (R20).

R33. If the target node's replication lag increases rather than decreases during CATCHUP (indicating the write rate exceeds the replay rate), the coordinator must log a warning after a configurable observation window (default: 30 seconds of sustained lag growth).

R33a. If lag growth persists for twice the observation window (default: 60 seconds of sustained lag growth), the coordinator must initiate rollback (R20).

### Catalog metadata update

R34. On entering CUTOVER, the coordinator must update the partition's catalog metadata to reflect the new owning node. The update must include the target node ID and a new ownership epoch (F27 R1) incremented from the source epoch. The coordinator must submit this update to the catalog leader as a catalog Raft entry (F37 R49).

R35. The catalog metadata update must be committed through the catalog Raft group (F37 R17) before the cutover proceeds. A majority of catalog replicas must acknowledge the update before it is considered committed.

R36. Until the catalog metadata update is confirmed, the source node must continue serving reads and the target node must not accept client traffic for the partition (F37 R54).

R37. If the catalog metadata update fails or times out (configurable, default: 15 seconds), the migration must revert to CATCHUP state and then transition to rollback (R20). The catalog update is the first operation within CUTOVER; if it does not succeed, the CUTOVER has not taken effect and rollback is still permitted.

R38. The catalog metadata update must carry the migration ID as a causality token. The catalog leader must reject an update whose migration ID does not match the currently active migration for that partition (F37 R50).

### CUTOVER phase

R39. After the catalog metadata update is confirmed, the coordinator must promote the target node from learner to voting member in the Raft group (F32 R52). The promotion must be proposed through the Raft configuration change protocol.

R40. After the target is promoted to a voting member, the coordinator must initiate a Raft leadership transfer to the target node. The source leader must stop accepting new client writes, allow all in-flight Raft log entries to commit, and then send a `TimeoutNow` message to the target to trigger an immediate election.

R41. The source node must transition the partition to DRAINING state (F27 R20) upon initiating leadership transfer. The drain follows the standard F27 drain protocol (F27 R5–R9).

R42. After the target wins the election and becomes leader, the target must transition the partition to SERVING state and begin accepting client traffic at the new ownership epoch.

R43. After the target is confirmed as leader, the coordinator must remove the source node from the Raft group configuration (F32 R53). The source transitions through DRAINING -> UNAVAILABLE per the F27 state machine (F27 R23).

R44. The CUTOVER phase must have a configurable timeout (default: 30 seconds) covering the combined time for learner promotion, leadership transfer, and source removal. If any step does not complete within the remaining timeout budget, the migration must transition to the STALLED terminal state (R1).

R44a. In STALLED state, the Raft group continues to function with the expanded membership (both source and target). The coordinator must not attempt automatic recovery from STALLED state. Manual intervention is required to complete or roll back the migration.

R45. Once the catalog metadata update is committed (R35) and the target is promoted to a voting member (R39), rollback is not supported. The system must complete the remaining cutover steps or enter STALLED state (R44). Reverting the catalog update under concurrent client traffic risks split-brain routing.

### Interaction with rebalancing operations (F29)

R46. When the rebalancing policy (F28) emits a `MoveAction`, the partition manager must translate the action into a migration by creating a `MigrationDescriptor` and invoking the `MigrationCoordinator`. The rebalancing policy must not invoke migration phases directly.

R47. Active migrations must count against the concurrent WAL replay limit (F29 R10). A migration in the TRANSFER or CATCHUP phase must occupy one replay slot. If no replay slots are available, the migration must wait in the pending queue ordered by the configured `TakeoverPrioritizer` (F29 R2).

R48. Snapshot transfer during the TRANSFER phase must acquire allowance from the shared I/O throughput limiter (F29 R18). Migration I/O competes fairly with WAL replay I/O for the shared budget.

### Interaction with compaction scheduling (F30)

R49. The `CompactionScheduler` (F30 R42) must deprioritize compaction for a partition that is in an active migration on the source node (any state from PREPARE through CUTOVER). Deprioritize means: the partition's compaction requests are moved to the end of the scheduling queue, not cancelled entirely.

R50. On the target node, compaction for a migrating partition must not be scheduled until the partition reaches SERVING state. Compaction of partially received snapshot data would produce incorrect results.

R51. After migration completes (partition reaches SERVING on target), the `CompactionScheduler` on the target must immediately evaluate the partition for compaction, since snapshot ingestion may have created an elevated L0 file count.

### Interaction with Raft replication group (F32)

R52. During migration, the Raft group temporarily contains both the source and target replicas. The replication factor is effectively N+1 during this window. The write quorum must be computed against the committed configuration (F32 R50), which changes as configuration entries are applied.

R53. If the Raft leader changes during any migration phase other than CUTOVER (e.g., the source loses leadership due to a network partition), the migration must transition to rollback. Only the Raft leader can coordinate snapshot creation and transfer.

R54. If the Raft leader changes during CUTOVER and the target wins the election, the cutover is considered successful and proceeds to completion (source removal). If a node other than the target wins, the migration must transition to the STALLED terminal state (R1).

R55. The migration must increment the ownership epoch (F27 R1) exactly once: during the catalog metadata update in CUTOVER (R34). Intermediate migration phases (PREPARE through CATCHUP) must not change the ownership epoch.

### Coordinator failure recovery

R56. The `MigrationCoordinator` must persist the `MigrationDescriptor` (including current state) to durable storage before each state transition. On coordinator restart, the coordinator must recover all active migrations from durable storage.

R57. On recovery, the coordinator must evaluate each recovered migration based on its persisted state: migrations in PREPARE, SNAPSHOT, TRANSFER, or CATCHUP must transition to rollback (R20). Migrations in CUTOVER must check whether the catalog metadata update was committed (via the catalog Raft group) and either complete the cutover or enter STALLED state.

R58. Migration state transitions must be idempotent: if the coordinator crashes and retries a state transition that was already applied (e.g., the learner addition in PREPARE was committed but the coordinator did not persist the transition to SNAPSHOT), the retry must detect the already-applied state and proceed without duplicating the operation.

### Observability

R59. The coordinator must emit a structured event on each state transition containing: migration ID, partition ID, source node ID, target node ID, the previous state, the new state, and a wall-clock timestamp.

R60. The coordinator must emit a structured event when TRANSFER progress changes, containing: migration ID, partition ID, chunks transferred, total chunks expected, bytes transferred, and the current transfer rate in bytes per second.

R61. The coordinator must emit a structured event when CATCHUP lag changes by more than 10% or crosses the lag threshold, containing: migration ID, partition ID, current lag (entries), leader last index, target match index.

R62. The coordinator must expose a method `activeMigrations()` returning an unmodifiable list of `MigrationDescriptor` records for all in-progress migrations on this node. This must be readable without blocking from any thread.

### Configuration

R63. All configurable timeouts and limits defined in this spec (PREPARE timeout R10, SNAPSHOT timeout R15, per-chunk timeout R18, chunk retry count R18, CATCHUP duration R32, lag observation window R33, catalog update timeout R37, CUTOVER timeout R44, rollback timeout R27) must be settable via the partition manager builder.

R64. Each timeout parameter must have the default value specified in its requirement. The builder must reject non-positive timeout values with an `IllegalArgumentException`.

R65. The builder must accept an optional `MigrationCoordinator` implementation. If not provided, the system must use its default coordinator implementation.

### Thread safety

R66. The `MigrationCoordinator` must be safe for concurrent invocation from multiple threads. Migration state transitions must be atomic with respect to concurrent status queries.

R67. The `MigrationDescriptor` must be an immutable record. State transitions produce a new `MigrationDescriptor` instance rather than mutating the existing one.

R68. Active migration status queries (R62) must not block ongoing migration state transitions.

### JPMS module boundaries

R69. `MigrationState`, `MigrationDescriptor`, and `MigrationCoordinator` must reside in an exported package of the `jlsm-table` module.

R70. Internal migration state machine implementation, snapshot coordination logic, and Raft interaction mechanics must reside in `jlsm.table.internal` and must not be exported.

---

## Design Narrative

### Intent

Define the protocol for moving a table partition from one node to another during rebalancing or schema changes. This spec resolves the deferred decision "table-migration-protocol" from the table-catalog-persistence ADR. The approach uses a five-phase state machine (PREPARE, SNAPSHOT, TRANSFER, CATCHUP, CUTOVER) that layers on top of the Raft replication group (F32) and the partition ownership model (F27). The migration adds the target as a Raft learner, transfers state via snapshot, promotes the learner to voter, transfers leadership, and removes the source -- the same pattern used by CockroachDB and TiKV for range migration.

### Why Raft learner-based migration

The KB (partition-rebalancing-protocols.md, migration-protocols section) documents three migration approaches: bulk SSTable transfer, Raft snapshot + learner, and SSTable streaming. Since F32 already establishes per-partition Raft groups, the learner-based approach is the natural fit:

1. **No separate transfer protocol.** The Raft snapshot transfer mechanism (F32 R44-R48) handles data movement. The migration coordinator orchestrates the Raft group membership changes; the Raft protocol handles the actual data transfer.

2. **Write continuity during migration.** The source remains Raft leader throughout PREPARE, SNAPSHOT, TRANSFER, and CATCHUP. Client writes continue without interruption because the leader appends them to the Raft log, which is replicated to the learner in real-time. The KB notes this as "write forwarding (recommended for Raft)" -- writes are not forwarded per se, but the Raft log naturally carries them to the target.

3. **Atomic cutover.** Leadership transfer is a single Raft operation. The target goes from learner to voter to leader in two configuration changes. The write interruption window is the duration of one election (~150-300ms), not the duration of the entire data transfer.

4. **Rollback is group membership reversal.** If migration fails before cutover, the coordinator simply removes the learner from the Raft group. No data cleanup on the source is needed because the source never stopped serving. The target discards its local state.

### Why a five-phase state machine

The five phases decompose the migration into independently monitorable and timeout-bounded steps:

- **PREPARE** validates preconditions and initiates the Raft group change. This is the cheapest phase -- if the partition is not in SERVING state or the Raft configuration change fails, the migration aborts quickly.
- **SNAPSHOT** creates the point-in-time data image. This is separated from TRANSFER because snapshot creation is a local operation on the source that may involve MemTable flush, while transfer is a network operation.
- **TRANSFER** ships the snapshot to the target. This is the longest phase for large partitions and has its own progress tracking and retry semantics.
- **CATCHUP** replays the Raft log entries accumulated during SNAPSHOT and TRANSFER. This phase converges the target to near-real-time state.
- **CUTOVER** promotes the target, transfers leadership, and removes the source. This is the only phase with no rollback, because the catalog metadata update creates a point of no return.

The alternative -- a monolithic "migrate" operation -- would make it impossible to distinguish whether a timeout occurred during data transfer (retryable) or during cutover (not retryable). The phased approach enables targeted timeouts and rollback strategies per phase.

### Why rollback is not supported during CUTOVER

Once the catalog metadata is updated to reflect the new owner (R34-R35), clients begin routing to the target. Reverting the catalog update while clients are mid-flight would cause:

1. Clients that already see the new routing send writes to the target, which is now being demoted.
2. Clients that see the reverted routing send writes to the source, which may have already started draining.
3. The ownership epoch becomes ambiguous -- was epoch N+1 ever valid?

CockroachDB handles this by treating the Raft leadership transfer as atomic -- once the target is promoted and the lease transfers, there is no going back. The migration enters STALLED state if cutover cannot complete, and the Raft group continues functioning (with an extra member) until manual resolution.

### Catalog metadata dependency (resolved)

This spec requires a catalog authority to commit the ownership change during cutover (R34-R38). F37 (Catalog Operations) defines a dedicated catalog Raft group (F37 R15-R28) and explicitly resolves the interaction with F33 in F37 R49-R54. The catalog ownership update is submitted as a Raft entry to the catalog leader, committed by majority, and propagated via epoch dissemination. The former OB-F33-01 obligation is resolved; R34-R38 now reference the specific F37 requirements.

### Interaction with F29 resource budgets

Migration competes with WAL replay for I/O and memory resources. Rather than introducing a separate resource budget for migration, this spec integrates with the existing F29 mechanisms:

- Migration in TRANSFER or CATCHUP counts as one WAL replay slot (R47). This ensures that migrations and node-join replays share the same concurrency limit.
- Snapshot chunk transfer uses the same I/O rate limiter as WAL replay (R48). This prevents migration from starving foreground I/O.

The tradeoff is that migrations are throttled by the same limits designed for WAL replay. If an operator needs faster migration at the expense of replay, they can increase the shared limits. A separate migration budget would add configuration complexity without meaningful benefit -- both migration and replay have the same I/O impact on the node.

### What was ruled out

- **Bulk SSTable transfer (without Raft).** Would require a separate transfer protocol, a separate catch-up mechanism for writes during transfer, and a separate consistency protocol for cutover. Since F32 provides all of these through Raft, adding a parallel mechanism is unnecessary complexity.

- **Dual-ownership during migration.** Both source and target accept writes, reconcile after. Requires conflict resolution, incompatible with the strong-consistency model provided by Raft. The KB explicitly notes this is "only viable in eventually-consistent systems."

- **Pre-copy / post-copy VM-style migration.** Aion (2025) uses delta tracking to transfer only changed pages incrementally. This optimizes for in-memory databases where most data is hot. For LSM-trees, most data is in immutable SSTables that don't change -- the snapshot is the bulk of the data, and the Raft log handles the delta. The VM-style approach adds complexity without benefit for predominantly immutable data.

- **Metadata-only migration for disaggregated storage.** The KB notes that when SSTables live on shared storage (S3), migration can be a metadata-only operation. This optimization is valid but requires the target to access the same storage backend as the source without data movement. F33 targets the general case (data must move); a future spec can optimize for shared-storage backends by short-circuiting the TRANSFER phase.

- **Concurrent migrations of the same partition to different targets.** Ruled out by R6. Concurrent migrations would require multi-target snapshot transfer and a protocol to select the winner. The complexity is not justified -- sequential migration to different targets achieves the same result with simpler correctness reasoning.

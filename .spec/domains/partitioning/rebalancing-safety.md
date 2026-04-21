---
{
  "id": "partitioning.rebalancing-safety",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F11"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "rebalancing-grace-period-strategy",
    "partition-to-node-ownership"
  ],
  "kb_refs": [
    "distributed-systems/data-partitioning/partition-rebalancing-protocols",
    "distributed-systems/data-partitioning/multi-writer-wal"
  ],
  "open_obligations": [
    "v2 renumbered requirements \u2014 update cross-references in F29, F30, F31, F32, F33"
  ],
  "_migrated_from": [
    "F27"
  ]
}
---
# partitioning.rebalancing-safety — Rebalancing Safety

## Requirements

### Ownership epoch

R1. Every partition assignment must carry a monotonically increasing ownership epoch.

R2. The ownership epoch for a partition must strictly increase on every ownership change (departure or return of a node that affects that partition's assignment). The increment need not be exactly one — it equals the gap between the previous and current membership view epochs at which the partition's owner changed.

R3. A node must reject any write request whose epoch is less than the node's current epoch for that partition. The rejection must include the current epoch so the caller can refresh its routing cache.

R4. The ownership epoch for a partition must equal the Rapid membership view epoch at which the partition's most recent ownership change occurred. When a membership view change does not alter a partition's owner, that partition's ownership epoch must not change. All nodes observing the same view must compute the same ownership epoch for every partition.

### Drain phase (departing owner)

R5. When a node detects via a new membership view that it has lost ownership of a partition, it must enter a drain phase for that partition before relinquishing control.

R6. During the drain phase, the departing owner must flush all MemTable data for the affected partition to durable storage (SSTable on object storage).

R7. During the drain phase, the departing owner must sync all pending WAL entries for the affected partition to durable storage.

R8. The drain phase must have a configurable timeout (default: 30 seconds). If the flush and WAL sync do not complete within the timeout, the departing owner must abandon the drain and emit a structured warning event containing the partition ID, the ownership epoch, and the number of unflushed MemTable entries.

R9. During the drain phase, the departing owner must reject new write requests for the draining partition with an OWNERSHIP_CHANGED rejection (as defined in R24).

R10. The departing owner must not delete WAL segments or SSTable files for the partition during the drain phase. Cleanup occurs only after the grace period expires.

### Drain phase read behavior

R11. During the drain phase, the departing owner must continue to serve read requests from its current MemTable and SSTables. The drain-phase flush must not block or delay concurrent read requests for the draining partition.

### Catch-up phase (new owner)

R12. The new owner must not accept writes for the partition until WAL replay is complete and the MemTable is rebuilt from the replayed entries.

R13. During the catch-up phase, the new owner must reject write requests for the partition with a NOT_READY rejection (as defined in R24). The rejection must include the partition ID and the current replay progress as the number of entries replayed so far.

R14. The new owner must replay the WAL from object storage starting from the sequence number of the last flushed SSTable for that partition. If no SSTable exists for the partition, replay must start from the earliest available WAL entry.

R15. If the departing owner completed its drain (flushed MemTable to SSTable), the new owner must incorporate that SSTable into its read path during catch-up by scanning the partition's storage prefix on object storage for SSTable files newer than the last known checkpoint.

R16. After WAL replay completes, the new owner must set the partition state to SERVING and begin accepting writes at the current ownership epoch.

### Initial partition creation

R17. When a partition is created for the first time (no prior data exists on object storage), the owning node must transition the partition directly from UNAVAILABLE to SERVING without a catch-up phase, because there is no WAL to replay and no SSTable to load.

### Partition states

R18. A partition on a given node must be in exactly one of the following states: SERVING, DRAINING, CATCHING_UP, or UNAVAILABLE.

R19. SERVING: the node owns the partition and accepts reads and writes.

R20. DRAINING: the node is flushing MemTable and WAL data before relinquishing ownership. The node rejects writes. The node continues to serve reads (per R11).

R21. CATCHING_UP: the node is the new owner and is replaying the WAL to rebuild the MemTable. The node rejects writes. Read behavior during catch-up is configurable per R43.

R22. UNAVAILABLE: the partition is not assigned to this node. All read and write operations must be rejected with a PARTITION_UNAVAILABLE error that includes the partition ID and the current membership view epoch. This rejection must be distinguishable from OWNERSHIP_CHANGED and NOT_READY rejections.

R23. State transitions must follow this directed graph: UNAVAILABLE -> CATCHING_UP -> SERVING -> DRAINING -> UNAVAILABLE. The only exception is R17 (initial creation: UNAVAILABLE -> SERVING when no prior data exists). No other transitions are permitted. A node must not transition from DRAINING to SERVING (re-acquiring ownership after drain starts requires going through UNAVAILABLE -> CATCHING_UP).

### Write rejection and client retry

R24. Write rejections during drain and catch-up must carry structured metadata: the rejection reason (OWNERSHIP_CHANGED, NOT_READY, or PARTITION_UNAVAILABLE), the partition ID, and the current epoch.

R25. OWNERSHIP_CHANGED rejections must additionally include the new owner's node ID when the departing owner can determine it from the current membership view. When the new owner cannot be determined (e.g., the departing node has not yet received the updated view), the new owner field must be absent rather than populated with a stale or guessed value.

R26. The caller must be able to distinguish between a write rejection due to ownership transition (retryable at a different node), a write rejection due to catch-up in progress (retryable at the same node after delay), and a write rejection due to a permanent error (not retryable). Each rejection type must use a distinct error code or exception type.

R27. The epoch check in R3 must prevent a write from succeeding at a node that has already advanced past the epoch carried by the write request. This is true regardless of whether the caller has refreshed its routing cache.

### Failure during drain

R28. If the departing owner crashes during the drain phase (before flush completes), recovery must proceed from the last durable state. The new owner recovers from the last durable WAL checkpoint and the last flushed SSTable on object storage. Any MemTable entries that were not synced to the WAL before the crash are not recoverable.

R29. The data loss window from R28 must be documented in the public API Javadoc as: "Writes that have been acknowledged to the client but not yet synced to the WAL on durable storage may be lost during an unclean ownership transfer. The maximum data loss window equals the WAL sync interval."

R30. If the departing owner completes the flush but crashes before signaling completion, the new owner must still discover the flushed SSTable during catch-up by scanning the partition's storage prefix on object storage for SSTable files. Discovery must not depend on an explicit completion signal from the departing owner.

### Failure during catch-up

R31. If the new owner crashes during WAL replay, the next owner (determined by HRW on the updated membership view) must start catch-up from scratch (UNAVAILABLE -> CATCHING_UP).

R32. WAL replay must be idempotent: replaying the same WAL entries that were already applied to the MemTable must produce a MemTable state identical to a single replay of those entries.

R33. If the WAL on object storage is corrupted or truncated, the new owner must skip corrupted records (per existing WAL CRC-based corruption detection) and emit a structured warning event containing the skipped record's sequence number, the partition ID, and the CRC mismatch details.

### Returning node (within grace period)

R34. When a node returns within the grace period and HRW reassigns its original partitions back to it, the node may perform a hot resume: transition those partitions from UNAVAILABLE to SERVING without a catch-up phase. Hot resume is only permitted when the node's JVM process was not restarted during the absence (the MemTable is still in memory).

R35. Hot resume (R34) must validate that no writes were accepted by any interim owner during the node's absence. The returning node must compare its local ownership epoch for the partition against the current ownership epoch derived from the membership view. If the current epoch is greater than the node's local epoch (indicating the interim owner accepted writes and incremented the epoch), the node must perform a full catch-up phase (UNAVAILABLE -> CATCHING_UP) instead of hot resume.

R36. If the node's JVM was restarted during the absence (MemTable is no longer in memory), the node must perform a full catch-up phase regardless of whether the grace period has expired.

### Concurrent ownership claims

R37. If two nodes simultaneously believe they own the same partition due to transient view divergence, the node with the lower ownership epoch must yield when it receives a write rejection (R3) or discovers a higher epoch via the membership view. The epoch mechanism ensures that at most one node can successfully accept writes for a partition at any given epoch.

### Concurrency

R38. Partition state transitions (R23) must be atomic with respect to concurrent read and write request dispatch. A request must observe either the pre-transition or post-transition state, never a partially-applied transition.

R39. A read or write request that was dispatched before a state transition and is still executing when the transition occurs must be allowed to complete against the pre-transition state. The transition must not cancel or abort in-flight operations.

R40. The ownership epoch for a partition must be readable without blocking by all request-processing threads (e.g., via a volatile field or atomic variable).

R41. Updates to the ownership epoch must be visible to all request-processing threads before any request at the new epoch is accepted. This requires a happens-before relationship between the epoch write and subsequent epoch reads.

### Configuration

R42. The drain timeout (R8) must be configurable via the partition manager builder. The default value must be 30 seconds. The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R43. The catch-up read behavior (R21) must be configurable: REJECT_READS (default) or SERVE_PARTIAL. When SERVE_PARTIAL is configured, reads during catch-up must return only data from entries that have been fully replayed and are visible in the MemTable at the time the read executes. The response must include a boolean flag indicating that results may be incomplete.

R44. The grace period duration must be configurable via the partition manager builder. The default value must be 120 seconds. The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

---

## Design Narrative

### Intent

Define the safety semantics for partition ownership transfer during cluster membership changes. This spec resolves two deferred decisions from the rebalancing-grace-period-strategy ADR: (1) what happens to MemTable data that has not been WAL-synced when ownership transfers, and (2) how writes in the pipeline are protected during the transition. The approach is a dual-phase protocol: the departing owner drains (flushes MemTable, rejects new writes), then the new owner catches up (replays WAL, rebuilds MemTable) before accepting traffic.

### Why this approach

**Drain-before-transfer over immediate handoff:** The parent ADR states "writes to the old owner's memtable that haven't been WAL'd are lost." The drain phase reduces this data loss window from "everything in the MemTable" to "only entries not yet synced to the WAL" — a much smaller window bounded by the WAL sync interval (typically milliseconds to seconds). Immediate handoff without drain would discard the entire MemTable, which could represent minutes of writes depending on flush thresholds.

**Write rejection over write forwarding:** The KB documents three approaches: forwarding, rejection+retry, and dual-write. Write rejection is simpler, composable with the existing PartitionClient SPI (R24 carries structured metadata for client retry), and does not require the departing owner to maintain connectivity during its departure — which may be involuntary.

**Explicit state machine over implicit states:** Four named states (SERVING, DRAINING, CATCHING_UP, UNAVAILABLE) with a directed transition graph make the system's behavior fully observable and testable. Each state has exactly defined read/write semantics. This avoids the ambiguity of "is this partition ready?" being answered differently by different code paths.

**Acknowledged data loss window over zero-loss guarantee:** A true zero-loss guarantee during unclean node departure requires synchronous replication (every write acknowledged only after N replicas confirm). This spec does not introduce replication — that is a separate concern deferred per the parent ADR. Instead, the spec documents the data loss window (R28-R29) so that operators understand the tradeoff and can configure the WAL sync interval accordingly.

### What was ruled out

- **Synchronous replication:** Would eliminate the data loss window entirely but requires consensus protocol (Raft/Paxos), which is a separate spec concern.
- **Write forwarding during drain:** Adds a forwarding channel between departing and new owner. For involuntary departures (crash), forwarding is impossible anyway.
- **Dual-write during transition:** Both owners accept writes and reconcile. Requires conflict resolution, incompatible with strong-consistency model.
- **Blocking reads during drain:** Creates unnecessary unavailability. The departing owner's data is still valid during drain.

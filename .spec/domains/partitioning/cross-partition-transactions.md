---
{
  "id": "partitioning.cross-partition-transactions",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F11",
    "F27"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-partitioning"
  ],
  "kb_refs": [
    "distributed-systems/transactions/cross-partition-protocols"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F31"
  ]
}
---
# partitioning.cross-partition-transactions — Cross-Partition Transactions

## Requirements

### Transaction identity and timestamps

R1. The system must define a `CrossPartitionTransaction` interface that represents a single atomic unit of work spanning one or more partitions within the same table. When all writes in the transaction route to a single partition, the Percolator protocol still applies (no single-partition shortcut) to preserve uniform commit semantics.

R2. Every `CrossPartitionTransaction` must carry a `startTs` (long) obtained from a `TimestampOracle` before any partition-level operation begins. The `startTs` establishes the snapshot from which all reads within the transaction are served.

R3. The system must define a `TimestampOracle` interface with a single method `next()` that returns a monotonically increasing long. Successive calls from any thread must never return the same value.

R4. The system must provide an `HlcTimestampOracle` implementation backed by a Hybrid Logical Clock. The implementation must produce timestamps that are causally ordered: if event A happens-before event B, then `ts(A) < ts(B)`. The encoding of physical and logical components into the long timestamp is an implementation detail.

R5. The `HlcTimestampOracle` must accept a `Clock` supplier at construction for testability. It must reject a null `Clock` with a `NullPointerException`.

R6. The `HlcTimestampOracle` must tolerate bounded clock skew. When a received timestamp from another node has a physical component ahead of the local clock by more than a configurable `maxSkew`, the oracle must reject the update with an `IllegalStateException` identifying the skew magnitude. The `maxSkew` must be configurable at construction (default: 500 milliseconds). The constructor must reject values less than or equal to zero with an `IllegalArgumentException`.

### Percolator protocol — prewrite phase

R7. The transaction client must designate one write as the primary. All other writes in the transaction are secondaries. The primary key serves as the transaction's commit point.

R8. During prewrite, for each key in the transaction's write set, the owning partition must: (a) check for any existing lock on the key from another transaction, (b) check for any committed write with a `commit_ts > startTs`, and (c) if neither conflict exists, write the tentative value to a data column keyed by `startTs` and place a lock record.

R9. The lock record must contain: the transaction's `startTs`, the primary key (as a `MemorySegment`), the primary key's partition ID, and a `lockTs` (the wall-clock time when the lock was placed, for TTL-based expiry detection).

R10. If any prewrite encounters a conflict (existing lock from another transaction or a committed write after `startTs`), the transaction must abort. On abort, all locks placed by this transaction during prior prewrite steps on reachable partitions must be cleaned up before the abort is reported to the caller. If a partition is unreachable during cleanup, the lock is left for lazy resolution via TTL expiry (R23-R24).

R11. Each individual prewrite operation (tentative value write + lock placement) must be atomic within its partition. The partition's WAL must guarantee that either both the value and lock are durable, or neither is.

R12. The prewrite must check the partition's ownership epoch (F27 R3) before writing. If the request's epoch is less than the partition's current epoch, the prewrite must fail with an `OwnershipChangedException` carrying the current epoch and partition ID.

### Percolator protocol — commit phase

R13. After all prewrites succeed, the transaction client must obtain a `commitTs` from the `TimestampOracle`. The `commitTs` must be strictly greater than the `startTs`.

R14. The primary commit must atomically remove the primary lock and write a commit record mapping `commitTs -> startTs` for the primary key. If the primary lock has been cleaned up by another transaction (rolled back due to TTL expiry), the commit must fail and the transaction is aborted.

R15. Once the primary commit record is durable, the transaction is committed. The outcome is determined solely by the presence or absence of the primary commit record.

R16. After primary commit succeeds, the transaction client must resolve each secondary key by removing its lock and writing a commit record (`commitTs -> startTs`). Secondary resolution is best-effort: if the client crashes before completing all secondaries, other transactions resolve stale secondary locks on encounter (R22).

### Isolation level

R17. Cross-partition transactions must provide snapshot isolation. A read within the transaction must observe exactly those values committed with `commit_ts <= startTs`, ignoring any writes committed after the transaction's snapshot.

R18. Snapshot isolation permits write skew. Two concurrent transactions that read overlapping keys and write disjoint keys must both be allowed to commit, even when their combined effect would violate an application-level invariant. The system must not prevent write-skew anomalies.

### Storage model — lock and write columns

R19. Each partition must maintain two metadata key spaces in addition to the data key space: a lock key space and a write key space. These key spaces must be stored in the same LSM engine as the partition's data. The mechanism for distinguishing key spaces (e.g., key prefix) is an implementation detail but must guarantee that a data key, a lock key, and a write key with the same user-key never collide.

R20. The lock key space must be keyed by `[prefix][user-key]` and contain lock records (R9). At most one lock may exist per user-key at any time within a partition.

R21. The write key space must be keyed by `[prefix][user-key][commitTs]` and contain the corresponding `startTs`. A point read for a user-key must scan the write key space in descending `commitTs` order and return the first entry where `commitTs <= readTs`.

R22. A read encountering a lock on the target key must resolve the lock before proceeding. Lock resolution must check the primary key's lock and commit state to determine the resolution action per R22a-R22c.

R22a. If the primary has a commit record, the encountered lock is stale and must be rolled forward: write a commit record for the locked key and remove the lock.

R22b. If the primary lock is absent and no commit record exists for the primary key at the transaction's `startTs`, the lock is from an aborted transaction and must be rolled back: remove the lock and the tentative value.

R22c. If the primary lock is still present and has not expired, the read must either wait for the lock to be released or abort, as determined by the `lockWaitPolicy` (R43a).

### Lock TTL and cleanup

R23. Lock records must carry a TTL derived from the `lockTs` in the lock record and a configurable `lockTtl` (default: 10 seconds). A lock is considered expired when `currentTime - lockTs > lockTtl`.

R24. An expired lock may be resolved by any transaction that encounters it. The resolver must check the primary before resolving (R22). Resolving an expired lock must not corrupt a concurrently committing transaction: the resolver must use a compare-and-swap or conditional delete that fails if the lock has been replaced since it was read.

R25. The `lockTtl` must be configurable on the `CrossPartitionTransactionManager` builder. The builder must reject values less than 1 second or greater than 300 seconds with an `IllegalArgumentException`.

### WAL integration

R26. Transaction prewrite and commit operations must flow through the owning partition's existing WAL. No global or cross-partition WAL is required.

R27. Each partition's WAL must be able to record lock placement, lock removal, tentative value writes, and commit record writes as distinct entry types. These entries must be replayable during WAL recovery to restore the lock and write key spaces.

R28. During WAL replay after a crash, the recovering partition must rebuild the lock and write key spaces from WAL entries. Orphaned locks (from transactions that never committed) are resolved lazily by subsequent reads (R22), not eagerly during replay.

### Interaction with ownership epoch (F27)

R29. A cross-partition transaction must record the ownership epoch of each participating partition in the transaction's in-memory state at prewrite time. The commit phase (both primary and secondary) must re-check the ownership epoch of each partition before writing. If any partition's epoch has changed since prewrite, the commit must fail and the transaction must abort.

R30. During a partition drain (F27 R5-R10), all locks held by in-progress cross-partition transactions on the draining partition must be left in place. The new owner inherits them during catch-up (via WAL replay per F27 R14). The new owner resolves orphaned locks lazily per R22-R22c.

R31. A partition in CATCHING_UP state (F27 R21) must reject prewrite and commit requests for cross-partition transactions. The rejection must carry a NOT_READY error consistent with F27 R13.

R32. A partition in DRAINING state (F27 R20) must reject new prewrite requests with an OWNERSHIP_CHANGED error (F27 R24). It must allow commit-primary and commit-secondary operations when the request's lock record exists on the partition (proving prewrite completed before the drain). This is necessary to avoid aborting transactions that have already reached the commit point (R15).

### Conflict resolution

R33. Write-write conflicts must be detected at prewrite time. If two transactions attempt to prewrite the same key, the second transaction to arrive must detect the first transaction's lock and abort (R10).

R34. Read-write conflicts within snapshot isolation are not detected. A transaction that reads key K and then another transaction commits a write to K does not cause the first transaction to abort. This is the standard write-skew window of snapshot isolation (R18).

### Transaction manager

R35. The system must provide a `CrossPartitionTransactionManager` that orchestrates the Percolator protocol. It must be constructed via a builder that accepts: a `TimestampOracle`, a `PartitionRouter` (from F11), a `lockTtl` configuration (R25), a `lockWaitPolicy` (R43a), and a `commitTimeout` (R38). The builder must reject null values for `TimestampOracle` and `PartitionRouter` with `NullPointerException`.

R36. The `CrossPartitionTransactionManager` must expose a `begin()` method that returns a `CrossPartitionTransaction` with a `startTs` already assigned.

R37. The `CrossPartitionTransaction` must expose `put(key, value)` and `delete(key)` methods to buffer writes, and a `commit()` method that executes the prewrite-then-commit protocol. A `delete(key)` writes a tombstone marker as the tentative value in the data column; reads that resolve to a tombstone must return empty/absent. The `commit()` method must return a sealed result type with `Committed(commitTs)` and `Aborted(reason)` variants.

R38. The `CrossPartitionTransaction.commit()` method must not block indefinitely. The commit timeout must be configurable on the `CrossPartitionTransactionManager` builder (default: 30 seconds). The builder must reject values less than 1 second or greater than 600 seconds with an `IllegalArgumentException`. If the protocol does not complete within the timeout, `commit()` must abort and return `Aborted` with a timeout reason.

R39. The `CrossPartitionTransaction` must expose a `get(key)` method that reads the value visible at `startTs`, resolving locks encountered per R22.

### Error handling

R40. If a partition is unreachable during prewrite, the transaction must abort and clean up all locks placed on reachable partitions before returning the abort result.

R41. If a partition is unreachable during secondary commit resolution (R16), the transaction is still committed (the primary commit record is durable). The unresolved secondary locks must be left for lazy resolution (R22-R24). The `Committed` result must include a list of partition IDs with unresolved secondaries.

R42. The `CrossPartitionTransactionManager` must emit a structured event (via `System.Logger` at WARNING level) for any transaction that commits with unresolved secondaries. The event must include the transaction's `startTs`, `commitTs`, and the list of unresolved partition IDs.

### Transaction lifecycle constraints

R43. The `CrossPartitionTransaction.commit()` method must throw `IllegalStateException` if the transaction's write set is empty (no `put` or `delete` calls were made). An empty transaction has no locks to place and no primary to designate, making the Percolator protocol inapplicable.

R43a. The `lockWaitPolicy` must be configurable on the `CrossPartitionTransactionManager` builder. It must be a sealed interface with two permitted variants: `ABORT` (the reader immediately aborts when encountering a live, unexpired lock) and `BACKOFF_RETRY(maxRetries, backoffMs)` (the reader retries with exponential backoff up to `maxRetries` attempts). The default must be `BACKOFF_RETRY(3, 50)`.

R44. The `CrossPartitionTransaction` must reject `put(key, value)` and `delete(key)` calls after `commit()` has been invoked, throwing `IllegalStateException`. The `commit()` method must reject a second invocation with `IllegalStateException`. A transaction is a single-use object.

R45. The `CrossPartitionTransaction.get(key)` must reject calls after `commit()` has returned, throwing `IllegalStateException`.

R46. If the `TimestampOracle.next()` call throws an exception during `begin()` or `commit()`, the exception must be propagated to the caller. During `commit()`, if the exception occurs after prewrites have succeeded (i.e., when obtaining `commitTs`), the transaction must abort and clean up all placed locks before propagating the exception.

R47. The primary key must be the first key in the transaction's write set in insertion order. The selection must be deterministic and must not depend on key ordering or partition assignment.

R48. The `CrossPartitionTransaction.put(key, value)` method must reject a null key with `NullPointerException` and a null value with `NullPointerException`. The `delete(key)` method must reject a null key with `NullPointerException`. The `get(key)` method must reject a null key with `NullPointerException`.

---

## Design Narrative

### Intent

Define atomic multi-partition write semantics for document operations that span partition boundaries within a single table. This spec resolves the deferred "cross-partition transactions" decision from the table-partitioning ADR, which acknowledged that cross-partition atomicity was out of scope for the initial partitioning design.

### Why Percolator

The KB research (cross-partition-protocols.md) evaluated four protocol families: 2PC, Calvin, Percolator, and OCC. The recommendation for jlsm's architecture is Percolator, for three reasons:

**No coordinator single point of failure.** Classical 2PC requires a coordinator that, if it crashes between PREPARE and COMMIT, leaves participants blocked indefinitely. Percolator's primary-lock-as-commit-point design eliminates the coordinator entirely. Any node can determine a transaction's outcome by inspecting the primary key's lock and write records.

**Maps to per-partition WAL.** Each Percolator operation (prewrite, commit-primary, commit-secondary) is a single-partition write. The existing per-partition WAL provides the atomicity guarantee needed for each step. No global WAL or cross-partition log is required — a significant architectural simplification.

**Non-blocking crash recovery.** When a transaction crashes mid-protocol, its locks become orphaned. Any subsequent transaction encountering a stale lock resolves it by checking the primary — committed transactions are rolled forward, aborted ones are rolled back. This is distributed, lock-free, and does not require a background garbage collector (though one could be added as an optimization).

### Why not Calvin

Calvin achieves the highest throughput for cross-partition transactions by batching them into deterministic epochs and avoiding per-transaction coordination. However, it requires that read/write sets are declared upfront before execution begins. For jlsm's document model — where a query might read a document to decide what to write — this is a fundamental mismatch. The workaround (reconnaissance queries) adds a round-trip and complicates the API. Calvin also requires a sequencer layer, which is a significant architectural addition for a library that targets composability.

### Why not OCC

OCC's zero-lock overhead is attractive for read-heavy workloads, but its abort rate grows quadratically with contention. For a general-purpose transaction API, this makes performance unpredictable. Percolator's lock-based approach has linear contention cost (one transaction waits or aborts, the other proceeds) and provides deterministic behavior under load.

### Snapshot isolation, not serializability

This spec provides snapshot isolation (SI), which permits write skew anomalies. The decision to ship SI rather than serializable isolation is deliberate: SI is sufficient for the vast majority of document operations (insert, update, delete across partitions), and serializable isolation would require either read-set tracking with validation (adding per-read overhead) or predicate locking (adding complexity). R18 documents this explicitly so that future work can layer serializable isolation on top if needed.

### Ownership epoch interaction

The critical interaction with F27 is during partition ownership transitions. The design makes two key choices:

1. **Locks survive ownership transfer.** When a partition drains (F27 R5-R10), locks from in-progress transactions are not eagerly cleaned up. They persist in the WAL and are replayed by the new owner. This avoids the need for the departing owner to coordinate with transaction clients during an involuntary departure.

2. **Epoch validation at prewrite and commit.** A transaction records each partition's epoch at prewrite time (R29). If the epoch changes before commit, the transaction aborts. This prevents a transaction from committing writes to a partition that has changed ownership — the new owner might have a different view of the data.

R32 introduces a nuanced rule: draining partitions reject new prewrites but allow commit operations for transactions already past the prewrite phase. Without this, a partition ownership change would abort every in-progress cross-partition transaction, even those that have already placed all locks and are about to commit. The commit operation is safe because the data (tentative values and locks) is already durable.

### What this spec does not cover

- **Cross-table transactions.** This spec covers same-table cross-partition only. Cross-table transactions are a separate concern in WD-06 (engine-api-surface-design).
- **Serializable isolation.** SI is the ceiling for this spec. Serializability requires additional conflict detection mechanisms.
- **Background lock garbage collection.** Orphaned locks are resolved lazily by readers. A background sweeper could reduce read latency for keys with stale locks but is not required for correctness.
- **Distributed deadlock detection.** Two transactions can deadlock if they lock the same keys in different orders. This spec does not include a deadlock detector — the lock TTL (R23) serves as a coarse timeout-based resolution. A proper deadlock detector is a future optimization.

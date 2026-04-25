---
{
  "id": "engine.catalog-operations",
  "version": 3,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "engine.in-process-database-engine",
    "transport.multiplexed-framing",
    "partitioning.partition-replication",
    "serialization.remote-serialization"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-catalog-persistence"
  ],
  "kb_refs": [
    "systems/database-engines/catalog-persistence-patterns",
    "distributed-systems/replication/catalog-replication-strategies"
  ],
  "open_obligations": [
    "OB-catalog-lock-extraction-01: Extract engine.catalog-lock-lifecycle as a dedicated spec covering the file-lock handle protocol currently captured as the composite R78 in this spec. Findings F-R1.resource_lifecycle.2.1 / 2.2 / 2.3 / 2.4 / 2.5 / 2.6 from audit run-001 all live on the FileBasedCatalogLock construct, which has no dedicated spec. The composite R78 captures the protocol short-term; a follow-up extraction is appropriate when cross-engine handle semantics expand (e.g., a remote-backend lease lock for object-storage backends)."
  ],
  "_migrated_from": [
    "F37"
  ]
}
---
# engine.catalog-operations — Catalog Operations

## Requirements

### Atomic multi-table DDL

R1. The engine must expose an `atomicDdl()` method that returns a `DdlBatch` builder.

R1a. The `DdlBatch` must accept an ordered sequence of DDL operations (create table, alter table, drop table).

R2. The `DdlBatch` must support `createTable(String name, Schema schema)`, `dropTable(String name)`, and `alterTable(String name, SchemaUpdate update)` as chained builder methods. Each method must return the `DdlBatch` for fluent composition.

R3. The `DdlBatch` must expose an `execute()` method that applies all queued operations atomically. Either all operations succeed and the catalog reflects every change, or none succeed and the catalog remains unchanged.

R4. If any individual operation within the batch fails validation (null name, duplicate create, drop of nonexistent table), the entire batch must be rejected before any mutation occurs. The exception must identify the failing operation's index (zero-based) and the reason.

R5. If a system failure (I/O error) occurs during batch execution after partial application, the engine must roll back all applied operations. The rollback must restore the catalog to its pre-batch state.

R5a. If rollback itself fails (I/O error during undo), the engine must mark the catalog as requiring recovery and throw an `IOException` whose message includes the batch ID and the number of operations successfully rolled back. On next startup, the recovery protocol (R9) must resolve the inconsistency.

R6. The engine must persist a batch intent record before applying any mutation. On recovery, an incomplete batch intent must trigger automatic rollback of any partially applied operations.

R6a. If the batch intent record is corrupt on recovery (CRC mismatch, truncated record), the engine must log a warning identifying the batch ID (if recoverable) and skip the record. The catalog state must be validated against the most recent known-good state.

R7. The batch intent record must include a monotonically increasing batch ID (long).

R7a. The batch intent record must include the number of operations and each operation's type and parameters.

R7b. The batch intent record must include a CRC32C checksum covering the entire record.

R8. After successful batch execution, the engine must persist a batch completion record to the catalog WAL. On recovery, a batch with both intent and completion records must be treated as fully applied.

R9. On recovery, a batch with an intent record but no completion record must be rolled back. The rollback must use the intent record's operation list to undo any partially applied changes (drop any tables that were created, recreate any tables that were dropped, revert any schema alterations).

R10. The `DdlBatch` must reject an empty batch (no operations queued) with an `IllegalStateException` when `execute()` is called.

R11. The `DdlBatch` must reject a batch where the same table name appears as the target of two operations of the same type (e.g., two creates of "A" or two drops of "A") with an `IllegalArgumentException` identifying the conflicting name. A batch that drops table "A" and then creates table "A" (in that order) is permitted and must execute the drop before the create.

R12. Concurrent `execute()` calls on different `DdlBatch` instances must be serialized. The engine must hold an exclusive DDL lock during batch execution to prevent interleaving of atomic batches.

R13. The DDL lock must have a configurable acquisition timeout (default: 10 seconds). If the lock cannot be acquired within the timeout, `execute()` must throw an `IOException` with a message indicating DDL contention.

R14. The `atomicDdl()` method must reject calls when the engine is closed (F05 R8) with an `IllegalStateException`.

### Catalog Raft group

R15. In clustered mode, the catalog must be replicated through a dedicated Raft group (the "catalog group") separate from data partition Raft groups. The catalog group must use the same Raft implementation as partition replication (F32).

R16. The catalog group must consist of a configurable number of replicas (default: 3, minimum: 1, must be odd). The builder must reject even values or values less than 1 with an `IllegalArgumentException`.

R17. The catalog Raft group must replicate all catalog mutations (table create, drop, alter, batch DDL, partition map updates) through the Raft consensus log.

R17a. A catalog mutation must not be considered committed until a majority of catalog replicas have acknowledged it.

R18. The catalog group leader must be the sole node authorized to accept DDL operations. DDL requests received by a catalog follower must be rejected with a redirect response containing the current leader's node ID.

R19. The catalog group must maintain a monotonically increasing catalog epoch (long). The epoch must be incremented on every committed catalog mutation (table create, drop, alter, partition map change).

R20. The catalog epoch must be persisted as part of the Raft log entry for each catalog mutation.

R20a. A node recovering from a crash must be able to reconstruct the current catalog epoch by replaying the catalog Raft log.

R20b. The catalog epoch must not wrap. If the epoch reaches `Long.MAX_VALUE`, the catalog leader must reject further mutations with an `IOException` indicating epoch exhaustion. This condition requires manual intervention (cluster reinitialization).

### Catalog Raft group bootstrap

R70. On initial cluster formation, the engine builder must accept a list of initial catalog group member node IDs. The first node in the list must bootstrap the catalog Raft group as a single-member group and then add the remaining members one at a time via the Raft configuration change protocol (F32 R49-R51).

R71. When the replication factor is 1 (single-node mode), the single catalog replica must immediately transition to LEADER on startup without running election timers (consistent with F32 R80). All catalog mutations are committed after the local WAL append (quorum of 1). Epoch dissemination via SWIM (R31a) is inactive.

R72. If the catalog Raft group has no leader (e.g., during an election or when a majority of catalog replicas are unreachable), DDL operations must fail with an `IOException` indicating that the catalog is unavailable. The exception must include a retry-after hint based on the election timeout range (F32 R19).

### Catalog Raft group SWIM interaction

R73. When SWIM marks a catalog group member as DEAD and the rebalancing policy determines a replacement, the catalog leader must remove the dead member and add the replacement via sequential Raft configuration changes (F32 R49-R53).

R74. When SWIM marks a catalog group member as SUSPECTED, the catalog group must not remove the member. The Raft protocol's internal leader election handles temporary unavailability.

### Catalog leader operations

R21. The catalog leader must serialize all DDL operations through the Raft log, including atomic multi-table DDL batches (R1-R14). A batch must be committed as a single Raft log entry containing the complete batch intent.

R22. The catalog leader must validate all DDL preconditions (table existence, name uniqueness, schema validity) before proposing the operation to the Raft log. A validation failure must be reported to the caller without proposing.

R23. The catalog leader must assign the new catalog epoch before proposing the mutation. The epoch must be included in the Raft log entry so that all replicas apply the same epoch for the same mutation.

R24. After a DDL Raft entry is committed (majority acknowledged), the catalog leader must apply the mutation to its local catalog state and return success to the caller.

R25. If the catalog leader loses leadership during a pending DDL operation (term change detected), the operation must fail with an `IOException` indicating leadership loss. The caller must retry against the new leader.

R25a. A DDL operation submitted to the catalog leader must have a configurable commit timeout (default: 30 seconds). If the Raft commit (majority acknowledgment) does not complete within this timeout, the operation must fail with an `IOException` identifying the timeout. The caller must not assume the operation failed -- it may have been committed but the response was lost.

### Follower catalog application

R26. Catalog followers must apply committed DDL Raft entries to their local catalog state in log order. The local catalog must reflect the same table set and schemas as the leader after applying the same log prefix.

R27. A follower that falls behind the leader's log must catch up via Raft log replay or snapshot transfer (F32 R44-R47c).

R27a. The catalog snapshot must include the complete catalog state: all table metadata, the partition map, the current catalog epoch, and any in-progress phased DDL state (R40-R48).

R28. After applying a catalog mutation, each follower must update its local catalog epoch to match the epoch in the Raft entry.

### Epoch-based catalog cache

R29. Every node in the cluster must maintain a local catalog cache containing the full catalog state (table metadata, partition map). The cache must store the catalog epoch corresponding to its current state.

R30. On each operation that requires catalog metadata (query routing, DDL, partition lookup), the node must compare its local cache epoch against the highest catalog epoch it has observed (via Raft heartbeats per R31 or SWIM messages per R31a). If the local epoch is lower than the observed epoch, the node must refresh its cache before proceeding.

R31. The catalog leader must piggyback the current catalog epoch on Raft `AppendEntries` heartbeats sent to catalog followers.

R31a. Catalog group members must piggyback the current catalog epoch on SWIM protocol messages sent to all cluster nodes.

R32. A node that discovers its catalog epoch is stale must pull the updated catalog state from any catalog group member. The pull request must include the node's current epoch so the responder can send only the delta if possible.

R33. The delta response must contain all catalog mutations between the requester's epoch and the current epoch, encoded as a sequence of DDL operations. If the delta is too large (more than a configurable threshold, default: 1000 mutations), the responder must send a full catalog snapshot instead.

R34. A catalog cache refresh must not block in-flight read operations that were initiated at the previous epoch. Only new operations initiated after the refresh completes must observe the updated catalog.

R35. A node whose catalog cache epoch is more than a configurable maximum staleness (default: 2 epochs behind the highest observed cluster epoch) must reject new client requests for tables whose metadata may have changed. The rejection must return a STALE_CATALOG error to the client with the current leader's node ID (if known) for redirect. In-flight operations initiated before the staleness was detected may complete at the previous epoch.

### Schema change propagation

R36. When the catalog leader commits a DDL operation, the new catalog epoch must be disseminated to all cluster nodes within a bounded time. The dissemination must use the SWIM protocol's epidemic broadcast (piggybacking on membership messages) to reach nodes that are not in the catalog Raft group.

R37. The catalog leader must send a catalog epoch notification to all catalog followers via the next Raft heartbeat after the DDL commit. Followers must propagate the epoch to non-catalog nodes via SWIM piggybacking within the next SWIM protocol round.

R38. A node that receives a catalog epoch notification with an epoch higher than its local cache must initiate an asynchronous cache refresh (R32). The refresh must not block the SWIM message processing path.

R39. DDL operations that change table schemas (create, alter, drop) must include the affected table names in the catalog Raft log entry.

R39a. Followers and cache-refreshing nodes must be able to determine which tables were affected by a catalog mutation without downloading the full catalog.

### F1-style phased DDL for schema alterations

R40. Schema alterations (add field, drop field, change field type) must follow a phased protocol ensuring at most two schema versions coexist in the cluster at any time. The phases are: DELETE_ONLY, WRITE_ONLY, and PUBLIC.

R41. When a schema alteration is submitted, the catalog leader must commit an initial Raft entry transitioning the affected element to DELETE_ONLY state. In DELETE_ONLY state, the new element accepts only delete operations (preventing orphaned data from nodes still on the old schema).

R42. The catalog leader must not advance from DELETE_ONLY to WRITE_ONLY until all catalog group followers have applied the DELETE_ONLY entry.

R42a. The catalog leader must not advance from DELETE_ONLY to WRITE_ONLY until the DELETE_ONLY epoch has been propagated to all cluster nodes that are ALIVE in the current SWIM membership view. Nodes marked SUSPECTED or DEAD are excluded from the propagation requirement. Propagation confirmation must use the SWIM protocol's dissemination guarantee (O(log N) rounds).

R43. In WRITE_ONLY state, the new schema element accepts writes but is not visible to reads. Background backfill of existing data (if required by the alteration) must occur during this phase.

R44. The catalog leader must not advance from WRITE_ONLY to PUBLIC until all cluster nodes that are ALIVE in the current SWIM membership view have confirmed receipt of the WRITE_ONLY epoch. Nodes marked SUSPECTED or DEAD are excluded. Confirmation must be via explicit acknowledgment piggybacked on the next SWIM protocol round from each node.

R45. In PUBLIC state, the schema element is fully visible and operational. The alteration is complete.

R46. Each phase transition must be committed as a separate Raft log entry with a new catalog epoch. The three-phase alteration requires three successive epoch increments.

R47. If the catalog leader loses leadership during a multi-phase alteration, the new leader must resume the alteration from the last committed phase. The Raft log contains the complete alteration history; no out-of-band coordination is required.

R48. A node that falls more than one schema version behind (misses an entire phase) must stop serving traffic for the affected table until it catches up. This enforces the two-version invariant from the F1 protocol.

R48a. Phased DDL alterations on different tables may proceed concurrently. The DDL lock (R12) serializes individual phase transitions but does not block a phase transition on table "B" while waiting for epoch propagation for table "A".

R48b. If a `dropTable` operation is submitted while a phased DDL alteration is in progress on that table (any phase), the drop must abort the in-progress alteration. The drop takes precedence: the table transitions to dropped state, and any pending phase transitions for that table are cancelled.

R48c. Each phase transition (DELETE_ONLY to WRITE_ONLY, WRITE_ONLY to PUBLIC) must have a configurable propagation timeout (default: 60 seconds). If epoch propagation confirmation (R42a, R44) is not received within the timeout, the catalog leader must log a warning and retry the propagation check. After three consecutive timeouts, the alteration must be aborted and the element reverted to its pre-alteration state.

### Interaction with F33 (Table Migration)

R49. During partition migration cutover (F33 R34-R38), the `MigrationCoordinator` must submit the ownership metadata update (new owning node, incremented ownership epoch) to the catalog leader as a catalog Raft entry.

R50. The catalog leader must validate the migration ID in the ownership update against the currently active migration for that partition (F33 R38). A mismatched migration ID must be rejected with an error response.

R51. The catalog ownership update must be atomic: the partition's owning node and ownership epoch must change in a single Raft log entry. The catalog epoch must also increment.

R52. After the catalog ownership update is committed, the catalog leader must respond to the `MigrationCoordinator` with a confirmation containing the new catalog epoch and the new ownership epoch.

R53. The ownership update must be propagated to all cluster nodes via the standard epoch dissemination mechanism (R36-R38). Nodes that receive the updated epoch must refresh their partition routing caches.

R54. Until the ownership update is committed in the catalog, the source node must continue serving reads for the migrating partition (F33 R36). The catalog serves as the single authority for partition ownership.

### Interaction with F36 (Remote Serialization)

R55. Atomic DDL operations must be encodable using the F36 remote serialization protocol. The protocol must define a new operation code for ATOMIC_DDL (reserved code 0x40) carrying a serialized batch of DDL operations.

R56. The ATOMIC_DDL request payload must begin with a 4-byte big-endian int32 operation count.

R56a. Each sub-operation in the ATOMIC_DDL payload must be encoded as: 1-byte operation type (0x01=CREATE, 0x02=DROP, 0x03=ALTER), followed by operation-specific fields. CREATE fields must match F36 R13 encoding. DROP fields must match F36 R14 encoding. ALTER fields must contain: table name (string per F36 R11), followed by the `SchemaUpdate` encoded as a sequence of field-level changes (each: 1-byte change type, field name string, and type-specific parameters per F36 R11-R12).

R57. The ATOMIC_DDL response must follow the F36 response envelope (F36 R27-R28). On success, the response must contain the new catalog epoch (8-byte int64). On failure, the error must carry the index of the failing operation and the specific error code.

R58. Catalog epoch queries must be encodable as a new operation code CATALOG_EPOCH (reserved code 0x41) with an empty request payload. The success response must contain: 8-byte int64 current catalog epoch, 8-byte int64 catalog leader node ID hash.

### Configuration

R59. The catalog group replication factor must be configurable via the engine builder (default: 3).

R60. The maximum catalog staleness (R35) must be configurable via the engine builder (default: 2 epochs).

R61. The DDL lock timeout (R13) must be configurable via the engine builder (default: 10 seconds).

R62. The delta threshold for full snapshot vs incremental refresh (R33) must be configurable (default: 1000 mutations).

R62a. The DDL commit timeout (R25a) must be configurable via the engine builder (default: 30 seconds).

R62b. The phased DDL propagation timeout (R48c) must be configurable via the engine builder (default: 60 seconds).

R63. Each configuration parameter must reject invalid values (non-positive, even replication factor, etc.) with an `IllegalArgumentException` identifying the parameter and constraint violated.

### Thread safety

R64. The `DdlBatch` builder must not be safe for concurrent use from multiple threads. A single batch must be built and executed from one thread. The engine's `atomicDdl()` may be called concurrently; the returned batches are thread-confined.

R65. The catalog Raft group state (epoch, table metadata, partition map) must be safe for concurrent reads from any thread on the node. Writes (DDL application) must be serialized through the Raft log.

R66. Catalog cache refresh (R32) must not block concurrent reads of the cache at the previous epoch. The refresh must use a copy-on-write or snapshot mechanism so readers see a consistent view.

### JPMS module boundaries

R67. `DdlBatch`, `SchemaUpdate`, the `CatalogEpoch` record, and the catalog query interfaces must reside in an exported package of the `jlsm-table` module.

R68. Internal catalog Raft group implementation, WAL-based batch recovery, and epoch dissemination mechanics must reside in `jlsm.table.internal` and must not be exported.

R69. The ATOMIC_DDL and CATALOG_EPOCH operation codes (R55, R58) must be registered in the F36 operation type registry.

### Local-mode catalog mutation discipline (single-JVM and cross-process)

The requirements in this section govern catalog mutation discipline at the local (single-node, possibly multi-JVM-on-shared-storage) layer. They complement the Raft-replicated DDL discipline (R12–R34 above) and apply equally whether the engine runs in single-node mode (no Raft group) or in clustered mode (where they constrain each node's local catalog state machine inside the Raft-applied flow).

R75. **Paired-mutation rollback discipline.** When a catalog mutation requires two durable-state writes (for example, "write `table.meta`" followed by "update the catalog index high-water"), and the second write fails after the first has succeeded, the catalog must roll back the first write to restore on-disk consistency with both the in-memory view and any auxiliary index view (R75 applies to `enableEncryption`, schema updates, partition-map mutations, and any future paired-mutation flow). Rollback that itself fails must NOT silence the original failure — the original `IOException` must propagate to the caller with the rollback failure attached via `Throwable.addSuppressed`. The implementation must structure the second-write failure path as: (a) catch the failure, (b) attempt rollback under a try/catch, (c) if rollback fails, call `addSuppressed(rollbackFailure)` on the original exception, (d) rethrow the original exception. R75 sits adjacent to R5 (atomic-batch rollback for `DdlBatch`) but applies to non-batch paired mutations that are not packaged as a `DdlBatch` — typically lifecycle mutations like `enableEncryption` whose two writes are described as "5 steps under one lock" by `sstable.footer-encryption-scope` R7b but whose rollback discipline was previously not specified.

R76. **Stage-then-publish discipline for table registration.** A catalog table-registration operation must NOT publish the in-memory table entry as `READY` before the on-disk artifacts (table directory, `table.meta`, catalog-index high-water) are durable. The implementation must:

1. Stage a placeholder (e.g., a `LOADING`-state entry that claims the table name) before any I/O begins. The placeholder reserves the name against TOCTOU race losses by concurrent registers.
2. Perform all I/O — directory creation, `table.meta` write, catalog-index high-water update — while the placeholder is still in `LOADING` state.
3. Transition the placeholder to `READY` via a compare-and-set operation only after every required on-disk artifact is durable.
4. On I/O failure during steps 2–3, perform a conditional-remove that targets the staged placeholder specifically (matching its identity, not just the table name) — never an unconditional name-keyed remove that could discard a competing register's `READY` entry.

Concurrent readers observing the entry during the I/O window must see the `LOADING` placeholder, never a `READY` entry whose disk state does not yet exist. R76 closes the publish-after-durable invariant gap that allowed in-memory reads to observe a `READY` entry whose disk artifacts had not yet been written.

R76a. **Stage-then-publish discipline for catalog index mutations.** A catalog-index mutation (for example, `setHighwater`, partition-map update, or any other write to the global catalog-index file) must use the same stage-then-publish discipline as R76, applied at the catalog-index granularity:

1. Encode the proposed value to bytes in memory.
2. Persist the encoded bytes via atomic rename (write-to-temp, fsync, atomic-rename) — this is the same pattern that `table-catalog-persistence` already mandates for `table.meta`.
3. Promote the in-memory live reference (the value readers consume on subsequent operations) only AFTER the atomic rename has completed durably.

A failure between step 1 and step 2 must leave both the in-memory live reference and the on-disk file unchanged. A failure between step 2 and step 3 must leave the on-disk file updated but the in-memory live reference stale; on next-startup recovery, the on-disk file is the source of truth and the in-memory reference is rebuilt from it. R76 and R76a apply to different state structures (per-table metadata vs. the global catalog index) and may diverge in implementation, so they are stated separately. Readers that observe the in-memory live reference must never see a value whose disk-side persistence has not yet completed.

R77. **Cross-process register-during-open race resolution.** The catalog `open()` scan must close the cross-process register-during-open race window that opens when two JVMs share the catalog storage (for example, a single-engine restart concurrent with another JVM's `register`, or two engines on shared object storage). The required protocol is:

1. While iterating table-name directories on disk, any directory whose in-memory catalog-index lookup misses must be DEFERRED — recorded into a per-`open()` deferred list, not skipped immediately.
2. After the directory iteration completes, the catalog must re-read the on-disk catalog-index file ONCE (to observe any peer JVM's `setHighwater` that completed during step 1).
3. Each deferred directory must be re-checked against the freshly-read catalog-index. Tables that become visible only via the fresh index must be loaded normally; tables still absent on the fresh re-read must be treated as cold-start orphans (handled per the existing R9b nonexistent-table contract — orphan files do not retroactively materialise tables without a corresponding catalog-index entry).
4. The two-phase scan (initial iteration + deferred re-check) must execute under the same catalog-level lock that protects against same-JVM concurrent registers, so the fresh re-read observes any peer JVM's `setHighwater` that completed during the first iteration. The lock is the catalog file-lock from R78 (below).

R77 closes the gap where a peer JVM's `register` completing during this JVM's `open()` would leave the registered table as an orphan in this JVM's catalog cache. The fix is the deferred-rescan pattern; a heavyweight cross-process catalog mutex is not required because the file-lock already serialises mutation, and the deferred rescan only needs to close the window between "directory observed missing from index" and "register completes on peer JVM".

### Catalog file-lock handle resource discipline

R78. **The catalog file-lock handle must satisfy the following six-part resource-lifecycle invariants.** A "catalog file-lock handle" is the construct (typically backed by a `FileChannel.tryLock`/`lock` call against a sentinel `.lock` file in the catalog directory) that protects single-writer-at-a-time semantics for catalog mutations. The invariants are stated as a single composite requirement because they collectively define one resource-lifecycle protocol; six separate requirements would fragment the contract.

(a) **Close ordering — release before cleanup, never delete.** `close()` must release the OS-level file lock before any cleanup of auxiliary state (in-process locks, holder PID record, listener invocation). `close()` must NOT delete the lock file as part of the release sequence: deletion creates a TOCTOU window in which an awaiting JVM can grab the file lock on the recreated file while this JVM still holds the OS-level lock pointer. The lock file must persist across release/acquire cycles; only its lock state changes.

(b) **Re-entrancy via in-process lock — no second OS-level acquire.** `acquire()` invoked on the same JVM with re-entrant intent (the same logical operation re-entering the lock from a deeper call) must short-circuit via the in-process lock (e.g., a `ReentrantLock` keyed by the same lock-file path) before any second OS-level lock attempt. An attempt to acquire the lock twice from the same thread must throw `IllegalStateException` (with a message indicating the re-entrant attempt against a non-re-entrant logical lock) rather than surfacing a JDK `OverlappingFileLockException`. The `IllegalStateException` is the canonical signal that the caller has a logic bug; `OverlappingFileLockException` would be a leaked implementation detail.

(c) **Monotonic-time bounded reclaim window.** The bounded reclaim/wait window for stale-holder reclamation (used when a recorded holder PID is found to be dead per (d) below) must use monotonic time — `System.nanoTime` with overflow-safe comparison — and never wall-clock time (`System.currentTimeMillis`, `Instant.now`, or any clock subject to NTP adjustment, leap seconds, or operator clock-set). Wall-clock skew during the wait window can cause a JVM to either give up too early (clock jumps forward) or wait forever (clock jumps backward). The overflow-safe comparison must be `(deadlineNanos - System.nanoTime()) > 0` rather than `System.nanoTime() < deadlineNanos`, because `nanoTime` returns a value that can wrap.

(d) **Platform-portable holder liveness probe.** Liveness probes for a recorded holder PID (used to determine whether a stale lock can be reclaimed) must use platform-portable mechanisms — `ProcessHandle.of(pid).isPresent()` is the canonical Java 9+ API. Platform-specific shortcuts must NOT be used as a primary signal: reading `/proc/<pid>` on Linux fails in chroots, distroless containers, and minimal `pid=host` configurations; checking `kill -0 <pid>` exit code on Unix fails on Windows; PID re-use during host process cycling can cause false-positive liveness on any platform. `ProcessHandle.of` correctly signals "no process exists with this PID" on every supported platform without requiring additional capabilities. R78(d) does NOT prohibit a fallback to `/proc` or other platform-specific mechanisms as a secondary signal when `ProcessHandle.of` is unavailable; it prohibits using them as the primary signal.

(e) **Holder-thread guard on close.** `close()` must reject calls from a thread that does not currently hold the JVM-level (in-process) lock with `IllegalStateException`, before any OS-level release operation. A non-holder thread releasing the OS-level lock while the JVM-level lock remains held would cross-thread the protection: the JVM-level lock would still appear held to its true holder, while the OS-level lock would have been released, creating a window in which a peer JVM could acquire the OS-level lock while this JVM's true holder still believes it has exclusive access. The check is the canonical "owner-thread on release" pattern from `ReentrantLock.unlock()` semantics, lifted to the composite handle.

(f) **Bounded per-table-name lock map.** The per-table-name JVM-level lock map (the `Map<TableName, ReentrantLock>` or equivalent that supplies (b) above) must be bounded: entries must be reference-counted on `acquire()` and atomically removed when the count reaches zero on `close()`. Distinct table names must NOT leak permanent map entries — a long-lived JVM that creates and drops 10 million tables must not retain 10 million `ReentrantLock` instances forever. The reference-count mutation must be atomic with the lock-state mutation (using a single compound `compute`/`computeIfAbsent` pattern) so that a concurrent `acquire()` from a peer thread cannot observe the count reaching zero in a moment when the lock would be discarded but the peer expects to use it. The bounded-map invariant matches the wider memory-discipline rule in `coding-guidelines.md` (every map that grows with input must have a configured capacity or eviction policy).

R78 lives in this spec as a composite requirement because the catalog file-lock handle has no dedicated spec; see open obligation `OB-catalog-lock-extraction-01` for the extraction tracking.

---

## Design Narrative

### Intent

Define atomic multi-table DDL operations and the catalog replication protocol for distributing catalog metadata across cluster nodes. This spec resolves two deferred decisions from the table-catalog-persistence ADR: "atomic-multi-table-ddl" and "catalog-replication." It also provides the catalog authority protocol that F33 (Table Migration) R34-R38 reference, resolving obligation OB-F33-01.

### Why a dedicated catalog Raft group

The KB (catalog-replication-strategies.md) evaluates four approaches: single-leader, consensus-replicated (Raft/Paxos), gossip-based, and epoch-based. The recommendation is a Raft catalog group with epoch-based caching, and the reasoning is sound:

1. **Strong consistency for DDL.** DDL operations (create, drop, alter) have cluster-wide visibility requirements. A gossip-based approach would allow nodes to transiently disagree on whether a table exists, causing routing failures and potential data loss. Raft provides linearizable writes: once a DDL commit returns, every subsequent catalog read at any node will reflect it (after the next cache refresh).

2. **No external dependencies.** An external coordination service (etcd, ZooKeeper) would violate the project's no-external-dependencies constraint. The Raft implementation from F32 already exists; the catalog group reuses it with different data.

3. **Small, fixed group.** The catalog group is 3-5 nodes regardless of cluster size. This avoids the scalability concern of consensus-per-mutation in large clusters. Data Raft groups scale with partition count; the catalog group does not.

CockroachDB uses the same approach: "system ranges" are Raft groups that store catalog metadata, identical in protocol to user data ranges. TiDB separates concerns by using an external etcd-backed Placement Driver, but that introduces an external dependency. YugabyteDB uses Raft for tablet metadata. The pattern is well-established.

### Why F1-style phased DDL

Schema alterations in a distributed system are dangerous because different nodes may be running different schema versions simultaneously. Google's F1 protocol (VLDB 2013) solves this with the two-version invariant: at most two adjacent schema versions coexist at any time, with intermediate phases (DELETE_ONLY, WRITE_ONLY) that prevent data corruption.

The key insight is that a node on schema version V and a node on version V+1 must not perform conflicting operations. DELETE_ONLY ensures that the new element cannot receive orphaned writes from nodes still on the old schema. WRITE_ONLY ensures that the element receives writes before it becomes visible to reads, so no node reads stale data.

The alternative -- immediate schema switch -- works only if all nodes apply the change simultaneously, which is impossible in an asynchronous distributed system. Even a brief window where one node sees a new column and another does not can cause writes to the new column that are invisible to reads on the old-schema node. F1's phased approach eliminates this by construction.

The three-phase overhead (three Raft commits per alteration) is acceptable because DDL is infrequent relative to data operations. The KB notes that TiDB implements this pattern in production with acceptable latency.

### Why WAL-based batch intent for atomic DDL

Single-node atomic DDL (before catalog replication is involved) uses a write-ahead batch intent record. This is simpler than a two-phase commit or a shadow catalog approach:

1. **Write intent record** containing all operations.
2. **Apply operations** to the in-memory catalog and persist each change.
3. **Write completion record** confirming the batch.

On crash recovery, an intent without a completion record triggers rollback. An intent with a completion record is treated as applied. This is the same pattern used by F35's transaction WAL (write-ahead, then apply) adapted for DDL.

In clustered mode, the Raft log replaces the local WAL as the durability mechanism. The batch is committed as a single Raft log entry (R21), providing atomicity through Raft's all-or-nothing commit semantics.

### Why epoch-based caching with pull-on-miss

Every query routing decision requires catalog metadata (which table exists, which node owns which partition). Making every routing decision go through Raft consensus would add 1-2ms per operation. Instead, nodes cache the full catalog locally and check staleness via a single long comparison (local epoch vs cluster epoch).

The common path (no DDL in flight) is a single local memory read. When DDL occurs, the epoch bump propagates via SWIM piggybacking (O(log N) rounds, typically < 1 second for 1000 nodes), and stale nodes refresh. The pull-on-miss approach means nodes that discover staleness fetch the delta from any catalog group member, not necessarily the leader, distributing the read load.

The maximum staleness bound (R35, default: 2 epochs) prevents a node from serving stale data indefinitely. A node that misses two consecutive epochs is likely experiencing network issues and should stop serving until it catches up.

### Resolving OB-F33-01

F33 R34-R38 describe catalog metadata updates during partition migration but were written protocol-agnostically because catalog-replication was not yet specified. With this spec:

- **R34** ("update partition's catalog metadata") maps to R49: the `MigrationCoordinator` submits the update to the catalog leader as a Raft entry.
- **R35** ("committed through catalog authority") is satisfied by R17: the catalog Raft group IS the authority, and commits require majority acknowledgment.
- **R36** ("until confirmed, source continues serving") maps to R54.
- **R37** ("if update fails, rollback") -- catalog leadership loss during the update causes the migration to fail per R25; the `MigrationCoordinator` handles rollback per F33 R20.
- **R38** ("migration ID as causality token") maps to R50.

### What was ruled out

- **Gossip-based catalog replication.** Eventually consistent metadata is unacceptable for DDL correctness. A node that believes a table exists when it has been dropped will accept writes that are silently lost. The KB explicitly notes gossip is appropriate only for "systems tolerant of brief inconsistency" (Cassandra-style).

- **Immediate schema switch (no phased DDL).** Works for single-node but causes data corruption in distributed mode when nodes disagree on schema version. Every production distributed database that supports online DDL uses some form of phased protocol.

- **External coordination (etcd, ZooKeeper).** Violates the no-external-dependencies constraint. The Raft implementation from F32 provides identical guarantees without external processes.

- **Optimistic DDL (allow conflicts, reconcile later).** DDL conflicts are rare but catastrophic when they occur. Two concurrent `createTable("users")` calls must produce exactly one table, not two that need reconciliation. Raft's linearizable log provides this guarantee by construction.

- **Separate catalog transport.** Catalog replication messages use the same F19 multiplexed transport as data replication, with METADATA traffic class (F36 R41). A separate transport would add operational complexity for no benefit -- catalog messages are small and infrequent.

### Out of scope

- Online DDL with zero-downtime backfill for existing data (the phased protocol provides the framework; actual backfill orchestration is a separate concern)
- Schema versioning with time-travel queries (monotonic epoch enables this but the query interface is not defined here)
- Catalog group leader placement policy (which nodes serve in the catalog group)
- DDL rate limiting or quota management

## Verification Notes

### Verified: v3 — 2026-04-25 (state: APPROVED — audit reconciliation amendment, source change required)

Audit reconciliation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`) surfaced six gaps in the local-mode catalog mutation discipline that the previous spec did not cover. v3 adds five new requirements (R75–R78) under a new "Local-mode catalog mutation discipline" section, plus opens one obligation tracking a future spec extraction:

- **R75 (new) — paired-mutation rollback discipline.** When a non-batch catalog mutation requires two durable writes and the second fails, rollback must restore the first; rollback failure must propagate the original failure with the rollback failure attached via `addSuppressed`. Source: F-R1.shared_state.1.1 (`TableCatalog.updateEncryption` had no rollback when `setHighwater` failed after `writeMetadata` succeeded).

- **R76 (new) — stage-then-publish for table registration.** Register must stage a `LOADING` placeholder before any I/O, transition to `READY` via CAS only after disk durable, and use conditional-remove (not unconditional) on rollback. Source: F-R1.shared_state.1.2 (`TableCatalog.registerInternal` published in-memory entry before disk state existed).

- **R76a (new) — stage-then-publish for catalog index mutations.** Catalog-index mutations must encode-then-rename-then-publish, never publish-then-persist. Source: F-R1.shared_state.1.3 (`CatalogIndex.setHighwater` published new value to in-memory readers before durability).

- **R77 (new) — cross-process register-during-open race resolution.** The catalog `open()` scan must defer index-miss directories during iteration and re-check them against a fresh catalog-index re-read after iteration completes, all under the catalog file-lock. Source: F-R1.shared_state.1.4 (`TableCatalog.open` did not acquire any catalog-level mutex against concurrent registers from other JVMs).

- **R78 (new — composite, six parts) — catalog file-lock handle resource discipline.** Six distinct latent bugs on the same `FileBasedCatalogLock` construct, captured as one composite requirement: (a) close ordering — release before cleanup, never delete; (b) re-entrancy via in-process lock; (c) monotonic-time bounded reclaim; (d) platform-portable holder liveness probe (`ProcessHandle.of`); (e) holder-thread guard on close; (f) bounded per-table-name lock map. Source: F-R1.resource_lifecycle.2.1 / 2.2 / 2.3 / 2.4 / 2.5 / 2.6.

- **`OB-catalog-lock-extraction-01` (new open obligation) — extract `engine.catalog-lock-lifecycle` as a dedicated spec.** Captured in the front-matter `open_obligations` list. The composite R78 captures the protocol short-term; the file-lock handle deserves its own spec when cross-engine handle semantics expand (e.g., a remote-backend lease lock for object-storage backends). The audit reconciliation flagged this as a "coverage gap" — the construct surfaced six confirmed bugs but had no dedicated spec.

**Verification impact:**

- All six additions are tightening (gap closure), not scope changes. R75–R78 are additive to the existing R-numbering family (which extends to R74); no existing requirement is invalidated.
- R75 sits adjacent to R5 (atomic-batch rollback for `DdlBatch`) but applies to non-batch paired mutations. R76 / R76a / R77 / R78 cover terrain not previously addressed by the spec.
- Implementation impact: existing `TableCatalog`, `CatalogIndex`, and `FileBasedCatalogLock` source already incorporates the audit fixes (audit run-001 is complete on the code side). v3 captures the contract invariants those fixes enforce so future audit passes can detect drift.

**Overall: APPROVED — amendment with source changes already applied.** Audit run-001 fixed each of the six bugs in source; v3 captures the contract invariants those fixes enforce. The open obligation `OB-catalog-lock-extraction-01` is non-blocking — it tracks a future spec extraction, not a current contract gap.

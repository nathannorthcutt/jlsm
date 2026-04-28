---
{
  "id": "engine.catalog-operations.ddl-operations",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "engine.catalog-operations",
  "_split_from": "engine.catalog-operations"
}
---

# engine.catalog-operations.ddl-operations — DDL Operations and Local-Mode Mutation

This spec was carved from `engine.catalog-operations` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R1. The engine must expose an `atomicDdl()` method that returns a `DdlBatch` builder.

R10. The `DdlBatch` must reject an empty batch (no operations queued) with an `IllegalStateException` when `execute()` is called.

R11. The `DdlBatch` must reject a batch where the same table name appears as the target of two operations of the same type (e.g., two creates of "A" or two drops of "A") with an `IllegalArgumentException` identifying the conflicting name. A batch that drops table "A" and then creates table "A" (in that order) is permitted and must execute the drop before the create.

R12. Concurrent `execute()` calls on different `DdlBatch` instances must be serialized. The engine must hold an exclusive DDL lock during batch execution to prevent interleaving of atomic batches.

R13. The DDL lock must have a configurable acquisition timeout (default: 10 seconds). If the lock cannot be acquired within the timeout, `execute()` must throw an `IOException` with a message indicating DDL contention.

R14. The `atomicDdl()` method must reject calls when the engine is closed (F05 R8) with an `IllegalStateException`.

### Catalog Raft group

R1a. The `DdlBatch` must accept an ordered sequence of DDL operations (create table, alter table, drop table).

R2. The `DdlBatch` must support `createTable(String name, Schema schema)`, `dropTable(String name)`, and `alterTable(String name, SchemaUpdate update)` as chained builder methods. Each method must return the `DdlBatch` for fluent composition.

R3. The `DdlBatch` must expose an `execute()` method that applies all queued operations atomically. Either all operations succeed and the catalog reflects every change, or none succeed and the catalog remains unchanged.

R4. If any individual operation within the batch fails validation (null name, duplicate create, drop of nonexistent table), the entire batch must be rejected before any mutation occurs. The exception must identify the failing operation's index (zero-based) and the reason.

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

R5. If a system failure (I/O error) occurs during batch execution after partial application, the engine must roll back all applied operations. The rollback must restore the catalog to its pre-batch state.

R5a. If rollback itself fails (I/O error during undo), the engine must mark the catalog as requiring recovery and throw an `IOException` whose message includes the batch ID and the number of operations successfully rolled back. On next startup, the recovery protocol (R9) must resolve the inconsistency.

R6. The engine must persist a batch intent record before applying any mutation. On recovery, an incomplete batch intent must trigger automatic rollback of any partially applied operations.

R6a. If the batch intent record is corrupt on recovery (CRC mismatch, truncated record), the engine must log a warning identifying the batch ID (if recoverable) and skip the record. The catalog state must be validated against the most recent known-good state.

R7. The batch intent record must include a monotonically increasing batch ID (long).

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

R7a. The batch intent record must include the number of operations and each operation's type and parameters.

R7b. The batch intent record must include a CRC32C checksum covering the entire record.

R8. After successful batch execution, the engine must persist a batch completion record to the catalog WAL. On recovery, a batch with both intent and completion records must be treated as fully applied.

R9. On recovery, a batch with an intent record but no completion record must be rolled back. The rollback must use the intent record's operation list to undo any partially applied changes (drop any tables that were created, recreate any tables that were dropped, revert any schema alterations).



---

## Notes

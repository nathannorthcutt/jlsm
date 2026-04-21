---
{
  "id": "engine.cross-table-transactions",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "F05",
    "F34"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "engine-api-surface-design"
  ],
  "kb_refs": [
    "systems/database-engines/cross-table-transaction-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F35"
  ]
}
---
# engine.cross-table-transactions — Cross-Table Transactions

## Requirements

### Transaction API

R1. The engine must expose a `beginTransaction()` method on the `Engine` interface that returns a `Transaction` object.

R1a. Each `Transaction` must carry a unique identity that distinguishes it from all other transactions created by the same engine instance.

R2. The `Transaction` interface must expose `table(String name)` which returns a `TransactionalTable` handle scoped to this transaction. The returned handle must support the same CRUD operations as a regular `Table` handle (F05 R32-R35) but buffer writes in memory until commit.

R2a. Calling `Transaction.table()` with a name that does not exist in the catalog must throw an `IllegalArgumentException` identifying the table name.

R2b. Calling `Transaction.table()` multiple times with the same table name must return the same `TransactionalTable` handle instance. A second call must not acquire an additional handle from the tracker.

R3. The `Transaction` interface must expose a `commit()` method that atomically applies all buffered writes across all participating tables.

R3a. The `commit()` method must return a sealed result type with `Committed(long commitTs)` and `Aborted(AbortReason reason)` variants.

R3b. Committing a transaction with no buffered writes must succeed and return `Committed` with a valid `commitTs`. No WAL record is written for an empty transaction.

R4. The `Transaction` interface must expose an `abort()` method that discards all buffered writes and releases all resources held by the transaction. Aborting an already-aborted or already-committed transaction must be idempotent (no exception).

R5. The `Transaction` interface must implement `AutoCloseable`. Closing an uncommitted transaction must abort it. Closing an already-committed or already-aborted transaction must be a no-op.

R6. The `Transaction` must reject operations after commit or abort. Calling `table()`, `commit()`, or any `TransactionalTable` method after the transaction has ended must throw an `IllegalStateException` with a message indicating the terminal state.

R6a. Calling `commit()` on an already-committed transaction must throw an `IllegalStateException`, not return a result. This differs from `abort()` (R4) which is idempotent.

R7. A `TransactionalTable` must reject operations on a table that has been dropped (F34 R10b) since the transaction began. The rejection must throw an `IllegalStateException` identifying the dropped table.

R7a. If a participating table is dropped between the last buffered write and the call to `commit()`, the commit must abort. The `Aborted` result must identify the dropped table.

R8. The `beginTransaction()` method must reject calls when the engine is closed (F05 R6) with an `IllegalStateException`.

### Transaction identity and timestamps

R9. Every transaction must carry a `startTs` (long) assigned at creation time from the engine's monotonic timestamp source. The `startTs` establishes the snapshot from which all reads within the transaction are served.

R10. The engine must maintain a monotonic transaction timestamp counter. Successive calls to `beginTransaction()` from any thread must produce strictly increasing `startTs` values.

R11. The `commitTs` must be obtained from the same timestamp source at commit time and must be strictly greater than the transaction's `startTs`.

R11a. The `commitTs` must be assigned after conflict detection succeeds and before the WAL record is written. No other transaction may receive a `commitTs` between this transaction's conflict check and its WAL write.

### WriteBatch atomicity

R12. The engine must use a dedicated transaction WAL for cross-table transaction commits. All writes from a single transaction must be serialized into one atomic WAL record that spans all participating tables.

R12a. A transaction that touches only one table must also write to the transaction WAL, not the per-table WAL. All transactional writes use the same commit path regardless of the number of participating tables.

R13. The atomic WAL record must include a batch header containing the transaction's `startTs`, `commitTs`, and entry count.

R13a. The atomic WAL record must include one entry per buffered write containing the target table identifier, operation type (PUT or DELETE), key, and value.

R13b. The atomic WAL record must include a CRC32C checksum covering the entire record (header and all entries).

R14. The WAL must fsync the atomic record before reporting the transaction as committed. A crash before fsync completes must result in the record being discarded on recovery.

R15. After the WAL record is durable, the engine must apply each write to the appropriate table's MemTable. The MemTable application is a replay of the durable record and need not be atomic with respect to readers (the WAL record is the commit point).

R16. If MemTable application fails for any table after the WAL record is durable (e.g., the table was dropped between WAL write and apply), the engine must not throw an exception to the caller. The `commit()` must still return `Committed`. The durable WAL record guarantees the writes are recoverable on the next engine restart via recovery replay (R40).

### Shared WAL design

R17. The engine must manage a dedicated transaction WAL, separate from per-table WALs. The transaction WAL must be stored under the engine's root directory at a deterministic, fixed location.

R18. The transaction WAL must provide the same durability guarantees as per-table WALs: segment-based storage with CRC validation on every record.

R19. The transaction WAL must be truncated (reclaimed) only after all participating tables have flushed their MemTables past the transaction's sequence point. A table that has not flushed blocks reclamation of any WAL segment containing that table's writes.

R20. To mitigate flush coupling (R19), the engine must trigger a flush on any table that has not flushed within a configurable `maxTxnWalRetention` duration (default: 5 minutes). The triggered flush must not block transaction commits.

R20a. The triggered flush must eventually complete under normal operating conditions. If the flush fails (I/O error), the engine must retry on the next retention check cycle rather than leaving the WAL unbounded.

### Snapshot isolation

R21. Cross-table transactions must provide snapshot isolation. A read within the transaction must observe exactly those values committed with `commitTs <= startTs`, ignoring writes committed after the transaction's snapshot.

R22. Writes buffered within the transaction must be visible to subsequent reads within the same transaction (read-your-writes). A `get()` on a `TransactionalTable` must check the transaction's write buffer before consulting the underlying table.

R23. Snapshot isolation permits write skew. The system must not claim serializable isolation. This is consistent with F31 R18 for cross-partition transactions.

### Conflict detection

R24. The engine must detect write-write conflicts at commit time using optimistic concurrency control (OCC). For each key in the transaction's write set, the engine must check whether any committed write to the same key in the same table has a `commitTs` in the range `(startTs, commitTs]`.

R24a. Non-transactional writes (direct `Table.put()` / `Table.delete()` via F05 R32-R35) must participate in conflict detection. A non-transactional write that commits to key K between a transaction's `startTs` and `commitTs` must cause a write-write conflict for that key.

R25. If a write-write conflict is detected for any key in any participating table, the transaction must abort. The `Aborted` result must include the conflicting table name and key.

R26. Conflict detection must be performed before the WAL record is written. A transaction that will abort must not write to the WAL.

R27. Read-write conflicts are not detected under snapshot isolation. A transaction that reads key K in table A, after which another transaction or non-transactional write commits a write to K in table A, does not cause the first transaction to abort (consistent with F31 R34).

### Conflict detection implementation

R28. The engine must maintain a committed-write record that tracks, for each recently written key in each table, the `commitTs` of the most recent committed write. This record must be retained for at least as long as the oldest active transaction's `startTs`.

R28a. The committed-write record must track writes from both transactional commits and non-transactional writes (F05 R32-R35), so that conflict detection (R24, R24a) has complete information.

R29. The committed-write record must be bounded. Entries with `commitTs` older than the oldest active transaction's `startTs` must be eligible for garbage collection.

R29a. When no transactions are active, all entries in the committed-write record are eligible for garbage collection.

R30. The committed-write record must be safe to query and update concurrently from multiple threads without external synchronization.

### Interaction with handle lifecycle (F34)

R31. A `TransactionalTable` obtained via `Transaction.table()` must acquire a handle from the engine's handle tracker (F05 R40). The handle must be tracked against the same per-source, per-table, and global budgets as regular handles.

R32. The handle priority for transactional handles must default to NORMAL but must be overridable via a `Transaction.withPriority(Priority level)` method called before any `table()` call.

R32a. Calling `withPriority()` after any `table()` call has been made must throw an `IllegalStateException`.

R33. A transactional handle must follow the same state machine as F34 R1: OPEN -> ACTIVE -> IDLE -> EXPIRED/CLOSED. The ACTIVE state immunity (F34 R5) protects a handle from eviction while a transactional operation is in progress.

R34. If a transactional handle is evicted (F34 R6) or expired (F34 R13, R19) while the transaction is uncommitted, all subsequent operations on that `TransactionalTable` must fail with the appropriate expiry exception (F34 R43).

R34a. When any transactional handle is expired or evicted while the transaction is uncommitted, the transaction must transition to an aborted state. Subsequent calls to `commit()` must return `Aborted` with reason indicating handle loss.

R35. When a transaction commits or aborts, all `TransactionalTable` handles acquired during the transaction must be released (closed). The handle count must decrease accordingly.

R36. Transactional handle acquisitions must respect the acquisition timeout configured in F34 R40. If no handle is available within the timeout, the transaction's `table()` call must throw an exception.

R36a. If a `table()` call fails due to handle acquisition timeout, the transaction must be aborted. All previously acquired handles must be released.

### Transaction resource limits

R37. The engine must enforce a configurable maximum number of concurrent active transactions (default: 1024). Exceeding this limit must cause `beginTransaction()` to throw an `IllegalStateException`.

R38. The engine must enforce a configurable maximum write buffer size per transaction in bytes (default: 64 MiB). A `put()` that would exceed this limit must throw an `IllegalStateException`.

R38a. When a `put()` is rejected due to write buffer size limit (R38), the transaction must be aborted. All previously buffered writes must be discarded and all acquired handles must be released.

R39. The engine must enforce a configurable transaction timeout (default: 30 seconds). A transaction that has not committed or aborted within this duration must be automatically aborted. Subsequent operations on the timed-out transaction must throw an `IllegalStateException` with reason TIMEOUT.

### Recovery

R40. On engine startup, the transaction WAL must be replayed after per-table WALs have been recovered. For each complete WAL record (CRC valid, entry count matches header), the engine must apply the writes to the appropriate table's MemTable.

R40a. Transaction WAL replay must be idempotent. If a write from the transaction WAL duplicates a write already present in a table's MemTable (from per-table WAL recovery), the later write (by `commitTs`) must win. Duplicate application must not corrupt state.

R41. Incomplete WAL records (truncated, CRC mismatch) must be discarded during recovery. An incomplete record means the transaction did not commit.

R41a. If the transaction WAL segment file itself is corrupt (unreadable header, all zeros), the engine must skip that segment and continue scanning subsequent segments. The engine must not abort recovery due to a single corrupt segment.

R42. If a WAL record references a table that no longer exists in the catalog (dropped after the transaction committed), the engine must skip the writes for that table. The remaining writes in the record must still be applied.

R43. After recovery replay completes, the committed-write record (R28) must be populated from the replayed WAL records so that conflict detection is correct for new transactions that start immediately after recovery.

R43a. On a fresh engine startup (no transaction WAL present), the committed-write record must start empty.

### Concurrency

R44. Multiple transactions must be able to execute concurrently. The engine must not hold a global lock during transaction commit beyond the duration of conflict detection and WAL write.

R45. The WAL write for a transaction commit must be serialized (one write at a time to the transaction WAL), but conflict detection and write buffering must proceed concurrently across transactions.

R46. The timestamp counter (R10) must be safe to access from multiple threads without external synchronization.

R46a. If `engine.close()` (F05 R6) is called while a transaction commit is in progress, the in-progress commit must be allowed to complete (WAL fsync and MemTable apply). Transactions that have not yet begun their commit must be aborted with reason ENGINE_SHUTDOWN.

R46b. Concurrent calls to `commit()` and `abort()` on the same transaction must resolve deterministically. If `commit()` has already begun conflict detection, `abort()` must block until `commit()` completes and then observe the terminal state. If `abort()` wins, a subsequent `commit()` must throw `IllegalStateException` (per R6).

### Input validation

R47. The engine builder must accept transaction-related configuration (max concurrent transactions, max write buffer size, transaction timeout, max WAL retention) via dedicated builder methods.

R48. The engine builder must reject zero or negative values for max concurrent transactions with an `IllegalArgumentException`.

R49. The engine builder must reject zero or negative values for max write buffer size with an `IllegalArgumentException`.

R50. The engine builder must reject zero or negative values for transaction timeout with an `IllegalArgumentException`.

R51. The engine builder must reject zero or negative values for max WAL retention duration with an `IllegalArgumentException`.

R52. The `Transaction.table()` method must reject null table names with a `NullPointerException`.

R53. The `TransactionalTable.put()` method must reject null keys with a `NullPointerException`.

R53a. The `TransactionalTable.put()` method must reject null values with a `NullPointerException`.

R54. The `TransactionalTable.delete()` method must reject null keys with a `NullPointerException`.

R55. The `TransactionalTable.get()` method must reject null keys with a `NullPointerException`.

---

## Design Narrative

### Intent

Define atomic multi-table write semantics for document operations that span table boundaries within a single engine instance. This spec resolves the deferred "cross-table transactions" decision from the engine-api-surface-design ADR, which listed "Transaction coordination across tables" as explicitly out of scope for the initial engine design.

### Scope boundary with F31

This spec and F31 (Cross-Partition Transactions) address different axes of atomicity:

- **F31** covers cross-partition transactions within a single table. Partitions may be on different nodes, so F31 uses the Percolator protocol (distributed, no coordinator, lock-based).
- **F35** covers cross-table transactions on a single node. All tables are local, so the simpler WriteBatch + shared WAL pattern is sufficient. No distributed coordination is needed.

If a transaction needs to span both tables and partitions, the caller must compose F35 (cross-table) with F31 (cross-partition) — that composition is out of scope for both specs.

### Why WriteBatch + shared WAL (pattern 3 from KB)

The KB research (cross-table-transaction-patterns.md) evaluated three patterns: shared WAL with transaction boundaries, per-table WAL with local 2PC, and write-batch accumulation. The write-batch approach is chosen for three reasons:

**Single fsync per commit.** Patterns 1 and 3 both achieve single-fsync commits, but pattern 2 (per-table WAL + 2PC) requires at minimum two fsyncs (prepare + commit log). For small-to-medium transactions — the common case in a document database — the single fsync of a write batch dominates.

**Simple recovery.** The transaction WAL record is self-contained. A complete record (CRC valid) means committed; a truncated record means uncommitted. No commit log correlation, no prepare record scanning, no cross-WAL joins. This is the same recovery model as per-table WAL replay — the engine already knows how to do it.

**Natural fit with existing WAL.** jlsm's WAL implementation already supports CRC-validated records with configurable segment sizes. The transaction WAL is just another WAL instance that writes batch records instead of single-entry records. No new WAL infrastructure is needed.

### Why a dedicated transaction WAL, not a shared WAL across all tables

The KB notes that a truly shared WAL (one WAL for all tables, pattern 1) introduces flush coupling: a low-traffic table blocks WAL reclamation for high-traffic tables. A dedicated transaction WAL limits coupling to tables that participate in transactions together. Tables that never participate in cross-table transactions are unaffected — their per-table WALs operate independently.

The `maxTxnWalRetention` flush trigger (R20) mitigates coupling for the transaction WAL by forcing slow tables to flush before the WAL grows unboundedly.

### Why OCC for conflict detection

Single-node cross-table transactions have a simpler conflict landscape than distributed cross-partition transactions (F31). All data is local, so conflict checks are cheap memory lookups. OCC (optimistic concurrency control) is chosen over pessimistic locking because:

**No lock management.** Pessimistic locking requires lock tables, deadlock detection, and lock-wait queues. For single-node transactions where conflict checks are local memory reads, this infrastructure is unnecessary overhead.

**Abort-then-retry over block-then-proceed.** OCC aborts conflicting transactions at commit time rather than blocking them at write time. For document workloads where cross-table conflicts are rare (different tables typically store different entity types), OCC's optimistic path has near-zero overhead.

**Consistent with F31's isolation model.** Both F31 and F35 provide snapshot isolation. OCC with write-write conflict detection at commit time is the natural implementation for SI on a single node.

### Why snapshot isolation, not serializability

Consistent with F31 R18 and the general jlsm position: snapshot isolation is sufficient for the vast majority of document operations. Write skew anomalies require a specific pattern (two transactions read overlapping sets and write to non-overlapping sets) that is uncommon in document databases where writes are typically to the document that was read. Serializability can be layered on top in a future spec by adding read-set tracking to conflict detection.

### Interaction with F34 handle lifecycle

Transactional table handles are tracked handles, not special-cased bypasses. This means:

- They consume budget from the global and per-table pools (R31).
- They respect priority levels (R32) so admin transactions can proceed under pressure.
- They benefit from ACTIVE-state immunity (R33) — a handle cannot be evicted mid-operation.
- They are automatically released on commit/abort (R35), preventing leaks.

The one subtlety is R34: if a handle is evicted while the transaction is in progress, the transaction must abort. This is necessary because the handle's eviction means the engine is under resource pressure, and continuing the transaction with a re-acquired handle could exacerbate the pressure. The caller should retry the transaction after the pressure subsides.

### Why non-transactional writes participate in conflict detection

R24a requires that direct `Table.put()`/`Table.delete()` calls (F05 R32-R35) feed into the committed-write record. Without this, a non-transactional write could silently overwrite a key that a concurrent transaction also writes, violating the write-write conflict guarantee. The committed-write record (R28, R28a) tracks both sources, and conflict detection checks against both. This means non-transactional writes impose a small overhead (one committed-write record entry per write), but the alternative — invisible conflicts — would make snapshot isolation semantics unreliable.

### What this spec does not cover

- **Cross-table + cross-partition composition.** A transaction spanning both table boundaries and partition boundaries requires composing F35 with F31. The composition protocol is out of scope.
- **Serializable isolation.** SI is the ceiling for this spec (R23). Read-set tracking for serializability is a future concern.
- **WAL group commit for transactions.** Multiple concurrent transactions could share a single fsync via group commit. This optimization is orthogonal and could be layered on top.
- **Distributed cross-table transactions.** When tables are on different nodes, the local WriteBatch pattern does not apply. This would require a distributed protocol (2PC or Percolator) across table boundaries.
- **Background garbage collection of committed-write records.** R29 defines eligibility for GC but does not specify the collection mechanism. A periodic sweep is the expected implementation but is not specified here.

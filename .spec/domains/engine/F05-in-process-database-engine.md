---
{
  "id": "F05",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["engine"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["table-catalog-persistence", "engine-api-surface-design"],
  "kb_refs": [],
  "open_obligations": []
}
---

# F05 — In-Process Database Engine

## Requirements

### Engine lifecycle

R1. The engine must be constructable via a builder pattern that accepts a root directory path as a mandatory parameter. The root directory must be an absolute path.

R2. The engine builder must reject a null root directory path at build time with a null pointer exception. The exception message must identify the parameter.

R3. The engine builder must reject a relative root directory path at build time with an illegal argument exception.

R4. If the root directory does not exist at engine startup, the engine must create it, including any necessary parent directories.

R5. If the root directory exists at engine startup, the engine must scan it for previously persisted table metadata and restore all tables that were in a ready state when the engine last shut down.

R6. The engine must implement AutoCloseable. Closing the engine must close all open tables, persist final catalog state, and release all held resources.

R7. Closing the engine must be idempotent: calling close on an already-closed engine must succeed silently without throwing an exception.

R8. After the engine is closed, all mutating operations (create table, drop table) must throw an IllegalStateException. The exception message must indicate the engine is closed.

R89. Handle registration must be rejected after the tracker or engine has been closed. Attempting to register a handle after shutdown must throw an IllegalStateException. [Audit: F-R1.concurrency.1.7]

R9. After the engine is closed, all read operations (list tables, get table, table metadata, metrics) must throw an IllegalStateException.

### Table creation

R10. The engine must support creating a table by name and schema. The table name must be non-null and non-empty.

R11. The engine must reject a null table name with a null pointer exception at the point of the create call.

R12. The engine must reject a null schema with a null pointer exception at the point of the create call.

R13. The engine must reject an empty table name with an illegal argument exception.

R14. Creating a table with a name that already exists in the catalog (regardless of state) must throw an illegal argument exception. The exception message must include the conflicting table name.

R15. On successful table creation, the engine must persist the table's metadata to the catalog before returning to the caller. A crash after create returns must not lose the table definition on recovery.

R16. Each table must be stored in a dedicated subdirectory under the engine's root directory. The subdirectory name must be deterministically derived from the table name.

R17. If directory creation for a new table fails (I/O error, permissions), the engine must not add the table to the catalog. The operation must throw an IOException.

R87. If table creation fails after the storage directory has been created (e.g., catalog registration error, handle tracker failure), the engine must roll back by removing the directory and any partial state. No orphaned resources may remain. [Audit: F-R1.cb.1.11]

### Table metadata and introspection

R18. The engine must expose metadata for each table, including at minimum: the table name, the schema, and the table state.

R19. Table state must distinguish at minimum: loading, ready, dropped, and error. Only tables in the ready state must accept data operations (insert, query, scan, update, delete).

R20. The engine must support listing all table names that are currently in the ready state. The returned collection must be a snapshot that is not affected by concurrent modifications.

R21. Requesting metadata for a table name that does not exist must return an empty result, not throw an exception.

### Table retrieval

R22. The engine must support retrieving a table handle by name. The returned handle must support CRUD and query operations.

R23. Retrieving a table that does not exist must return an empty result, not throw an exception.

R24. Retrieving a table that is in the dropped state must return an empty result, not throw an exception.

R25. Retrieving a table that is in the loading or error state must return an empty result.

### Table drop

R26. The engine must support dropping a table by name. Dropping a table must transition it to the dropped state in the catalog.

R27. Dropping a table must persist the state change to the catalog before returning to the caller.

R28. Dropping a table that does not exist must throw an illegal argument exception. The exception message must include the table name.

R29. After a table is dropped, any previously obtained handle for that table must reject all subsequent data operations with an IllegalStateException. The exception must indicate the table has been dropped.

R30. After a table is dropped and the engine is restarted, the dropped table must not appear in the list of ready tables.

R31. Dropping a table must remove the table's storage directory and all contained files. If deletion fails, the table must still be marked as dropped in the catalog, and the engine must log or surface the cleanup failure without preventing the drop from completing.

### Table CRUD operations

R32. The table handle must support inserting a document by key, delegating to the underlying table implementation.

R33. The table handle must support retrieving a document by key, delegating to the underlying table implementation.

R34. The table handle must support updating a document by key, delegating to the underlying table implementation.

R35. The table handle must support deleting a document by key, delegating to the underlying table implementation.

R36. Every data operation on a table handle must validate that the handle is still valid (table not dropped, engine not closed) before delegating. Invalid handles must throw an IllegalStateException.

R83. Table handle operations must synchronize validity checks with the operation they guard. The validity check and the delegated operation must execute atomically with respect to concurrent invalidation. [Audit: F-R1.concurrency.1.4]

### Query pass-through

R37. The table handle must support executing queries via the fluent query API provided by the underlying table implementation.

R38. The table handle must support scan operations (range iteration) via the underlying table implementation.

R39. Query and scan operations must validate handle validity before execution, consistent with CRUD operations.

### Handle tracking and eviction

R40. The engine must track all outstanding table handles. The total number of open handles must be bounded by a configurable maximum.

R80. The engine must enforce handle limits by invoking eviction checks on every new handle registration. A registration that would exceed any configured limit must trigger eviction before the registration completes. [Audit: F-R1.cb.1.1]

R41. The maximum handles per table must be independently configurable with a default value.

R42. The maximum handles per source per table must be independently configurable with a default value. A source is identified by a caller-provided tag or, if allocation tracking is enabled, by the calling thread or stack.

R91. The source identifier used for per-source handle tracking must be unique per caller. When allocation tracking uses thread identity, the identifier must be the thread ID (not the thread name), which is guaranteed unique by the JVM. [Audit: F-R1.shared_state.2.4]

R43. The engine must support three allocation tracking modes: off, caller tag, and full stack. The default mode must be off.

R44. When the handle limit for a source is reached, the engine must evict the least recently used handle for that source before issuing a new one.

R45. When the per-table handle limit is reached and no single-source eviction can free a slot, the engine must evict the least recently used handle across all sources for that table.

R46. When the global handle limit is reached and no per-table eviction can free a slot, the engine must evict the least recently used handle across all tables.

R81. When the global handle limit is reached and eviction is required, the engine must evict from the table with the highest handle count, not from the table that triggered the limit check. [Audit: F-R1.resource_lifecycle.1.8]

R47. An evicted handle must reject all subsequent operations with an exception that includes the eviction reason: eviction pressure, engine shutdown, or table dropped.

R48. The eviction reason must be queryable from the exception so callers can distinguish transient eviction from permanent invalidation.

### Handle registration lifecycle

R49. Each handle registration must track whether it has been invalidated. The invalidation flag must be visible to all threads without requiring the caller to synchronize.

R50. Invalidating a handle must be idempotent: invalidating an already-invalid handle must succeed silently.

R51. Handle registration must record the creation time for LRU eviction ordering.

### Catalog persistence

R52. The table catalog must persist table metadata to a file within the engine's root directory. The catalog file location must be deterministic and fixed.

R53. The catalog must survive engine restarts: all tables that were in a ready or loading state at shutdown must be recoverable on the next startup.

R85. The catalog must persist the table state (ready, loading, dropped, error) for each table entry. A table recovered from the catalog must reflect the state it had when the metadata was last written. [Audit: F-R1.cb.2.3]

R54. Catalog writes must be atomic with respect to crashes: a crash during a catalog write must not leave the catalog in a corrupt or unreadable state. The implementation must use a write-then-rename pattern or equivalent.

R55. The catalog must store sufficient information to reconstruct the table's schema and locate its storage directory without scanning subdirectories.

R84. The catalog must persist all schema field definitions including field name, field type, and type-specific parameters (e.g., length bounds). A table recovered from the catalog must have a schema identical to the schema provided at creation time. [Audit: F-R1.cb.2.2]

R56. The catalog must reject duplicate table names at the persistence layer. If a catalog file on disk already contains a table with the same name as one being added, the write must fail.

### Recovery

R57. On startup, if the catalog file is present, the engine must read it and restore all tables listed as ready. Tables listed as dropped must not be restored.

R58. On startup, if a table's storage directory is missing but the catalog lists it as ready, the engine must transition that table to the error state rather than crashing.

R59. On startup, if the catalog file is absent, the engine must treat the root directory as a fresh installation with no tables.

R60. On startup, if the catalog file is present but corrupt (not parseable), the engine must throw an IOException rather than silently starting with an empty catalog. Data loss must never be silent.

R61. On startup, if a table's underlying LSM tree data is corrupt (WAL replay failure, SSTable corruption), the engine must transition that table to the error state and continue starting the remaining tables. The engine must not abort startup due to a single table's corruption.

R86. If reading a table's metadata fails during startup recovery (I/O error, corrupt data), the engine must not delete the table's storage directory. The engine must mark the table as errored and preserve its data for manual recovery. [Audit: F-R1.cb.2.4]

### Engine metrics

R62. The engine must expose metrics including: the count of ready tables, the total number of open handles, the number of open handles per table, and the number of open handles per source per table.

R63. Metrics must reflect the current state at the time of the call. Stale or cached metric values are not acceptable.

R64. Metrics must be safe to query concurrently from multiple threads without external synchronization.

### Thread safety

R65. All engine operations (create table, drop table, list tables, get table, table metadata, metrics, close) must be safe to call concurrently from multiple threads without external synchronization.

R66. Concurrent create-table calls with different names must both succeed independently.

R67. Concurrent create-table calls with the same name must result in exactly one success and one illegal argument exception. Neither call may corrupt the catalog.

R88. Concurrent table creation calls with different names must not interfere with each other's storage directories. A failed concurrent registration must only clean up its own resources. [Audit: F-R1.cb.2.6]

R68. A drop-table call concurrent with an in-progress query on the same table must not cause the query to throw an unexpected exception. The query must either complete with results obtained before the drop, or throw an IllegalStateException indicating the table was dropped.

R69. Handle eviction must be safe under concurrent access. Two threads evicting handles simultaneously must not corrupt the handle tracker's internal state.

### Input validation

R70. All public API methods on the engine must reject null arguments with a null pointer exception. The exception message must identify the null parameter.

R71. The engine builder must reject configuration values that are zero or negative for handle limits at build time with an illegal argument exception.

R72. The engine builder must reject a maximum handles-per-source-per-table value that exceeds the maximum handles-per-table value at build time with an illegal argument exception.

R73. The engine builder must reject a maximum handles-per-table value that exceeds the maximum total handles value at build time with an illegal argument exception.

R90. The engine builder must reject handle limit configurations where per-source-per-table exceeds per-table, or per-table exceeds total. Contradictory limits must be detected at build time. [Audit: F-R1.cb.1.5]

### JPMS module boundaries

R74. The engine module must declare a JPMS module that exports only the public API package. Internal implementation packages (catalog, handle tracker, local engine internals) must not be exported.

R75. The engine module must depend on the table module. The engine module must not depend on the core module directly if all needed types are re-exported by the table module.

R76. The engine's public API must not expose types from internal packages of any dependency. All parameter and return types in public methods must come from exported packages.

### Resource cleanup

R77. Closing a table handle must release its registration from the handle tracker. The handle count must decrease by one.

R82. When a handle is released (via close or explicit release), the handle must be immediately invalidated before being removed from the tracker. A released handle must not pass validity checks. [Audit: F-R1.resource_lifecycle.1.4]

R78. If an error occurs while closing one table during engine shutdown, the engine must continue closing the remaining tables. All accumulated errors must be reported after all tables have been closed.

R79. The engine must not leak file handles or off-heap memory. Every opened channel, arena, or buffer pool resource must be released during engine or table close.

---

## Design Narrative

### Intent

Provide an in-process database engine that manages multiple named tables under a single root directory. The engine is the entry point for applications that want schema-driven document storage without assembling LSM tree components manually. It handles table lifecycle (create, recover, drop), handle bookkeeping with bounded resource consumption, and catalog persistence for crash recovery. All data operations delegate to the existing jlsm-table fluent API.

### Why this approach

**Catalog-backed persistence over directory-scanning discovery:** A catalog file provides O(1) lookup of table metadata and guarantees that table state (ready, dropped) survives restarts. Directory scanning would require conventions for encoding state into directory names or marker files, which is fragile and non-atomic.

**Handle tracking with eviction over unbounded handles:** Library consumers may open many table references from different components. Without a bound, an application can exhaust file descriptors or off-heap memory. The handle tracker enforces configurable limits with LRU eviction, keeping resource usage predictable. The three-tier eviction (per-source, per-table, global) ensures fair sharing.

**Atomic catalog writes over append-only logs:** A write-then-rename pattern for catalog persistence guarantees that the catalog is always in a valid state. An append-only log would require compaction and replay, adding complexity disproportionate to the catalog's small size.

**Delegation over re-implementation:** The engine's table handles delegate all CRUD and query operations to existing JlsmTable implementations. This avoids duplicating the table layer's logic and ensures that improvements to the table layer automatically benefit engine users.

**Error-state tables over crash-on-corruption:** When a table's underlying data is corrupt, marking it as errored and continuing startup is safer than aborting. The engine can serve other tables while the operator investigates the corrupted one. This follows the principle of partial availability.

### What was ruled out

- **Network protocol support:** Out of scope. The engine is in-process only. Network access is a separate concern (see F04 for clustering).
- **Cross-table transactions:** Requires a transaction coordinator and conflict resolution, which is a separate feature with significant complexity.
- **Engine-level query language:** SQL or custom query syntax is handled by jlsm-sql. The engine exposes the fluent API.
- **Automatic schema migration:** Schema evolution (adding/removing fields, changing types) is a complex problem that should be addressed as a separate feature.
- **Background compaction management:** The engine delegates compaction to the underlying LSM tree. Centralized compaction scheduling across tables could be added later.

### Out of scope

- Network protocols (REST, gRPC, wire protocol)
- Cluster distribution and replication
- Engine-level query language or SQL pass-through
- Cross-table joins or transactions
- Automatic schema migration or versioning
- Background compaction coordination across tables

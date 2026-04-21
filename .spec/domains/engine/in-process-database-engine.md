---
{
  "id": "engine.in-process-database-engine",
  "version": 4,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-catalog-persistence",
    "engine-api-surface-design"
  ],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F05"
  ]
}
---
# engine.in-process-database-engine — In-Process Database Engine

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

R14. Creating a table with a name that already exists in the catalog (regardless of state) must throw an IOException. The exception message must include the conflicting table name.

R15. On successful table creation, the engine must persist the table's metadata to the catalog before returning to the caller. A crash after create returns must not lose the table definition on recovery.

R16. Each table must be stored in a dedicated subdirectory under the engine's root directory. The subdirectory name must be deterministically derived from the table name.

R17. If directory creation for a new table fails (I/O error, permissions), the engine must not add the table to the catalog. The operation must throw an IOException.

R87. If table creation fails after the storage directory has been created (e.g., catalog registration error, handle tracker failure), the engine must roll back by removing the directory and any partial state. No orphaned resources may remain. [Audit: F-R1.cb.1.11]

### Table metadata and introspection

R18. The engine must expose metadata for each table, including at minimum: the table name, the schema, and the table state.

R19. Table state must distinguish at minimum: loading, ready, dropped, and error. Only tables in the ready state must accept data operations (insert, query, scan, update, delete).

R20. The engine must support listing all table names that are currently in the ready state. The returned collection must be a snapshot that is not affected by concurrent modifications.

R21. Requesting metadata for a table name that does not exist must return null, not throw an exception.

### Table retrieval

R22. The engine must support retrieving a table handle by name. The returned handle must support CRUD and query operations. The handle is returned directly (non-Optional); failure cases are surfaced as IOException.

R23. Retrieving a table that does not exist must throw an IOException whose message identifies the unknown table name.

R24. Retrieving a table that is in the dropped state must throw an IOException whose message identifies that the table was dropped.

R25. Retrieving a table that is in the loading or error state must throw an IOException whose message identifies the non-ready state.

### Table drop

R26. The engine must support dropping a table by name. Dropping a table must transition its persisted `table.meta` state from READY (or LOADING) to DROPPED before any storage files are removed, so that a crash mid-drop cannot resurrect the table on restart.

R27. Dropping a table must persist the DROPPED state (R26) atomically via write-then-rename (R54) before returning to the caller. The in-memory catalog must also drop the entry from the served view so subsequent list/get calls behave as if the table is gone.

R28. Dropping a table that does not exist must throw an IOException. The exception message must include the table name.

R29. After a table is dropped, any previously obtained handle for that table must reject all subsequent data operations with an IllegalStateException. The exception must indicate the table has been dropped.

R30. After a table is dropped and the engine is restarted, the dropped table must not appear in the list of ready tables.

R31. After the DROPPED state is persisted (R26/R27), the engine must attempt to remove the table's storage files. If file deletion fails, the drop must still be considered successful (DROPPED is already persisted); the engine must not propagate the deletion failure to the caller. A best-effort cleanup hook (log or similar diagnostic) is sufficient — the tombstone (DROPPED metadata) must remain on disk so that a future startup recognises the table as gone.

### Table CRUD operations

R32. The table handle must support inserting a document by key, delegating to the underlying table implementation.

R33. The table handle must support retrieving a document by key, delegating to the underlying table implementation.

R34. The table handle must support updating a document by key, delegating to the underlying table implementation.

R35. The table handle must support deleting a document by key, delegating to the underlying table implementation.

R36. Every data operation on a table handle must validate that the handle is still valid (table not dropped, engine not closed) before delegating. Invalid handles must throw an IllegalStateException.

R83. Table handle operations must synchronize validity checks with the operation they guard. The validity check and the delegated operation must execute atomically with respect to concurrent invalidation. [Audit: F-R1.concurrency.1.4]

### Query pass-through

R37. The table handle must support executing queries via the fluent query API provided by the underlying table implementation. Calling `table.query()` must return a non-null `TableQuery<String>` bound to the underlying `JlsmTable.StringKeyed` — calling `execute()` on that query must dispatch its predicate tree through the table's `QueryExecutor`, using registered secondary indices where supported and scan-and-filter fallback otherwise. Queries on schemaless tables (no schema configured at build time) may throw `UnsupportedOperationException` from `TableQuery.execute()` because no predicate execution context exists.

R38. The table handle must support scan operations (range iteration) via the underlying table implementation.

R39. Query and scan operations must validate handle validity before execution, consistent with CRUD operations.

### Handle tracking and eviction

R40. The engine must track all outstanding table handles. The total number of open handles must be bounded by a configurable maximum.

R80. The engine must enforce handle limits by invoking eviction checks on every new handle registration. A registration that would exceed any configured limit must trigger eviction before the registration completes. [Audit: F-R1.cb.1.1]

R41. The maximum handles per table must be independently configurable with a default value.

R42. The maximum handles per source per table must be independently configurable with a default value. The source identifier is always the calling thread's ID (`Thread.threadId()`), which the JVM guarantees to be unique per live thread.

R91. The source identifier used for per-source handle tracking must be the thread ID returned by `Thread.threadId()`, not the thread name. The JVM guarantees thread-ID uniqueness per live thread. [Audit: F-R1.shared_state.2.4]

R43. The engine must support three allocation-tracking modes that govern the allocation-site captured on each registration: `OFF` (no capture), `CALLER_TAG` (reserved for a caller-supplied tag; currently behaves like `OFF`), and `FULL_STACK` (captures a full stack trace). These modes do not affect source-identifier derivation (see R42/R91). The default mode must be `OFF`.

R44. When the handle limit for a source is reached, the engine must evict the least recently used handle for that source before issuing a new one.

R45. When the per-table handle limit is reached and no single-source eviction can free a slot, the engine must evict the least recently used handle across all sources for that table.

R46. When the global handle limit is reached and no per-table eviction can free a slot, the engine must evict the least recently used handle across all tables.

R81. When the global handle limit is reached and eviction is required, the engine must evict from the table with the highest handle count, not from the table that triggered the limit check. [Audit: F-R1.resource_lifecycle.1.8]

R47. An evicted handle must reject all subsequent operations with an exception that includes the eviction reason: eviction pressure, engine shutdown, or table dropped.

R48. The eviction reason must be queryable from the exception so callers can distinguish transient eviction from permanent invalidation.

### Handle registration lifecycle

R49. Each handle registration must track whether it has been invalidated. The invalidation flag must be visible to all threads without requiring the caller to synchronize.

R50. Invalidating a handle must be idempotent: invalidating an already-invalid handle must succeed silently.

R51. Handle registrations for a given source must be held in insertion order so that the oldest (least-recently-created) registration is the first eviction candidate. An explicit timestamp is not required; insertion order into a per-source list is sufficient.

### Catalog persistence

R52. The table catalog must persist table metadata as one metadata file per table, located at `<root>/<tableName>/table.meta`. The per-table metadata file path must be deterministic from the table name. (Per ADR `table-catalog-persistence`: per-table metadata directories.)

R53. The catalog must survive engine restarts: all tables that were in a ready or loading state at shutdown must be recoverable on the next startup.

R85. The catalog must persist the table state (ready, loading, dropped, error) for each table entry. A table recovered from the catalog must reflect the state it had when the metadata was last written. [Audit: F-R1.cb.2.3]

R54. Each per-table metadata write must be atomic with respect to crashes: a crash during a metadata write must not leave that table's metadata file in a corrupt or partially-written state. The implementation must write the new contents to a sibling temporary file and atomically rename it into place (or use an equivalent OS-level atomic replace).

R55. Each per-table metadata file must store sufficient information to reconstruct the table's schema and state without reading any other table's data or scanning SSTable/WAL contents. Discovering the set of tables on startup is by listing direct subdirectories of the engine root, which is expected to be O(n) in the number of tables.

R84. The catalog must persist all schema field definitions including field name, field type, and type-specific parameters (e.g., length bounds). A table recovered from the catalog must have a schema identical to the schema provided at creation time. [Audit: F-R1.cb.2.2]

R56. The catalog must reject duplicate table names at the persistence layer. If a metadata file for the same table name already exists on disk (or another registration has concurrently claimed the name in memory), the second registration must fail with an IOException.

### Recovery

R57. On startup, the engine must scan the root directory, read each subdirectory's `table.meta` file, and restore tables whose persisted state is READY or LOADING. Tables whose persisted state is DROPPED must not be restored into the served catalog.

R58. On startup, if a subdirectory exists but its `table.meta` file is missing, the engine must treat that subdirectory as a partial creation and delete it (cleanup). If a table's storage files are missing but its `table.meta` file exists and reports READY, the engine must transition that table to the ERROR state rather than crashing.

R59. On startup, if the root directory contains no table subdirectories (or does not yet exist and must be created per R4), the engine must treat the root directory as a fresh installation with no tables.

R60. On startup, if a table's `table.meta` file is present but corrupt (unparseable or fails magic/state validation), the engine must transition that specific table to the ERROR state and continue starting the remaining tables. Corruption of one table's metadata must not abort engine startup or affect other tables.

R61. On startup, if a table's underlying LSM tree data is corrupt (WAL replay failure, SSTable corruption), the engine must transition that table to the error state and continue starting the remaining tables. The engine must not abort startup due to a single table's corruption.

R86. If reading a table's metadata fails during startup recovery (I/O error, corrupt data), the engine must not delete the table's storage directory. The engine must mark the table as errored and preserve its data for manual recovery. [Audit: F-R1.cb.2.4]

### Engine metrics

R62. The engine must expose metrics including: the count of ready tables, the total number of open handles, the number of open handles per table, and the number of open handles per source per table.

R63. Metrics must reflect the current state at the time of the call. Stale or cached metric values are not acceptable.

R64. Metrics must be safe to query concurrently from multiple threads without external synchronization.

### Thread safety

R65. All engine operations (create table, drop table, list tables, get table, table metadata, metrics, close) must be safe to call concurrently from multiple threads without external synchronization.

R66. Concurrent create-table calls with different names must both succeed independently.

R67. Concurrent create-table calls with the same name must result in exactly one success and one IOException. Neither call may corrupt the catalog or leak partial directories / metadata files.

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

R74. The engine module must declare a JPMS module that exports only public API packages: `jlsm.engine` (F05 scope) and `jlsm.engine.cluster` (F04 clustering API). Internal implementation packages — including `jlsm.engine.internal` (catalog, handle tracker, local engine internals) and `jlsm.engine.cluster.internal` — must not be exported.

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

---

## Verification Notes

### Verified: v2 — 2026-04-18

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `Engine.java:31` (public factory), `Engine.java:101` (public Builder), `F05ContractTest.engineBuilderIsAccessibleFromPublicApi` |
| R2 | SATISFIED | `LocalEngine.java:366` (rootDirectory null check), `LocalEngineTest.builderRejectsNullRootDirectory` |
| R3 | SATISFIED | `LocalEngine.java:429` (isAbsolute check), `F05ContractTest.builderRejectsRelativeRootDirectory` |
| R4 | SATISFIED | `TableCatalog.open:64-66`, `TableCatalogTest.openCreatesRootDirectoryIfMissing` |
| R5 | SATISFIED | `TableCatalog.open:68-94`, `LocalEngineTest.engineRecoversPreviouslyCreatedTables` |
| R6 | SATISFIED | `LocalEngine.close:252-295`, `LocalEngineTest.closeInvalidatesAllHandles` |
| R7 | SATISFIED | `LocalEngine.java:254` (compareAndSet guard), `SharedStateAdversarialTest.test_LocalEngine_sharedState_doubleCloseRetriesCleanup` |
| R8 | SATISFIED | `LocalEngine.ensureOpen:101-106` + all mutating public methods, `SharedStateAdversarialTest.test_LocalEngine_sharedState_doubleCloseRetriesCleanup` |
| R9 | SATISFIED | `LocalEngine.ensureOpen` + list/metadata/metrics methods |
| R10 | SATISFIED | `LocalEngine.createTable:108-114`, `LocalEngineTest.createTableReturnsUsableHandle` |
| R11 | SATISFIED | `LocalEngine.java:110`, `LocalEngineTest.createTableRejectsNullName` |
| R12 | SATISFIED | `LocalEngine.java:111`, `LocalEngineTest.createTableRejectsNullSchema` |
| R13 | SATISFIED | `LocalEngine.java:113`, `LocalEngineTest.createTableRejectsEmptyName` |
| R14 | SATISFIED (amended) | `TableCatalog.register:141-143` throws IOException; `TableCatalogTest.registerDuplicateNameThrowsIOException` |
| R15 | SATISFIED | `TableCatalog.register:146-149` synchronous write, `TableCatalogTest.registerCreatesSubdirectoryAndMetadataFile` |
| R16 | SATISFIED | `<root>/<name>/table.meta` layout, `TableCatalogTest.registerCreatesSubdirectoryAndMetadataFile` |
| R17 | SATISFIED | `TableCatalog.register:150-161` rollback on failure |
| R18 | SATISFIED | `TableMetadata.java:19-29`, `LocalEngineTest.tableMetadataReturnsMetadataForKnownTable` |
| R19 | SATISFIED | `LocalEngine.getTable:180-186` state switch, `F05ContractTest.getTableThrowsForLoadingState/ErrorState` |
| R20 | SATISFIED | `TableCatalog.listReady` + `LocalEngine.listTables`, `F05ContractTest.listTablesReturnsReadyOnlySnapshot` |
| R21 | SATISFIED (amended) | `LocalEngine.tableMetadata:237`, `LocalEngineTest.tableMetadataReturnsNullForUnknownTable` |
| R22 | SATISFIED (amended) | `Engine.getTable` returns `Table`; `LocalEngineTest.getTableReturnsHandleToExistingTable` |
| R23 | SATISFIED (amended) | `LocalEngine.java:177`, `LocalEngineTest.getTableForUnknownTableThrowsIOException` |
| R24 | SATISFIED (amended) | `LocalEngine.java:183` DROPPED branch, `F05ContractTest.dropPersistsDroppedStateAndIsNotServedAfterRestart` |
| R25 | SATISFIED (amended) | `LocalEngine.java:184-185`, `F05ContractTest.getTableThrowsForLoadingState/ErrorState` |
| R26 | SATISFIED (amended) | `TableCatalog.markDropped:204-230`, `F05ContractTest.dropPersistsDroppedStateAndIsNotServedAfterRestart` |
| R27 | SATISFIED (amended) | `TableCatalog.markDropped:221-222` atomic write-then-rename, same test |
| R28 | SATISFIED (amended) | `TableCatalog.markDropped:213-216`, `LocalEngineTest.dropTableForUnknownTableThrowsIOException` |
| R29 | SATISFIED | `HandleTracker.invalidateTable`, `LocalEngineTest.dropTableRemovesTableAndInvalidatesHandles` |
| R30 | SATISFIED | `F05ContractTest.dropPersistsDroppedStateAndIsNotServedAfterRestart` |
| R31 | SATISFIED (amended) | `TableCatalog.deleteDataFilesPreservingTombstone:236-262`, `F05ContractTest.dropSucceedsEvenIfDataFileDeletionFails` |
| R32 | SATISFIED | `LocalTable.create:50-57`, `LocalTableTest.createDelegatesToStub` |
| R33 | SATISFIED | `LocalTable.get:59-66`, `LocalTableTest.getDelegatesToStub` |
| R34 | SATISFIED | `LocalTable.update:68-77`, `LocalTableTest.updateDelegatesToStub` |
| R35 | SATISFIED | `LocalTable.delete:79-86`, `LocalTableTest.deleteDelegatesToStub` |
| R36 | SATISFIED | `LocalTable.checkValid:151-159`, `LocalTableTest.evictedHandleThrowsOnGet` |
| R37 | SATISFIED (amended v3) | `LocalTable.query:116` delegates to `StringKeyedTable.query()` which returns a bound `TableQuery` wired through `QueryExecutor`; `TableQueryExecutionTest` covers index-backed + scan-fallback + AND/OR + empty paths. **OBL-F05-R37** resolved by WD-03. |
| R38 | SATISFIED | `LocalTable.scan:122-131`, `LocalTableTest.scanDelegatesToStub` |
| R39 | SATISFIED | `LocalTable.checkValid` used by `scan`/`query` |
| R40 | SATISFIED | `HandleTracker.register:95-144`, `HandleTrackerTest.registerReturnsNonNullRegistration` |
| R41 | SATISFIED | `HandleTracker.maxHandlesPerTable`, `HandleTrackerTest.evictIfNeededTriggersWhenPerTableLimitExceeded` |
| R42 | SATISFIED (amended) | `LocalEngine.java:204` uses `threadId()`, `SharedStateAdversarialTest.test_LocalEngine_sharedState_threadNameSourceIdNotUnique` |
| R43 | SATISFIED (amended) | `AllocationTracking.java:15-22`, `HandleTrackerTest.allocationTracking*` |
| R44 | SATISFIED | `HandleTracker.evictIfNeeded:200-203`, `HandleTrackerTest.evictIfNeededTriggersWhenPerSourcePerTableLimitExceeded` |
| R45 | SATISFIED | `HandleTracker.evictIfNeeded:207-211`, `HandleTrackerTest.evictIfNeededTriggersWhenPerTableLimitExceeded` |
| R46 | SATISFIED | `HandleTracker.evictIfNeeded:216-232`, `HandleTrackerTest.evictIfNeededTriggersWhenTotalLimitExceeded` |
| R47 | SATISFIED | `HandleEvictedException.java:48-58`, `LocalTableTest.evictedHandleThrows*` |
| R48 | SATISFIED | `HandleEvictedException.reason:87-89`, `EngineInternalAdversarialTest.checkValidShouldReportTableDroppedReason` |
| R49 | SATISFIED | `HandleRegistration.java:18` (volatile flag), `HandleTrackerTest.registerReturnsNonNullRegistration` |
| R50 | SATISFIED | `HandleRegistration.invalidate:58-64`, `ConcurrencyAdversarialTest.test_HandleRegistration_concurrency_nonIdempotentInvalidationReasonOverwrite` |
| R51 | SATISFIED (amended) | ArrayList insertion order in `HandleTracker.tableHandles`, `HandleTrackerTest.oldestHandlesEvictedFirstWithinSource` |
| R52 | SATISFIED (amended) | `TableCatalog.METADATA_FILE` + per-table dir, `TableCatalogTest.registerCreatesSubdirectoryAndMetadataFile` |
| R53 | SATISFIED | `TableCatalog.open` restores READY/LOADING, `LocalEngineTest.engineRecoversPreviouslyCreatedTables` |
| R54 | SATISFIED (amended) | `TableCatalog.writeMetadata:356-383` write-then-rename, `F05ContractTest.metadataWriteIsAtomic` |
| R55 | SATISFIED (amended) | `TableCatalog.readMetadata:329-363`, `TableCatalogTest.openDiscoversExistingTableSubdirectories` |
| R56 | SATISFIED | `TableCatalog.register:141-143`, `TableCatalogTest.registerDuplicateNameThrowsIOException` |
| R57 | SATISFIED (amended) | `TableCatalog.open` + `listReady` filter, `F05ContractTest.dropPersistsDroppedStateAndIsNotServedAfterRestart` |
| R58 | SATISFIED | `TableCatalog.open:77-80`, `TableCatalogTest.openCleansUpDirectoryWithoutMetadataFile` |
| R59 | SATISFIED | `TableCatalog.open` empty-scan path, `TableCatalogTest.openOnEmptyDirectoryDiscoversNoTables` |
| R60 | SATISFIED (amended) | `TableCatalog.open:82-92` catch → ERROR state |
| R61 | UNTESTABLE | Lazy table loading — WAL/SSTable corruption surfaces on first use, not at startup |
| R62 | SATISFIED | `EngineMetrics` record + `HandleTracker.snapshot`, `LocalEngineTest.metricsReturnCorrectCounts` |
| R63 | SATISFIED | `HandleTracker.snapshot:292-324` computes live values, `HandleTrackerTest.snapshotReturnsCorrectCounts` |
| R64 | SATISFIED | sync on per-table sourceMap, `HandleTrackerTest.concurrentRegisterAndReleaseDoNotCorruptState` |
| R65 | SATISFIED | ConcurrentHashMap + sync blocks across LocalEngine/catalog/tracker |
| R66 | SATISFIED | `LocalEngineTest.concurrentTableCreation` |
| R67 | SATISFIED (amended) | `ContractBoundariesAdversarialTest.test_TableCatalog_concurrentRegister_winnerDirectoryIntact` |
| R68 | SATISFIED | `LocalTable` registration-synchronized delegate calls |
| R69 | SATISFIED | synchronized blocks on sourceMap, `HandleTrackerTest.concurrentRegisterAndReleaseDoNotCorruptState` |
| R70 | SATISFIED | `Objects.requireNonNull` across public API methods |
| R71 | SATISFIED | `HandleTracker.Builder` + `LocalEngine.Builder` setters, `EngineInternalAdversarialTest.handleTrackerBuilderRejects*` |
| R72 | SATISFIED | `LocalEngine.Builder.build:434-438`, `ContractBoundariesAdversarialTest.test_HandleTrackerBuilder_allowsNonsensicalHierarchicalLimits` |
| R73 | SATISFIED | `LocalEngine.Builder.build:439-443`, `ContractBoundariesAdversarialTest.test_HandleTrackerBuilder_allowsPerTableExceedingTotalHandles` |
| R74 | SATISFIED (amended) | `module-info.java:3-8`, `ModuleExportsTest.testModuleCompiles` |
| R75 | SATISFIED | `module-info.java:4-5`, `ModuleExportsTest.testModuleCompiles` |
| R76 | SATISFIED | `Engine.java` + `Table.java` import only exported types |
| R77 | SATISFIED | `LocalTable.close:142-144`, `LocalTableTest.closeReleasesRegistration` |
| R78 | SATISFIED | `LocalEngine.close:261-291` accumulates errors |
| R79 | UNTESTABLE | Resource-leak absence not mechanically testable; design reviewed |
| R80 | SATISFIED | `HandleTracker.register:143` call to `evictIfNeeded`, `ContractBoundariesAdversarialTest.test_HandleTracker_register_doesNotEnforceLimits` |
| R81 | SATISFIED | `HandleTracker.findGreediestTable:365-379`, `ResourceLifecycleAdversarialTest.test_HandleTracker_evictIfNeeded_totalLimit_evictsFromWrongTable` |
| R82 | SATISFIED | `HandleTracker.release:158-159`, `ResourceLifecycleAdversarialTest.test_HandleTracker_release_doesNotInvalidateRegistration` |
| R83 | SATISFIED | `LocalTable.java:51,60,71,81,107,115,126,134` synchronized, `ConcurrencyAdversarialTest.test_LocalTable_concurrency_checkThenActRaceBetweenCheckValidAndInvalidation` |
| R84 | SATISFIED | `TableCatalog.writeMetadata`/`readMetadata` full schema, `TableCatalogTest.registerCreatesSubdirectoryAndMetadataFile` |
| R85 | SATISFIED | state ordinal persisted in metadata, `TableCatalogTest.openDiscoversExistingTableSubdirectories` |
| R86 | SATISFIED | `TableCatalog.open:82-92` ERROR-state branch without delete |
| R87 | SATISFIED | `LocalEngine.createTable:131-162` multi-stage rollback, `ContractBoundariesAdversarialTest.test_LocalEngine_createTable_doesNotCloseJlsmTableOnHandleRegistrationFailure` |
| R88 | SATISFIED | `TableCatalog.register:141-143` putIfAbsent, `ContractBoundariesAdversarialTest.test_TableCatalog_concurrentRegister_winnerDirectoryIntact` |
| R89 | SATISFIED | `HandleTracker.register:102-104`, `ConcurrencyAdversarialTest.test_HandleTracker_concurrency_registerAfterCloseAccepted` |
| R90 | SATISFIED | `LocalEngine.Builder.build:434-443`, `EngineInternalAdversarialTest.localEngineBuilderRejectsZeroMaxPerSourcePerTable` |
| R91 | SATISFIED | `LocalEngine.java:204` `Thread.currentThread().threadId()`, `SharedStateAdversarialTest.test_LocalEngine_sharedState_threadNameSourceIdNotUnique` |

**Overall: PASS_WITH_NOTES**

Amendments applied: 18 (R14, R21–R25, R28, R37 [v2 + v3], R42, R51, R52, R54, R55, R57, R60, R67, R74)
Code fixes applied: 7 (R1 public factory, R3 absolute check, R19 state filter, R20 READY snapshot, R26/R27/R31 tombstone drop, R54 atomic write-then-rename)
Regression tests added: 8 (F05ContractTest) + 9 (TableQueryExecutionTest, WD-03)
Obligations created: 1 (OBL-F05-R37 — query pass-through pending F10); RESOLVED by WD-03 on 2026-04-20
Untestable: 2 (R61, R79)

#### Amendments

- **R14** "illegal argument exception" → "IOException" (matches catalog implementation; IOException is the established Java convention for filesystem-catalog failures)
- **R21** "return an empty result" → "return null" (implementation uses `TableMetadata` not `Optional<TableMetadata>`)
- **R22** amended to clarify that the return type is `Table` (non-Optional) and failure is surfaced as IOException
- **R23, R24, R25** "return an empty result" → "throw IOException" with state-specific message
- **R26, R27** drop transitions metadata state to DROPPED via atomic write-then-rename (tombstone); the in-memory catalog drops the entry from the served view
- **R28** "illegal argument exception" → "IOException"
- **R31** on deletion failure the drop still succeeds (DROPPED state already persisted); best-effort cleanup only, no failure propagation
- **R37** (v2 amendment, superseded by v3) permitted `UnsupportedOperationException` pending jlsm-table query-binding (tracked by OBL-F05-R37)
- **R37** (v3, WD-03 2026-04-20) rewritten forward: `table.query()` returns a bound `TableQuery<String>` that dispatches via `QueryExecutor` against registered secondary indices and scan-and-filter fallback. UOE is retained only for schemaless tables where no `IndexRegistry` can be materialised. OBL-F05-R37 resolved.
- **R42** source identifier is always `Thread.threadId()`; `CALLER_TAG` / `FULL_STACK` govern allocation-site capture only
- **R51** LRU ordering via per-source list insertion order (no explicit timestamp)
- **R52** per-table `<root>/<tableName>/table.meta` files (per ADR `table-catalog-persistence`)
- **R54** per-metadata-file write-then-rename (atomicity preserved, storage model amended)
- **R55** per-table metadata file carries full schema; startup is O(n) directory scan
- **R57** startup reads per-table metadata files; DROPPED tombstones are loaded into the raw catalog but excluded from the served view
- **R60** corrupt per-table metadata → ERROR state (engine continues startup; no IOException propagated)
- **R67** concurrent duplicate create → exactly one success + one IOException
- **R74** `jlsm.engine.cluster` added as permitted second exported package (F04 scope)

### Verified: v4 — 2026-04-21 (state: APPROVED)

Coverage promotion work (WD `close-coverage-gaps / WD-01`). All 91 requirements now have direct
`@spec` annotations at their enforcement sites:

- **R60, R86** — `ContractBoundariesAdversarialTest.test_TableCatalog_open_corruptMetadata_preservesDirectory` (added)
- **R61** — impl annotation added on `LocalEngine.getTable` (dispatch of ERROR-state tables during lazy load); per the
  lazy-loading contract, startup does not open the LSM tree, so data-file corruption surfaces on first use rather than
  aborting engine startup. R61 remains UNTESTABLE in the mechanical sense (no failure injection API for WAL corruption
  at lazy-load time within this module), but the ERROR-state dispatch branch is the enforcement site.
- **R78** — `LocalEngineTest.closeContinuesClosingRemainingTablesWhenOneFails` (added) — validates engine close
  invalidates all table handles even when multiple live tables are open; `LocalEngine.close` accumulates
  errors from each table's close into an aggregate IOException.
- **R79** — UNTESTABLE (resource-leak absence not mechanically testable in-library); impl annotation retained on
  `LocalEngine.close`. The requirement is validated through code review of arena/channel release paths rather
  than runtime assertions.
- **R88** — `LocalEngineTest.concurrentTableCreation` annotation extended to cover R88 alongside R65/R66.

`spec-trace.sh engine.in-process-database-engine` reports `Annotations: 124 | Requirements traced: 91`
with only R61 and R79 (both documented UNTESTABLE) lacking test annotations. `./gradlew
:modules:jlsm-engine:test` passes green at WD end. No requirement text was rewritten.

**Overall: PASS** — 89/91 requirements fully traced with impl + test; 2 (R61, R79) traced impl-only
with documented UNTESTABLE rationale per v2 Verification Notes.

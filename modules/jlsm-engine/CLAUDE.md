# jlsm-engine

In-process database engine providing multi-table management with self-organized
storage. Supports table creation, metadata introspection, CRUD operations, and
querying via pass-through to the `jlsm-table` fluent API. Thread-safe for
concurrent callers.

## Dependencies

- `jlsm.table` (transitive) — document model, schema, query API
- `jlsm.core` — LSM tree, WAL, MemTable, SSTable, bloom filters

## Exported Package

- `jlsm.engine` — public API: `Engine`, `Table`, `TableMetadata`, `EngineMetrics`,
  `AllocationTracking`, `HandleEvictedException`

## Internal Package

Not exported in `module-info.java` and must not be made public:

- `jlsm.engine.internal` — `LocalEngine`, `LocalTable`, `HandleTracker`,
  `HandleRegistration`, `TableCatalog`

## Key Design Decisions

- **Engine API Surface:** Interface-based handle pattern with tracked lifecycle
  and lease eviction ([ADR](.decisions/engine-api-surface-design/adr.md))
- **Table Catalog:** Per-table metadata directories with lazy recovery
  ([ADR](.decisions/table-catalog-persistence/adr.md))

## Known Gaps

- `Table.query()` throws `UnsupportedOperationException` — `TableQuery` has a
  private constructor and cannot be instantiated from outside `jlsm.table`. Use
  `Table.scan()` for range queries until this is resolved.

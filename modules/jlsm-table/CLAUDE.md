# jlsm-table

Document-oriented table API built on top of `jlsm-core`. Provides schema-driven
documents, CRUD operations, secondary indices, and a fluent query API.

## Dependencies

- `jlsm.core` (transitive) — LSM tree, bloom filters, WAL, SSTable
- `jdk.incubator.vector` — SIMD acceleration in `DocumentSerializer` (byte-swap for array fields)

## Exported Package

- `jlsm.table` — public API: `JlsmTable`, `JlsmDocument`, `JlsmSchema`, `Predicate`, `TableQuery`, etc.

## Internal Package

Not exported in `module-info.java` and must not be made public:

- `jlsm.table.internal` — `StringKeyedTable`, `LongKeyedTable`, `DocumentSerializer`,
  `FieldValueCodec`, `JsonValueAdapter`, `IndexRegistry`, `FieldIndex`,
  `VectorFieldIndex`, `QueryExecutor`

## Key Constraint

Do not add dependencies on `jlsm-indexing` or `jlsm-vector` from production code —
secondary index delegation to those modules happens through `jlsm-core` interfaces
(`FullTextIndex`, `VectorIndex`). The dependency arrow points one way only.

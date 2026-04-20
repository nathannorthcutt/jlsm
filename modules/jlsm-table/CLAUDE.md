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

## Secondary Index Wiring (WD-01)

- `StandardJlsmTable.stringKeyedBuilder()` accepts `addIndex(IndexDefinition)` and
  `fullTextFactory(FullTextIndex.Factory)`. FULL_TEXT definitions without a factory
  are rejected with `IllegalArgumentException` at `build()` time.
- `StringKeyedTable` routes `create/update/delete` through an optional `IndexRegistry`
  so secondary indices stay synchronised with the primary tree.
- `FullTextFieldIndex` adapts `SecondaryIndex` mutation callbacks to the batch
  `FullTextIndex.index/remove` API and translates `FullTextMatch` predicates to
  `Query.TermQuery` when looked up.

## Query Binding (WD-03)

- `StandardJlsmTable.stringKeyedBuilder()` now materialises an `IndexRegistry`
  whenever a schema is configured, even with zero index definitions — the registry's
  document store is the schema-aware mirror used for scan-and-filter fallback.
- `JlsmTable.StringKeyed.query()` is a default interface method returning an
  unbound `TableQuery<String>`; `StringKeyedTable.query()` overrides it to return a
  `TableQuery` bound via `TableQuery.bound(runner)` to a `QueryExecutor.forStringKeys(...)`.
- `QueryRunner<K>` (public interface in `jlsm.table`) is the binding contract
  between `TableQuery.execute()` and the internal `QueryExecutor`; it keeps the
  `jlsm.table.internal` types off the public API surface.
- Schemaless tables (no schema configured on the builder) return the interface's
  default unbound query — `execute()` throws `UnsupportedOperationException`.
- `OBL-F05-R37` resolved; F05 spec moved from v2 → v3 with R37 rewritten forward.

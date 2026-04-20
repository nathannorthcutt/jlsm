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

## Secondary Index Wiring

Secondary-index implementations are injected via factory SPIs defined in `jlsm-core`:

- **WD-01 — Full-Text:** `StandardJlsmTable.stringKeyedBuilder()` accepts
  `addIndex(IndexDefinition)` and `fullTextFactory(FullTextIndex.Factory)`. `FULL_TEXT`
  definitions without a factory fail at `build()` with `IllegalArgumentException`.
  `jlsm-indexing` supplies `LsmFullTextIndexFactory`. `FullTextFieldIndex` adapts
  `SecondaryIndex` mutation callbacks to the batch `FullTextIndex.index/remove` API
  and translates `FullTextMatch` predicates to `Query.TermQuery` on lookup.
- **WD-02 — Vector:** `StandardJlsmTable.stringKeyedBuilder()` accepts
  `vectorFactory(VectorIndex.Factory)`. `VECTOR` definitions without a factory fail at
  `build()` with `IllegalArgumentException` instead of silently dropping writes.
  `jlsm-vector` supplies `LsmVectorIndexFactory` (IvfFlat/Hnsw). `VectorFieldIndex`
  adapts per-field `SecondaryIndex` mutation callbacks to `VectorIndex.index/remove`
  and translates `VectorNearest` predicates to `VectorIndex.search(query, topK)`.
- **Shared wiring:** `StringKeyedTable` routes `create/update/delete` through an
  optional `IndexRegistry` so every registered secondary index stays synchronised
  with the primary tree. `IndexRegistry` has a four-arg constructor accepting both
  factories; a three-arg overload passing `null` for the vector factory is retained
  for call-sites that do not register VECTOR indices.

## Query Binding (WD-03)

- `StandardJlsmTable.stringKeyedBuilder()` materialises an `IndexRegistry` whenever
  a schema is configured, even with zero index definitions — the registry's document
  store is the schema-aware mirror used for scan-and-filter fallback.
- `JlsmTable.StringKeyed.query()` is a default interface method returning an unbound
  `TableQuery<String>`; `StringKeyedTable.query()` overrides it to return a
  `TableQuery` bound via `TableQuery.bound(runner)` to `QueryExecutor.forStringKeys(...)`.
- `QueryRunner<K>` (public interface in `jlsm.table`) is the binding contract between
  `TableQuery.execute()` and the internal `QueryExecutor`; it keeps the
  `jlsm.table.internal` types off the public API surface.
- Schemaless tables (no schema configured on the builder) return the interface's
  default unbound query — `execute()` throws `UnsupportedOperationException`.
- `OBL-F05-R37` resolved; F05 spec moved from v2 → v3 with R37 rewritten forward.

---
title: "table-indices-and-queries"
type: feature-footprint
domains: ["lsm-index-patterns", "data-structures"]
constructs: ["IndexDefinition", "IndexType", "Predicate", "TableQuery", "FieldIndex", "FieldValueCodec", "FullTextFieldIndex", "VectorFieldIndex", "IndexRegistry", "QueryExecutor", "SecondaryIndex"]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/Index*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/Predicate.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/TableQuery.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/Field*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/Index*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/Query*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/Secondary*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/Vector*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FullText*.java"
research_status: stable
last_researched: "2026-03-25"
---

# table-indices-and-queries

## What it built
Secondary index support and a fluent query API for jlsm-table. Indices are defined
on the table builder, validated against the schema, and maintained synchronously on
writes. The query API uses a Predicate AST that the QueryExecutor plans against
available indices, falling back to scan-and-filter for unindexed predicates.

## Key constructs
- `IndexDefinition` — record defining field name + index type + optional vector config
- `IndexType` — enum: EQUALITY, RANGE, UNIQUE, FULL_TEXT, VECTOR
- `Predicate` — sealed interface AST: Eq, Ne, Gt, Gte, Lt, Lte, Between, FullTextMatch, VectorNearest, And, Or
- `TableQuery` — fluent builder producing Predicate trees
- `FieldValueCodec` — sort-preserving binary encoding for index keys
- `SecondaryIndex` — sealed interface: FieldIndex, FullTextFieldIndex, VectorFieldIndex
- `FieldIndex` — equality/range/unique index backed by in-memory TreeMap
- `IndexRegistry` — manages all indices, routes writes, provides lookup
- `QueryExecutor` — plans and executes queries using IndexRegistry

## Adversarial findings
- Runtime type inference ambiguity: Short→INT16 vs FLOAT16 → [KB entry](runtime-type-inference-ambiguity.md)
- Multi-index atomicity: sequential unique checks leave orphan entries → [KB entry](multi-index-atomicity.md)
- Mutable array in record: float[] not cloned in Predicate.VectorNearest → [KB entry](../../data-structures/mutable-array-in-record.md)
- Between mixed-type mismatch: Integer/Long mix crashed codec → [KB entry](between-mixed-type-mismatch.md)
- Between inverted range: low > high crashed TreeMap.subMap → [KB entry](between-inverted-range.md)
- Hardcoded key decoder: QueryExecutor always decoded keys as String → [KB entry](hardcoded-key-decoder.md)
- Scan-filter unchecked compareTo: cross-type compareTo crashed with ClassCastException → [KB entry](scan-filter-unchecked-compareto.md)

## Cross-references
- Related features: sql-query-support (depends on Predicate AST), float16-vector-support (FLOAT16 encoding)
- KB: composite-key-reindex-orphan (same domain, different bug class)

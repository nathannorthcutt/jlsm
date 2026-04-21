---
{
  "id": "query.index-types",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "query"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F10"
  ]
}
---

# query.index-types — Index Types

## Requirements

### IndexType enum

R1. `IndexType` must be a public enum in `jlsm.table` with exactly five constants: `EQUALITY`, `RANGE`, `UNIQUE`, `FULL_TEXT`, `VECTOR`.

R2. `EQUALITY` must support `Eq` and `Ne` predicate lookups on any primitive or bounded-string field type.

R3. `RANGE` must support `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, and `Between` predicate lookups on naturally ordered field types.

R4. `UNIQUE` must support the same predicate lookups as `RANGE` and additionally enforce a uniqueness constraint at write time.

R5. `FULL_TEXT` must support only `FullTextMatch` predicate lookups and must require a `STRING` or `BoundedString` field type. The `FullTextFieldIndex` adapter must delegate to a `FullTextIndex<MemorySegment>` backing supplied by a `FullTextIndex.Factory` so that write-path mutations propagate to a real index implementation without a direct static dependency from `jlsm-table` on `jlsm-indexing`.

R6. `VECTOR` must support only `VectorNearest` predicate lookups and must require a `VectorType` field type.

### IndexDefinition record

R7. `IndexDefinition` must be a public record in `jlsm.table` with components `fieldName` (String), `indexType` (IndexType), and `similarityFunction` (SimilarityFunction, nullable).

R8. `IndexDefinition` must reject a null `fieldName` with a `NullPointerException`.

R9. `IndexDefinition` must reject a blank `fieldName` with an `IllegalArgumentException`.

R10. `IndexDefinition` must reject a null `indexType` with a `NullPointerException`.

R11. `IndexDefinition` must require a non-null `similarityFunction` when `indexType` is `VECTOR`, rejecting null with a `NullPointerException`.

R12. `IndexDefinition` must reject a non-null `similarityFunction` when `indexType` is not `VECTOR` with an `IllegalArgumentException`.

R13. `IndexDefinition` must provide a two-argument convenience constructor `(fieldName, indexType)` that passes null for `similarityFunction`.

### Predicate sealed interface

R14. `Predicate` must be a public sealed interface in `jlsm.table` permitting exactly eleven implementations: `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, `Between`, `FullTextMatch`, `VectorNearest`, `And`, `Or`.

R15. Each leaf predicate record (`Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, `Between`, `FullTextMatch`) must reject a null `field` with a `NullPointerException`.

R16. Each leaf predicate record (`Eq`, `Ne`) must reject a null `value` with a `NullPointerException`.

R17. Each range predicate record (`Gt`, `Gte`, `Lt`, `Lte`) must accept a `Comparable<?>` value and reject null with a `NullPointerException`.

R18. `Between` must reject a null `low` or `high` with a `NullPointerException`.

R19. `FullTextMatch` must reject a null `query` string with a `NullPointerException`.

R20. `VectorNearest` must reject a null `field` with a `NullPointerException`.

R21. `VectorNearest` must reject a null `queryVector` with a `NullPointerException`.

R22. `VectorNearest` must reject a `topK` value of zero or negative with an `IllegalArgumentException`.

R23. `VectorNearest` must defensively copy the `queryVector` array at construction and return a defensive copy from the accessor.

R24. `And` must reject a null `children` list with a `NullPointerException`.

R25. `And` must reject a `children` list with fewer than two elements with an `IllegalArgumentException`.

R26. `And` must store a defensively copied immutable list of its children.

R27. `Or` must reject a null `children` list with a `NullPointerException`.

R28. `Or` must reject a `children` list with fewer than two elements with an `IllegalArgumentException`.

R29. `Or` must store a defensively copied immutable list of its children.

### Index-schema type compatibility

R30. `FieldValueCodec.encode` must reject a value whose Java type does not match the expected type for the field type (e.g., passing a String for an INT32 field) with an `IllegalArgumentException`.

R31. `FieldValueCodec` must use the schema-declared field type for encoding rather than inferring from the runtime value type, when the schema field type is available.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

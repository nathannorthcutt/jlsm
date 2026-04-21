---
{
  "id": "query.field-index",
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

# query.field-index — Field Index

## Requirements

### SecondaryIndex sealed interface

R1. `SecondaryIndex` must be a sealed interface in `jlsm.table.internal` extending `Closeable`, permitting exactly three implementations: `FieldIndex`, `FullTextFieldIndex`, `VectorFieldIndex`.

R2. `SecondaryIndex.definition()` must return the `IndexDefinition` this index was created from.

R3. `SecondaryIndex.onInsert(primaryKey, fieldValue)` must index the field value associated with the given primary key.

R4. `SecondaryIndex.onInsert` must treat a null `fieldValue` as a no-op (null fields are not indexed).

R5. `SecondaryIndex.onUpdate(primaryKey, oldFieldValue, newFieldValue)` must remove the old field value from the index and insert the new field value.

R6. `SecondaryIndex.onUpdate` must handle null `oldFieldValue` (insert-only) and null `newFieldValue` (delete-only) independently.

R7. `SecondaryIndex.onDelete(primaryKey, fieldValue)` must remove the field value entry for the given primary key from the index.

R8. `SecondaryIndex.onDelete` must treat a null `fieldValue` as a no-op.

R9. `SecondaryIndex.lookup(predicate)` must return an `Iterator<MemorySegment>` of matching primary keys for the given predicate.

R10. `SecondaryIndex.supports(predicate)` must return true only when the predicate's field matches the index's field and the predicate type is compatible with the index type.

### FieldIndex — equality, range, and unique lookups

R11. `FieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`.

R12. `FieldIndex` must reject at construction an `IndexDefinition` whose type is not `EQUALITY`, `RANGE`, or `UNIQUE` with an `IllegalArgumentException`.

R13. `FieldIndex` must maintain a sorted map keyed by sort-preserving encoded field values (via `FieldValueCodec`) with lists of primary key segments as values.

R14. For `EQUALITY` index type, `FieldIndex.supports` must return true only for `Eq` and `Ne` predicates on its field.

R15. For `RANGE` and `UNIQUE` index types, `FieldIndex.supports` must return true for `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, and `Between` predicates on its field.

R16. `FieldIndex.lookup` for `Eq` must return all primary keys whose encoded field value equals the encoded query value.

R17. `FieldIndex.lookup` for `Ne` must return all primary keys whose encoded field value does not equal the encoded query value.

R18. `FieldIndex.lookup` for `Gt` must return all primary keys whose encoded field value is strictly greater than the encoded query value.

R19. `FieldIndex.lookup` for `Between` must return all primary keys whose encoded field value falls within the inclusive range `[low, high]`.

R20. `FieldIndex.lookup` for `Between` must return an empty iterator when `low` compares greater than `high` in encoded form.

R21. `FieldIndex` must use unsigned byte-wise comparison of encoded keys (`ByteArrayKey.compareTo`) so that the sort-preserving encoding of `FieldValueCodec` produces correct ordering.

### Unique index constraint enforcement

R22. When `IndexType` is `UNIQUE`, `FieldIndex.onInsert` must throw `DuplicateKeyException` if the encoded field value already exists in the index with a non-empty primary key list.

R23. When `IndexType` is `UNIQUE`, `FieldIndex.onUpdate` must throw `DuplicateKeyException` if the new field value already exists for a different primary key, but must not reject an update that retains the same field value.

R24. `IndexRegistry.onInsert` must validate all unique constraints across all unique indices before mutating any index, preventing orphan entries if the Nth unique check fails.

R25. `IndexRegistry.onUpdate` must validate all unique constraints for changed values across all unique indices before mutating any index, preventing partial updates.

R26. Unique constraint checks must skip null field values (null values are not subject to uniqueness enforcement).

### FieldIndex key comparison

R27. `ByteArrayKey.compareTo` must compare byte arrays using unsigned byte values, so that sort-preserving encoded keys produce correct ordering across all primitive types.

R28. `ByteArrayKey` must implement `equals` and `hashCode` based on array content, not reference identity.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

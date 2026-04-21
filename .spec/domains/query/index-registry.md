---
{
  "id": "query.index-registry",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
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
# query.index-registry — Index Registry

## Requirements

### IndexRegistry — index lifecycle management

R1. `IndexRegistry` must be a final class in `jlsm.table.internal` implementing `Closeable`.

R2. `IndexRegistry` must accept a `JlsmSchema` and a list of `IndexDefinition` values at construction, validating each definition against the schema.

R3. `IndexRegistry` must reject an `IndexDefinition` whose `fieldName` does not exist in the schema with an `IllegalArgumentException`.

R4. `IndexRegistry` must reject an `EQUALITY`, `RANGE`, or `UNIQUE` index on a non-primitive, non-BoundedString field type with an `IllegalArgumentException`.

R5. `IndexRegistry` must reject a `RANGE` or `UNIQUE` index on a `BOOLEAN` field with an `IllegalArgumentException`.

R6. `IndexRegistry` must reject a `FULL_TEXT` index on a field that is not `STRING` or `BoundedString` with an `IllegalArgumentException`.

R7. `IndexRegistry` must reject a `VECTOR` index on a field that is not `VectorType` with an `IllegalArgumentException`.

R8. `IndexRegistry.onInsert(primaryKey, document)` must route the insert to all registered indices, extracting the field value from the document for each index.

R9. `IndexRegistry.onUpdate(primaryKey, oldDocument, newDocument)` must route the update to all registered indices with the old and new field values.

R10. `IndexRegistry.onDelete(primaryKey, document)` must route the delete to all registered indices.

R11. `IndexRegistry.findIndex(predicate)` must return the first registered `SecondaryIndex` that supports the given predicate, or null if no index supports it.

R12. `IndexRegistry.close()` must close all registered indices, accumulating exceptions via the deferred pattern and throwing after all indices have been closed.

R13. `IndexRegistry` must maintain a document store mapping primary keys to their documents for scan-and-filter query execution.

R14. `IndexRegistry.resolveEntry(primaryKey)` must return the stored entry for the given primary key, or null if not found.

R15. `IndexRegistry.allEntries()` must return an iterator over a snapshot of all stored entries, safe against concurrent modification during iteration.

### Index close and resource cleanup

R16. `FieldIndex.close()` must set a closed flag and clear its internal data structures.

R17. After `FieldIndex.close()`, all mutation and lookup operations must throw `IllegalStateException` indicating the index is closed.

R18. `IndexRegistry.close()` must close all registered indices and must not leak resources even if one index close throws an exception.

### Audit-hardened requirements

R19. `IndexRegistry` read-only query methods (`findIndex`, `isEmpty`, `resolveEntry`, `allEntries`, `schema`) must acquire the read lock before checking the closed flag and accessing internal state.

R20. `IndexRegistry.onUpdate` and `IndexRegistry.onDelete` must place the `documentStore` mutation inside the try/catch rollback scope, matching the transactional consistency pattern used by `onInsert`.

R21. `IndexRegistry.close()` must accumulate exceptions from all resources (indices and arena) using the deferred exception pattern, never silently losing an exception when multiple resources fail.

R22. `IndexRegistry` must reject an `EQUALITY` index on a `BOOLEAN` field with an `IllegalArgumentException`, matching the rejection behavior of `RANGE` and `UNIQUE` index types on `BOOLEAN`.

R23. `IndexRegistry.extractFieldValue` must return a defensive copy of vector arrays (`float[]` and `short[]`), not a reference to the document's internal array.

---

## Verification Notes

### Verified: v1 — 2026-04-20

Promoted DRAFT → APPROVED after migration verification. Pre-migration source
(F10 v4) was DRAFT pending post-amendment re-verification. Migration preserved
all requirements mechanically; the split redistributed F10's 139 reqs across
8 query specs with global RN renumbering (see `.spec/_archive/migration-2026-04-20/`).

Verification evidence:
- Annotation coverage: 100% of reqs have `@spec` annotations in `modules/`
  (implementation) and `tests/` (regression). Verified via `spec-trace.sh`.
- Build + test green: `./gradlew test` BUILD SUCCESSFUL post-migration.
- Round-trip validation passed: every source `F10.R*` maps to exactly one
  `query.index-registry.R*` destination, preserving req content.

No requirement text changed in this promotion; only frontmatter state.

---
{
  "id": "query.query-executor",
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

# query.query-executor — Query Executor

## Requirements

### Null field value handling

R1. When a document is inserted with a null value for an indexed field, no entry must be added to that field's index.

R2. When a document is updated and the new value for an indexed field is null, the old entry must be removed from the index and no new entry must be added.

R3. When a document is deleted and the field value is null, the delete must be a no-op for that field's index.

### Update atomicity

R4. `FieldIndex.onUpdate` must remove the old field value entry before inserting the new field value entry.

R5. If the old and new field values are identical, `FieldIndex.onUpdate` must still perform the remove-then-insert cycle to ensure index consistency.

### QueryExecutor — query planning and execution

R6. `QueryExecutor<K>` must be a final class in `jlsm.table.internal` parameterized by the primary key type `K`.

R7. `QueryExecutor.execute(predicate)` must reject a null predicate with a `NullPointerException`.

R8. For a leaf predicate where an index exists (determined by `IndexRegistry.findIndex`), `QueryExecutor` must use the index's `lookup` method to retrieve matching primary keys.

R9. For a leaf predicate where no index exists, `QueryExecutor` must fall back to scan-and-filter: iterate all entries in the `IndexRegistry` document store and evaluate the predicate against each entry's field values.

R10. For `Predicate.And`, `QueryExecutor` must compute the intersection of results from all child predicates.

R11. For `Predicate.Or`, `QueryExecutor` must compute the union of results from all child predicates.

R12. For `FullTextMatch` and `VectorNearest` predicates without a corresponding index, the scan-and-filter fallback must throw `UnsupportedOperationException` with a message identifying the field and the required index type. These predicates require index backing and cannot be evaluated row-by-row.

R13. `QueryExecutor` must deduplicate results by primary key so that no entry appears more than once in the output iterator.

### Scan-and-filter predicate evaluation

R14. Scan-and-filter for `Eq` must match entries where the field value is non-null and equals the query value.

R15. Scan-and-filter for `Ne` must match entries where the field value is non-null and does not equal the query value.

R16. Scan-and-filter for `Gt`, `Gte`, `Lt`, `Lte` must match entries where the field value is a `Comparable` and compares appropriately against the query value. When field value and query value share a class, use natural `compareTo`; when both are numeric (`java.lang.Number` subtypes) but differ in class, widen to `double` if either is floating-point, otherwise to `long`. Non-numeric class mismatches must produce no match.

R17. Scan-and-filter for `Between` must match entries where the field value is a `Comparable` and falls within the inclusive range `[low, high]` using the same coercion rules as R121. The `Between` record itself enforces that `low` and `high` share a class at construction (R18 guardrail).

R18. Scan-and-filter must treat null field values as non-matching for all comparison predicates.

### JPMS module boundaries

R19. `SecondaryIndex`, `FieldIndex`, `FullTextFieldIndex`, `VectorFieldIndex`, `FieldValueCodec`, `IndexRegistry`, and `QueryExecutor` must reside in `jlsm.table.internal`, which must not be exported in the `jlsm-table` module descriptor.

R20. `IndexType`, `IndexDefinition`, `Predicate`, `TableQuery`, `TableEntry`, and `DuplicateKeyException` must reside in `jlsm.table`, which must be exported in the `jlsm-table` module descriptor.

### Thread safety

R21. `IndexRegistry` must expose closed-state transitions atomically to all threads without external synchronization. Implementations must use either a `volatile` flag or a stronger primitive such as `AtomicBoolean` with `compareAndSet` to guarantee a single close winner.

R22. `FieldIndex` must use a volatile closed flag so that the closed state is visible to all threads.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

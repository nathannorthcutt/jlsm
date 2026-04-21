---
{
  "id": "query.table-query",
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

# query.table-query — Table Query

## Requirements

### TableQuery fluent builder

R1. `TableQuery<K>` must be a public final class in `jlsm.table` parameterized by the primary key type `K`.

R2. `TableQuery.where(fieldName)` must reject a null `fieldName` with a `NullPointerException`.

R3. `TableQuery.where(fieldName)` must return a `FieldClause<K>` that provides comparison operator methods.

R4. `FieldClause<K>` must provide methods `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`, `fullTextMatch`, and `vectorNearest`, each returning the owning `TableQuery<K>` for chaining.

R5. `TableQuery.and(fieldName)` must combine the next predicate with the existing root using `Predicate.And`.

R6. `TableQuery.or(fieldName)` must combine the next predicate with the existing root using `Predicate.Or`.

R7. `TableQuery.predicate()` must return the current root predicate, or null if no predicates have been added.

R8. `TableQuery.execute()` must return an `Iterator<TableEntry<K>>` of matching entries.

R9. Calling `execute()` on an unbound `TableQuery` (not obtained from a table) must throw `UnsupportedOperationException`.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

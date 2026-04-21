---
{
  "id": "query.vector-index",
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
# query.vector-index — Vector Index

## Requirements

### VectorFieldIndex

R1. `VectorFieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`.

R2. `VectorFieldIndex.supports` must return true only for `VectorNearest` predicates whose field matches the index's field.

R3. `VectorFieldIndex.onInsert` must extract the vector from the field value and insert it into the backing vector index keyed by primary key. The backing implementation is obtained via `VectorIndex.Factory.create(tableName, fieldName, dimensions, precision, similarityFunction)`; when a table registers a `VECTOR` index without a configured factory, `build()` must fail with `IllegalArgumentException` rather than silently accepting unindexed writes.

R4. `VectorFieldIndex.onUpdate` must remove the old vector and insert the new vector for the given primary key. When the old vector is absent (field previously unset), the removal step is a no-op and the insert still proceeds.

R5. `VectorFieldIndex.onDelete` must remove the vector associated with the given primary key from the backing index.

R6. `VectorFieldIndex.lookup` for `VectorNearest` must return the `topK` closest primary keys to the query vector according to the configured similarity function, using the backing implementation's `search(queryVector, topK)` call.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

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
  `query.vector-index.R*` destination, preserving req content.

No requirement text changed in this promotion; only frontmatter state.

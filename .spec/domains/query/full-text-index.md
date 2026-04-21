---
{
  "id": "query.full-text-index",
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
# query.full-text-index — Full Text Index

## Requirements

### FullTextFieldIndex

R1. `FullTextFieldIndex` must implement `SecondaryIndex` and must be a final class in `jlsm.table.internal`. It must be constructed with a non-null `IndexDefinition` of type `FULL_TEXT` and a non-null backing `jlsm.core.indexing.FullTextIndex<MemorySegment>` supplied by the caller (typically via `FullTextIndex.Factory`). Any other index type must be rejected with `IllegalArgumentException`.

R2. `FullTextFieldIndex.supports` must return true only for `FullTextMatch` predicates whose `field()` equals the index's field, and must return false for all other predicates and after `close()`.

R3. `FullTextFieldIndex.onInsert` must route `(fieldName -> String.valueOf(fieldValue))` to `FullTextIndex.index(primaryKey, fields)` on the backing index so that the backing implementation's tokenisation pipeline indexes each term for the primary key. Null `fieldValue` is a no-op per R56.

R4. `FullTextFieldIndex.onUpdate` must invoke `FullTextIndex.remove` with the old field value (when non-null) and then `FullTextIndex.index` with the new field value (when non-null), so that old terms are deindexed and new terms indexed for the given primary key.

R5. `FullTextFieldIndex.onDelete` must route `(fieldName -> String.valueOf(fieldValue))` to `FullTextIndex.remove(primaryKey, fields)` on the backing index so that all terms for the primary key are removed. Null `fieldValue` is a no-op per R60.

R6. `FullTextFieldIndex.lookup` for `FullTextMatch` must translate the predicate to `jlsm.core.indexing.Query.TermQuery(field, query)` and return the iterator produced by `FullTextIndex.search`; it must reject any other predicate shape with `UnsupportedOperationException`. `FullTextFieldIndex.close()` must be idempotent and must close the backing index exactly once; subsequent calls are no-ops.

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
  `query.full-text-index.R*` destination, preserving req content.

No requirement text changed in this promotion; only frontmatter state.

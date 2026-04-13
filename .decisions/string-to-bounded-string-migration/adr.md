---
problem: "string-to-bounded-string-migration"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmSchema.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
---

# ADR — Schema Migration Policy (Compaction-Time + On-Demand Scan)

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Schema Type Systems | Migration strategies, compaction-time pattern, version-tagged schema | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |

---

## Files Constrained by This Decision

- `DocumentSerializer.java` — schema version already in header (bytes 0-1); migration check during compaction rewrite
- `JlsmSchema.java` — version field drives migration policy
- `JlsmDocument.java` — validation against current schema on new writes

## Problem
Define the migration policy when a schema evolves to tighten field constraints
(STRING→BoundedString, ArrayType→BoundedArray, future constraint changes).
The serialization format already embeds schema version in the document header.
Documents written under old schemas coexist with new-schema documents across
SSTable levels.

## Constraints That Drove This Decision
- **Zero downtime (Operational, weight 3)**: Migration cannot require stopping
  reads or writes. Must be progressive and observable.
- **Explicit non-compliance handling (Accuracy, weight 3)**: Documents that
  violate new constraints must be quarantined, not silently passed or truncated.
- **Cold data completeness (Operational, weight 3)**: Bottom-level SSTables in
  cold key ranges may never be compacted — pure compaction-time migration
  cannot guarantee completion.

## Decision
**Chosen approach: Compaction-time migration + optional on-demand scan**

Two complementary mechanisms:

1. **Compaction-time opportunistic migration**: During compaction, check each
   document's schema version (header bytes 0-1). Old version + compliant →
   bump version tag and write normally. Old version + non-compliant → write to
   quarantine output. Zero additional I/O — piggybacks on compaction's existing
   read-merge-write cycle.

2. **On-demand migration scan**: Caller-invokable API
   (`table.migrateToSchema(version)`) that reads only SSTables whose
   min-schema-version metadata is below the target. Validates, migrates, and
   quarantines. Caller controls migration SLA.

Supporting infrastructure:
- **Per-SSTable min-schema-version**: 2-byte field in SSTable metadata (footer
  or manifest), written at flush/compaction time. Enables targeted on-demand
  scan — only touch SSTables that contain old-version documents.
- **Migration progress**: Query min-schema-version across all SSTables.
  "Fully migrated" = all SSTables at current version.
- **Quarantine policy**: Caller-provided callback or separate SSTable output.
  jlsm defines the hook; the application decides what to do with non-compliant
  documents.

## Rationale

### Why compaction-time + on-demand scan (composite)
- **Zero-cost for hot data**: Compaction rewrites every document it touches —
  migration is free for actively-written key ranges (typically 80%+ of data).
- **Guaranteed completion for cold data**: On-demand scan closes the structural
  gap — bottom-level SSTables that compaction never reaches can be explicitly
  migrated when the caller needs it.
- **Observable**: Per-SSTable min-schema-version makes migration state queryable
  without scanning document payloads.

### Why not pure compaction-time
- Cold bottom-level SSTables in inactive key ranges are never compacted in
  leveled/SPOOKY compaction strategies. Migration never completes for those
  ranges. The read path must handle old schema versions indefinitely.

### Why not read-time lazy migration
- Non-compliant documents are flagged but not quarantined — warnings are easy
  to ignore. Adds complexity to the hot read path for a migration concern.

### Why not immediate background scan
- Scans the full dataset even when compaction handles 80%+ of migration for
  free. Wasteful I/O when most data is already compliant.

## Implementation Guidance

### Schema version lifecycle
```
Deploy schema V+1 → new writes validated eagerly against V+1
  ↓
Compaction runs → old-version documents migrated or quarantined
  ↓
On-demand scan (optional) → remaining cold SSTables migrated
  ↓
All SSTables at min-schema-version >= V+1 → migration complete
```

### Compaction hook (jlsm-table layer)
```
// Registered by the table on its compaction pipeline — not in jlsm-core
CompactionEntryTransformer migrator = (key, value, metadata) -> {
    int docVersion = readSchemaVersion(value);  // bytes 0-1
    if (docVersion < currentSchema.version()) {
        ValidationResult result = validate(value, currentSchema);
        if (result.compliant()) {
            return bumpVersion(value, currentSchema.version());
        } else {
            quarantineCallback.accept(key, value, result.violations());
            return SKIP;  // exclude from normal output
        }
    }
    return value;  // already current version
};
```

### Per-SSTable metadata
```
// Written at flush/compaction — track minimum schema version in file
record SSTableMetadata(..., short minSchemaVersion, ...)
```

### Migration ordering for STRING→BoundedString(N)
1. Deploy schema V+1 with BoundedString(N)
2. New writes validated eagerly against the bound
3. Compaction migrates compliant old documents, quarantines oversized ones
4. If needed: `table.migrateToSchema(V+1)` to force-scan cold SSTables
5. Query `table.migrationStatus()` → all SSTables at V+1 = complete

## What This Decision Does NOT Solve
- **Quarantine resolution policy** — what to DO with non-compliant documents
  (truncate, delete, notify, manual review). Application-level concern.
- **Cross-table schema migration coordination** — migrating multiple tables
  atomically as a single schema version bump.

## Conditions for Revision
This ADR should be re-evaluated if:
- The compaction hook proves too invasive for jlsm-core's merge pipeline
  (may need a formal plugin API instead of a callback)
- Migration SLA requirements demand immediate completion (background scan
  with priority scheduling may be needed)
- Schema changes become more complex than constraint tightening (e.g., field
  renames, type conversions, computed fields)

---
*Confirmed by: user deliberation | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*

---
{
  "id": "schema.schema-field-access",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "schema"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F13"
  ]
}
---

# schema.schema-field-access — Schema Field Access

## Requirements

### Field lookup

R1. `fieldIndex(String name)` must return the zero-based index of the field with the given name, using case-sensitive HashMap lookup. `[EXPLICIT]`

R2. `fieldIndex(String name)` must return `-1` when no field with the given name exists. `[EXPLICIT]`

R3. `fieldIndex(String name)` must reject a null argument with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R4. The `fieldIndexMap` must be an unmodifiable `Map` built at construction time via `Map.copyOf`. `[EXPLICIT]`

### Accessors

R5. `name()` must return the schema name provided at construction. `[EXPLICIT]`

R6. `version()` must return the schema version provided at construction. `[EXPLICIT]`

R7. `maxDepth()` must return the maximum nesting depth, defaulting to 10 if not explicitly set on the builder. `[EXPLICIT]`

### Absent behaviors

R8. `JlsmSchema` does not implement `toString()`. The default `Object.toString()` is used. `[ABSENT]`

R9. `JlsmSchema` does not implement `Serializable` or any serialization interface. Persistence is handled externally. `[ABSENT]`

R10. There is no field-count upper bound at the schema level. A schema may contain an arbitrarily large number of fields. The `DocumentSerializer` wire format imposes a 65535-field limit at the serialization boundary (see F12 R48). `[ABSENT]`

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

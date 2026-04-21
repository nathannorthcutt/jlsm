---
{
  "id": "schema.schema-nesting",
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

# schema.schema-nesting — Schema Nesting

## Requirements

### Nesting (object fields)

R1. `Builder.objectField(String name, Consumer<Builder> nested)` must create a nested builder at `currentDepth + 1`. `[EXPLICIT]`

R2. `Builder.objectField` must reject a null `name` with a `NullPointerException`. `[EXPLICIT]`

R3. `Builder.objectField` must reject a null `nested` consumer with a `NullPointerException`. `[EXPLICIT]`

R4. When the nested builder's depth exceeds the configured `maxDepth`, `buildFields()` must throw an `IllegalArgumentException` whose message includes both the depth and the limit. `[EXPLICIT]`

R5. Nested object fields are represented as `FieldType.ObjectType` containing a `List<FieldDefinition>`. The nested fields list is defensively copied via `List.copyOf`. `[EXPLICIT]`

### Audit-hardened requirements

R6. `FieldType.ObjectType.toSchema()` must propagate the parent schema's encryption specs for each field, not silently drop them.

R7. `FieldType.ObjectType.toSchema()` must accept and propagate the parent schema's `maxDepth` configuration rather than hardcoding a default value.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

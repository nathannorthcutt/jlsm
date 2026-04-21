---
{
  "id": "schema.schema-field-definition",
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

# schema.schema-field-definition — Schema Field Definition

## Requirements

### FieldDefinition (companion type)

R1. `FieldDefinition` must be a `public record` in `jlsm.table` with components `(String name, FieldType type, EncryptionSpec encryption)`. `[EXPLICIT]`

R2. The compact constructor must reject null `name`, `type`, or `encryption` with a `NullPointerException`. `[EXPLICIT]`

R3. A two-argument convenience constructor `(String name, FieldType type)` must default `encryption` to `EncryptionSpec.NONE`. `[EXPLICIT]`

### FieldType (companion type)

R4. `FieldType` must be a sealed interface permitting exactly: `Primitive`, `ArrayType`, `ObjectType`, `VectorType`, `BoundedString`. `[EXPLICIT]`

R5. `Primitive` must be an enum implementing `FieldType` with constants: `STRING`, `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT16`, `FLOAT32`, `FLOAT64`, `BOOLEAN`, `TIMESTAMP`. `[EXPLICIT]`

R6. `VectorType` must validate that `elementType` is `FLOAT16` or `FLOAT32`, rejecting others with `IllegalArgumentException`. `[EXPLICIT]`

R7. `VectorType` must validate that `dimensions > 0`, rejecting non-positive values with `IllegalArgumentException`. `[EXPLICIT]`

R8. `BoundedString` must validate that `maxLength > 0`, rejecting non-positive values with `IllegalArgumentException`. `[EXPLICIT]`

R9. `ArrayType` must reject null `elementType` with `NullPointerException`. `[EXPLICIT]`

R10. `ObjectType` must reject null `fields` with `NullPointerException` and defensively copy via `List.copyOf`. `[EXPLICIT]`

R11. Static factory methods must be provided: `string()`, `string(int)`, `int32()`, `int64()`, `float32()`, `float64()`, `boolean_()`, `timestamp()`, `vector(Primitive, int)`, `arrayOf(FieldType)`, `objectOf(List<FieldDefinition>)`. `[EXPLICIT]`

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

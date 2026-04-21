---
{
  "id": "schema.document-field-access",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "schema"
  ],
  "requires": [
    "schema.schema-construction"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F14"
  ]
}
---
# schema.document-field-access — Document Field Access

## Requirements

### Typed getters

R1. All typed getters must reject a null field name with a `NullPointerException` (via `requireIndex` calling `Objects.requireNonNull`). `[EXPLICIT]`

R2. All typed getters must reject an unknown field name with an `IllegalArgumentException`. `[EXPLICIT]`

R3. All typed getters (except `isNull`) must throw `NullPointerException` when the field value is null. `[EXPLICIT]`

R4. All typed getters must throw `IllegalArgumentException` when the field's declared type does not match the getter's expected type. `[EXPLICIT]`

R5. `getString(field)` must accept fields of type `STRING` or `BoundedString`. `[EXPLICIT]`

R6. `getLong(field)` must accept fields of type `INT64` or `TIMESTAMP`. `[EXPLICIT]`

R7. `getFloat16Bits(field)` must return the raw `short` value for `FLOAT16` fields, representing IEEE 754 half-precision bits. `[EXPLICIT]`

R8. `getArray(field)` must return a defensive copy of the `Object[]` value via `clone()`. `[EXPLICIT]`

R9. `getObject(field)` must return the nested `JlsmDocument` directly without copying. `[IMPLICIT]`

### Schema accessor

R10. `schema()` must return the `JlsmSchema` passed at construction. The return value is the same reference, not a copy. `[EXPLICIT]`

### Null field query

R11. `isNull(String field)` must return `true` when the value at the field's index is null, `false` otherwise. `[EXPLICIT]`

R12. `isNull(String field)` must reject a null field name with `NullPointerException` and an unknown field name with `IllegalArgumentException`. `[EXPLICIT]`

### Pre-encrypted document support

R13. `isPreEncrypted()` must be package-private. It must return `true` for documents created via `preEncrypted()`, `false` for documents created via `of()` or the package-private constructors. `[EXPLICIT]`

### Vector getters

R14. `getFloat32Vector(String field)` must return a defensive copy (`clone()`) of the `float[]` value for `VectorType(FLOAT32)` fields. It must throw `IllegalArgumentException` if the field is not `VectorType(FLOAT32)`, and `NullPointerException` if the value is null. `[EXPLICIT]`

R15. `getFloat16Vector(String field)` must return a defensive copy (`clone()`) of the `short[]` value for `VectorType(FLOAT16)` fields. It must throw `IllegalArgumentException` if the field is not `VectorType(FLOAT16)`, and `NullPointerException` if the value is null. `[EXPLICIT]`

### Absent behaviors

R16. `JlsmDocument` does not implement `toString()`. The default `Object.toString()` is used. Use `toJson()` for human-readable output. `[ABSENT]`

R17. `JlsmDocument` does not implement `Serializable` or any serialization interface. Binary serialization is handled by `DocumentSerializer` in `jlsm.table.internal`. `[ABSENT]`

R18. `JlsmDocument` does not validate that nested `JlsmDocument` values for `ObjectType` fields conform to the `ObjectType`'s declared field list. Only the Java type (`JlsmDocument`) is checked. `[ABSENT]`

R19. There is no `set` or `with` method for updating individual field values. Documents are write-once from the public API perspective. `[ABSENT]`

### Audit-hardened requirements

R20. `JlsmDocument.getLong()` must throw a descriptive `NullPointerException` with the field name when the field value is null, rather than allowing an unboxing NPE with no context.

---

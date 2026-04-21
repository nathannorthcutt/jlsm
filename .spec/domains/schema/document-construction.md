---
{
  "id": "schema.document-construction",
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
# schema.document-construction — Document Construction

## Requirements

### Construction

R1. `JlsmDocument` must be a `public final class` in the `jlsm.table` package. It must not be subclassable. `[EXPLICIT]`

R2. `JlsmDocument` must have package-private constructors only. Direct construction is restricted to code within `jlsm.table`. Public construction must go through the `of()` or `preEncrypted()` static factory methods. `[EXPLICIT]`

R3. The two-argument package-private constructor `JlsmDocument(JlsmSchema, Object[])` must delegate to the three-argument constructor with `preEncrypted = false`. `[EXPLICIT]`

R4. The three-argument package-private constructor must validate via runtime checks: schema and values are rejected with `NullPointerException` (via `Objects.requireNonNull`), and a `values.length != schema.fields().size()` mismatch is rejected with `IllegalArgumentException`. Runtime checks are preferred over assertions here so the invariants hold in production even though the constructor is package-private. `[EXPLICIT]`

R5. The package-private constructors must not defensively copy the `values` array. Internal callers within the `jlsm.table` package are trusted to not mutate the array after construction. `[IMPLICIT]`

### Factory method: `of(JlsmSchema, Object...)`

R6. `JlsmDocument.of(JlsmSchema, Object...)` must reject a null schema with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R7. `JlsmDocument.of` must reject an odd-length `nameValuePairs` array with an `IllegalArgumentException`. `[EXPLICIT]`

R8. `JlsmDocument.of` must reject a non-String value at any even index in `nameValuePairs` with an `IllegalArgumentException` whose message includes the index. `[EXPLICIT]`

R9. `JlsmDocument.of` must reject a field name that does not exist in the schema with an `IllegalArgumentException` whose message includes the unknown field name. `[EXPLICIT]`

R10. `JlsmDocument.of` must validate non-null values against the declared field type using `validateType`. Null values are accepted without type validation for any field. `[EXPLICIT]`

R11. Fields not mentioned in `nameValuePairs` must default to `null` in the resulting document. `[EXPLICIT]`

R12. `JlsmDocument.of` must produce a document with `preEncrypted = false`. `[EXPLICIT]`

### Factory method: `preEncrypted(JlsmSchema, Object...)`

R13. `JlsmDocument.preEncrypted` must reject a null schema with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R14. `JlsmDocument.preEncrypted` must reject an odd-length `nameValuePairs` array with an `IllegalArgumentException`. `[EXPLICIT]`

R15. `JlsmDocument.preEncrypted` must reject a non-String name at any even index with an `IllegalArgumentException`. `[EXPLICIT]`

R16. `JlsmDocument.preEncrypted` must reject an unknown field name with an `IllegalArgumentException`. `[EXPLICIT]`

R17. For fields whose `FieldDefinition.encryption()` is `EncryptionSpec.None`, `preEncrypted` must validate the value's type normally (via `validateType`). `[EXPLICIT]`

R18. For fields whose `FieldDefinition.encryption()` is not `EncryptionSpec.None`, `preEncrypted` must enforce that non-null values are `byte[]` ciphertext. A non-`byte[]` value for an encrypted field must throw `IllegalArgumentException`. `[EXPLICIT]`

R19. `JlsmDocument.preEncrypted` must produce a document with `preEncrypted = true`. `[EXPLICIT]`

R20. `JlsmDocument.preEncrypted` must defensively copy `VectorType` field values (same as `of()`). `[EXPLICIT]`

### Type validation

R21. `validateType` must accept `String` for `FieldType.Primitive.STRING`. `[EXPLICIT]`

R22. `validateType` must accept `Byte` for `INT8`, `Short` for `INT16` and `FLOAT16`, `Integer` for `INT32`, `Long` for `INT64` and `TIMESTAMP`, `Float` for `FLOAT32`, `Double` for `FLOAT64`, `Boolean` for `BOOLEAN`. `[EXPLICIT]`

R23. `validateType` must accept `String` for `FieldType.BoundedString` and must validate that the UTF-8 byte length does not exceed `maxLength()`. Exceeding the limit must throw `IllegalArgumentException`. `[EXPLICIT]`

R24. `validateType` must accept `Object[]` for `FieldType.ArrayType` and must recursively validate each non-null element against the declared `elementType`. `[EXPLICIT]`

R25. `validateType` must accept `float[]` for `VectorType` with `FLOAT32` element type and `short[]` for `VectorType` with `FLOAT16` element type. `[EXPLICIT]`

R26. `validateType` must validate vector dimension: the array length must equal `VectorType.dimensions()`. A mismatch must throw `IllegalArgumentException`. `[EXPLICIT]`

R27. `validateType` must validate vector elements for finiteness: for `FLOAT32` vectors, each element must satisfy `Float.isFinite()`; for `FLOAT16` vectors, each element must not have the exponent field `0x7C00` set (NaN/Infinity check). Non-finite elements must throw `IllegalArgumentException` identifying the element index. `[EXPLICIT]`

R28. `validateType` must accept `JlsmDocument` for `FieldType.ObjectType`. `[EXPLICIT]`

R29. `validateType` must throw `IllegalArgumentException` when the value's Java type does not match the expected type for the field. The exception message must include the field name, expected type, and actual type. `[EXPLICIT]`

### Defensive copying

R30. `JlsmDocument.of` must defensively copy `float[]` and `short[]` values for `VectorType` fields via `clone()`. The caller's array must not be aliased by the document. `[EXPLICIT]`

R31. `JlsmDocument.of` must not defensively copy values for scalar and reference fields that are safe to alias: `String`, boxed primitives, and nested `JlsmDocument`. `Object[]` values for `ArrayType` fields are deep-copied per R68; `float[]`/`short[]` values for `VectorType` fields are cloned per R30. `[EXPLICIT]`

R32. `JlsmDocument.preEncrypted` must defensively copy `VectorType` field values via `clone()`, consistent with the `of()` factory. `[EXPLICIT]`

### Audit-hardened requirements

R33. `JlsmDocument.of` must reject duplicate field names in the `nameValuePairs` argument with an `IllegalArgumentException`, rather than silently accepting last-write-wins semantics.

R34. `JlsmDocument.validateType` must enforce a maximum recursion depth when validating nested `ArrayType` fields, throwing `IllegalArgumentException` when the depth limit is exceeded.

R35. `JlsmDocument.defensiveCopyIfVector` must perform a deep copy of nested arrays within `ArrayType` fields, not a shallow clone that leaves inner arrays shared with the caller.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

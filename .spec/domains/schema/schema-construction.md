---
{
  "id": "schema.schema-construction",
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

# schema.schema-construction — Schema Construction

## Requirements

### Construction

R1. `JlsmSchema` must be a `final class` in the `jlsm.table` package. It must not be subclassable. `[EXPLICIT]`

R2. `JlsmSchema` must have a private constructor. Instances must be created exclusively through `JlsmSchema.builder(name, version).build()`. `[EXPLICIT]`

R3. `JlsmSchema.builder(String name, int version)` must reject a null `name` with a `NullPointerException` (via `Objects.requireNonNull`) before returning the builder. `[EXPLICIT]`

R4. The `JlsmSchema` constructor must assert non-null `name` as a development-time invariant check. The public builder entry point enforces this via `Objects.requireNonNull`. `[EXPLICIT]`

R5. The `JlsmSchema` constructor must assert non-null `fields` as a development-time invariant check. `[EXPLICIT]`

R6. The `JlsmSchema` constructor must assert that `maxDepth` is in the range `[0, 25]`. The builder enforces this via runtime `IllegalArgumentException`. `[EXPLICIT]`

R7. `JlsmSchema.builder(String name, int version)` must reject negative `version` values with `IllegalArgumentException`. The constructor must assert `version >= 0` as a development-time invariant check. `[EXPLICIT]`

R8. `JlsmSchema` must define two constants: `DEFAULT_MAX_DEPTH = 10` and `ABSOLUTE_MAX_DEPTH = 25`. Both are private static final. `[EXPLICIT]`

### Field management

R9. The `fields` list must be defensively copied via `List.copyOf(fields)` at construction. The returned list from `fields()` must be unmodifiable. Callers cannot mutate the schema's field list. `[EXPLICIT]`

R10. The `JlsmSchema` constructor must reject duplicate field names (case-sensitive) with an `IllegalArgumentException` whose message includes the duplicate field name and the schema name. `[EXPLICIT]`

R11. An empty field list (zero fields) must be accepted without error. `[IMPLICIT]`

R12. Field ordering must be preserved: `fields()` returns fields in the order they were added via the builder. `[IMPLICIT]`

### Builder lifecycle

R13. `Builder.field(String name, FieldType type)` must reject null `name` or `type` with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R14. `Builder.field(String name, FieldType type, EncryptionSpec encryption)` must reject null `name`, `type`, or `encryption` with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R15. `Builder.field(String name, FieldType type)` must create a `FieldDefinition` with `EncryptionSpec.NONE` as the default encryption. `[EXPLICIT]`

R16. `Builder.vectorField(String name, Primitive elementType, int dimensions)` must reject null `name` or `elementType` with a `NullPointerException`. `[EXPLICIT]`

R17. `Builder.vectorField` must delegate to `FieldType.vector(elementType, dimensions)`, centralizing element type and dimension validation in the `VectorType` constructor. `[EXPLICIT]`

R18. `Builder.maxDepth(int)` must throw `IllegalArgumentException` if the value exceeds 25 or is negative. `[EXPLICIT]`

R19. `Builder.maxDepth(int)` must accept values in the range `[0, 25]` inclusive. `[EXPLICIT]`

R20. The default `maxDepth` if not explicitly set is 10. `[EXPLICIT]`

R21. `Builder.build()` must return a new `JlsmSchema` instance. It re-validates `maxDepth <= ABSOLUTE_MAX_DEPTH` before construction. `[EXPLICIT]`

R22. The `Builder` is not thread-safe. Concurrent use of a single Builder instance from multiple threads is undefined behavior. `[IMPLICIT]`

### Field name validation

R23. The builder's `field()` methods must reject blank field names (empty or whitespace-only) with `IllegalArgumentException`. `[EXPLICIT]`

### Audit-hardened requirements

R24. `JlsmSchema.Builder.objectField()` must reject blank field names (empty or whitespace-only) with `IllegalArgumentException`, matching the validation performed by `field()`.

R25. `DocumentSerializer` must reject schemas with more than 65535 fields with `IllegalArgumentException` at serialization time, because the wire format encodes field count as an unsigned 16-bit integer.

---

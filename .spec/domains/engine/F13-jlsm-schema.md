---
{
  "id": "F13",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["engine"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": []
}
---

# F13 — JlsmSchema

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

### Field lookup

R13. `fieldIndex(String name)` must return the zero-based index of the field with the given name, using case-sensitive HashMap lookup. `[EXPLICIT]`

R14. `fieldIndex(String name)` must return `-1` when no field with the given name exists. `[EXPLICIT]`

R15. `fieldIndex(String name)` must reject a null argument with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R16. The `fieldIndexMap` must be an unmodifiable `Map` built at construction time via `Map.copyOf`. `[EXPLICIT]`

### Accessors

R17. `name()` must return the schema name provided at construction. `[EXPLICIT]`

R18. `version()` must return the schema version provided at construction. `[EXPLICIT]`

R19. `maxDepth()` must return the maximum nesting depth, defaulting to 10 if not explicitly set on the builder. `[EXPLICIT]`

### Nesting (object fields)

R20. `Builder.objectField(String name, Consumer<Builder> nested)` must create a nested builder at `currentDepth + 1`. `[EXPLICIT]`

R21. `Builder.objectField` must reject a null `name` with a `NullPointerException`. `[EXPLICIT]`

R22. `Builder.objectField` must reject a null `nested` consumer with a `NullPointerException`. `[EXPLICIT]`

R23. When the nested builder's depth exceeds the configured `maxDepth`, `buildFields()` must throw an `IllegalArgumentException` whose message includes both the depth and the limit. `[EXPLICIT]`

R24. Nested object fields are represented as `FieldType.ObjectType` containing a `List<FieldDefinition>`. The nested fields list is defensively copied via `List.copyOf`. `[EXPLICIT]`

### Builder lifecycle

R25. `Builder.field(String name, FieldType type)` must reject null `name` or `type` with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R26. `Builder.field(String name, FieldType type, EncryptionSpec encryption)` must reject null `name`, `type`, or `encryption` with a `NullPointerException` (via `Objects.requireNonNull`). `[EXPLICIT]`

R27. `Builder.field(String name, FieldType type)` must create a `FieldDefinition` with `EncryptionSpec.NONE` as the default encryption. `[EXPLICIT]`

R28. `Builder.vectorField(String name, Primitive elementType, int dimensions)` must reject null `name` or `elementType` with a `NullPointerException`. `[EXPLICIT]`

R29. `Builder.vectorField` must delegate to `FieldType.vector(elementType, dimensions)`, centralizing element type and dimension validation in the `VectorType` constructor. `[EXPLICIT]`

R30. `Builder.maxDepth(int)` must throw `IllegalArgumentException` if the value exceeds 25 or is negative. `[EXPLICIT]`

R31. `Builder.maxDepth(int)` must accept values in the range `[0, 25]` inclusive. `[EXPLICIT]`

R32. The default `maxDepth` if not explicitly set is 10. `[EXPLICIT]`

R33. `Builder.build()` must return a new `JlsmSchema` instance. It re-validates `maxDepth <= ABSOLUTE_MAX_DEPTH` before construction. `[EXPLICIT]`

R34. The `Builder` is not thread-safe. Concurrent use of a single Builder instance from multiple threads is undefined behavior. `[IMPLICIT]`

### Immutability and thread safety

R35. All fields of `JlsmSchema` are `private final`. The class is effectively immutable after construction. `[EXPLICIT]`

R36. The `fields` list is stored as an unmodifiable copy (`List.copyOf`). `[EXPLICIT]`

R37. The `fieldIndexMap` is stored as an unmodifiable copy (`Map.copyOf`). `[EXPLICIT]`

R38. `JlsmSchema` instances are safe for concurrent read access from multiple threads without synchronization. `[IMPLICIT]`

### FieldDefinition (companion type)

R39. `FieldDefinition` must be a `public record` in `jlsm.table` with components `(String name, FieldType type, EncryptionSpec encryption)`. `[EXPLICIT]`

R40. The compact constructor must reject null `name`, `type`, or `encryption` with a `NullPointerException`. `[EXPLICIT]`

R41. A two-argument convenience constructor `(String name, FieldType type)` must default `encryption` to `EncryptionSpec.NONE`. `[EXPLICIT]`

### FieldType (companion type)

R42. `FieldType` must be a sealed interface permitting exactly: `Primitive`, `ArrayType`, `ObjectType`, `VectorType`, `BoundedString`. `[EXPLICIT]`

R43. `Primitive` must be an enum implementing `FieldType` with constants: `STRING`, `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT16`, `FLOAT32`, `FLOAT64`, `BOOLEAN`, `TIMESTAMP`. `[EXPLICIT]`

R44. `VectorType` must validate that `elementType` is `FLOAT16` or `FLOAT32`, rejecting others with `IllegalArgumentException`. `[EXPLICIT]`

R45. `VectorType` must validate that `dimensions > 0`, rejecting non-positive values with `IllegalArgumentException`. `[EXPLICIT]`

R46. `BoundedString` must validate that `maxLength > 0`, rejecting non-positive values with `IllegalArgumentException`. `[EXPLICIT]`

R47. `ArrayType` must reject null `elementType` with `NullPointerException`. `[EXPLICIT]`

R48. `ObjectType` must reject null `fields` with `NullPointerException` and defensively copy via `List.copyOf`. `[EXPLICIT]`

R49. Static factory methods must be provided: `string()`, `string(int)`, `int32()`, `int64()`, `float32()`, `float64()`, `boolean_()`, `timestamp()`, `vector(Primitive, int)`, `arrayOf(FieldType)`, `objectOf(List<FieldDefinition>)`. `[EXPLICIT]`

### Structural equality

R50. `JlsmSchema` must implement `equals()` based on `name`, `version`, `fields`, and `maxDepth`. Two schemas with identical structure must be equal. `[EXPLICIT]`

R51. `JlsmSchema` must implement `hashCode()` consistent with `equals()`, computed from `name`, `version`, `fields`, and `maxDepth`. `[EXPLICIT]`

### Field name validation

R52. The builder's `field()` methods must reject blank field names (empty or whitespace-only) with `IllegalArgumentException`. `[EXPLICIT]`

### Absent behaviors

R53. `JlsmSchema` does not implement `toString()`. The default `Object.toString()` is used. `[ABSENT]`

R54. `JlsmSchema` does not implement `Serializable` or any serialization interface. Persistence is handled externally. `[ABSENT]`

R55. There is no field-count upper bound. A schema may contain an arbitrarily large number of fields. `[ABSENT]`

---

## Design Narrative

### Intent

`JlsmSchema` is the foundational type for all schema-driven operations in `jlsm-table`. It describes the structure of documents stored in a `JlsmTable`: an ordered list of named, typed fields with optional encryption specifications. The schema is referenced by the document serializer, the index registry, the query executor, the SQL translator, and the database engine. Despite being consumed by 6 specs, this type had no standalone specification until now.

### Why this approach

**Builder pattern over constructor:** The schema has many optional parameters (maxDepth, nested objects, vector fields, encryption specs) that would make a constructor unwieldy. The builder provides a fluent, readable construction API and validates constraints incrementally (e.g., nesting depth is checked when each object field is built, not deferred to `build()`).

**Defensive copying over shared references:** Both the field list and the field index map are copied at construction (`List.copyOf`, `Map.copyOf`). This guarantees immutability after construction without requiring callers to avoid mutating their inputs. The cost is one copy at construction, amortized over the lifetime of the schema.

**HashMap for field lookup over linear search:** Field lookup by name is O(1) via `fieldIndexMap`. This matters because the SQL translator, index registry, and serializer all perform field lookups during schema validation and per-document operations.

**Assertions as internal invariant checks:** The private constructor uses assertions for null and range checks as development-time invariant verification. All public entry points (builder factory, field methods) enforce constraints via runtime exceptions (`Objects.requireNonNull`, `IllegalArgumentException`). Assertions supplement but never replace runtime validation.

### What was ruled out

- **Record type for JlsmSchema:** A record would require all fields to be public components, which conflicts with the need for a private `fieldIndexMap` derived field.
- **Schema validation at build time:** Validating field type compatibility with encryption specs at schema construction was deferred to downstream consumers (index registry, serializer). The schema is a structural description, not a policy enforcement point.
- **Interned field names:** String interning for field names was considered for memory savings but rejected because schemas are typically small and long-lived.

### Known limitations

- No `toString()` override — the default `Object.toString()` is used, which provides no structural information.
- No field-count upper bound — extremely large schemas are accepted. Downstream consumers with memory constraints should validate field count independently.

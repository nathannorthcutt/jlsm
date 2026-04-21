---
{
  "id": "F13",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
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

R55. There is no field-count upper bound at the schema level. A schema may contain an arbitrarily large number of fields. The `DocumentSerializer` wire format imposes a 65535-field limit at the serialization boundary (see F12 R48). `[ABSENT]`

### Audit-hardened requirements

R56. `JlsmSchema.Builder.objectField()` must reject blank field names (empty or whitespace-only) with `IllegalArgumentException`, matching the validation performed by `field()`.

R57. `FieldType.ObjectType.toSchema()` must propagate the parent schema's encryption specs for each field, not silently drop them.

R58. `FieldType.ObjectType.toSchema()` must accept and propagate the parent schema's `maxDepth` configuration rather than hardcoding a default value.

R59. `DocumentSerializer` must reject schemas with more than 65535 fields with `IllegalArgumentException` at serialization time, because the wire format encodes field count as an unsigned 16-bit integer.

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

---

## Verification Notes

### Verified: v1 — 2026-04-16

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `JlsmSchema.java:25` — `public final class JlsmSchema` |
| R2 | SATISFIED | `JlsmSchema.java:45` private ctor; builder is sole entry point (line 143) |
| R3 | SATISFIED | `JlsmSchema.java:144` — `Objects.requireNonNull(name, ...)` |
| R4 | SATISFIED | `JlsmSchema.java:46` — runtime `Objects.requireNonNull` (stronger than spec's `assert`) |
| R5 | SATISFIED | `JlsmSchema.java:47` — runtime `Objects.requireNonNull` (stronger than `assert`) |
| R6 | SATISFIED | `JlsmSchema.java:51-54` — runtime range check + IAE (stronger than `assert`); builder at 297-303 |
| R7 | SATISFIED | `JlsmSchema.java:48-50` constructor; builder at 145-147 |
| R8 | SATISFIED | `JlsmSchema.java:28-29` — both constants `private static final` |
| R9 | SATISFIED | `JlsmSchema.java:58` `List.copyOf`; `fields()` returns unmodifiable (88-90) |
| R10 | SATISFIED | `JlsmSchema.java:65-68` — message includes duplicate name and schema name |
| R11 | SATISFIED | loop at `JlsmSchema.java:63-70` no-ops on empty list |
| R12 | SATISFIED | `List.copyOf` preserves iteration order; `ArrayList` preserves insert order |
| R13 | SATISFIED | `JlsmSchema.java:127-130` — HashMap-backed `getOrDefault` |
| R14 | SATISFIED | `JlsmSchema.java:129` — `getOrDefault(name, -1)` |
| R15 | SATISFIED | `JlsmSchema.java:128` — `Objects.requireNonNull` |
| R16 | SATISFIED | `JlsmSchema.java:71` — `Map.copyOf(indexMap)` |
| R17 | SATISFIED | `JlsmSchema.java:76-78` |
| R18 | SATISFIED | `JlsmSchema.java:82-84` |
| R19 | SATISFIED | `JlsmSchema.java:94-96`; default 10 set at builder entry (line 148) |
| R20 | SATISFIED | `JlsmSchema.java:271-273` — `nestedDepth = currentDepth + 1` |
| R21 | SATISFIED | `JlsmSchema.java:263` |
| R22 | SATISFIED | `JlsmSchema.java:264` |
| R23 | SATISFIED | `JlsmSchema.java:333-337` — `buildFields` throws IAE including depth and limit |
| R24 | SATISFIED | `FieldType.java:45-49` — `ObjectType` compact ctor copies via `List.copyOf` |
| R25 | SATISFIED | `JlsmSchema.java:192-193` |
| R26 | SATISFIED | `JlsmSchema.java:214-216` |
| R27 | SATISFIED | `JlsmSchema.java:199` uses 2-arg `FieldDefinition` ctor which defaults to `EncryptionSpec.NONE` (`FieldDefinition.java:34-36`) |
| R28 | SATISFIED | `JlsmSchema.java:238-239` |
| R29 | SATISFIED | `JlsmSchema.java:242` — delegates to `FieldType.vector` |
| R30 | SATISFIED | `JlsmSchema.java:297-303` — rejects >25 and <0 with IAE |
| R31 | SATISFIED | range check at `JlsmSchema.java:297,301` admits `[0, 25]` |
| R32 | SATISFIED | `JlsmSchema.java:148` — `DEFAULT_MAX_DEPTH` passed to Builder |
| R33 | SATISFIED | `JlsmSchema.java:316-323` — re-validates `maxDepth <= ABSOLUTE_MAX_DEPTH` |
| R34 | UNTESTABLE | thread-safety claim; single `ArrayList` in Builder confirms absence of synchronization |
| R35 | SATISFIED | `JlsmSchema.java:31-35` — all fields `private final` |
| R36 | SATISFIED | `JlsmSchema.java:58` — `List.copyOf` |
| R37 | SATISFIED | `JlsmSchema.java:71` — `Map.copyOf` |
| R38 | UNTESTABLE | thread-safety claim; justified by R35-R37 immutability |
| R39 | SATISFIED | `FieldDefinition.java:15` — `public record FieldDefinition(String name, FieldType type, EncryptionSpec encryption)` |
| R40 | SATISFIED | `FieldDefinition.java:21-25` — compact ctor null checks |
| R41 | SATISFIED | `FieldDefinition.java:34-36` — 2-arg ctor defaults to `EncryptionSpec.NONE` |
| R42 | SATISFIED | `FieldType.java:18-19` — sealed permits exactly 5 types |
| R43 | SATISFIED | `FieldType.java:23-25` — enum with all 10 constants |
| R44 | SATISFIED | `FieldType.java:118-121` — rejects non-FLOAT16/32 |
| R45 | SATISFIED | `FieldType.java:122-125` |
| R46 | SATISFIED | `FieldType.java:99-103` |
| R47 | SATISFIED | `FieldType.java:34-36` |
| R48 | SATISFIED | `FieldType.java:46-49` — null check + `List.copyOf` |
| R49 | SATISFIED | `FieldType.java:133-208` — all factories present |
| R50 | SATISFIED | `JlsmSchema.java:99-109` — uses name, version, fields, maxDepth |
| R51 | SATISFIED | `JlsmSchema.java:112-115` — `Objects.hash(name, version, fields, maxDepth)` |
| R52 | SATISFIED | `JlsmSchema.java:194-196, 217-219` — `isBlank()` check + IAE |
| R53 | SATISFIED | no `toString()` override in `JlsmSchema.java` |
| R54 | SATISFIED | class does not implement `Serializable` |
| R55 | SATISFIED | no field-count check at schema level (enforced only in `DocumentSerializer`) |
| R56 | SATISFIED | `JlsmSchema.java:265-267` — `objectField` rejects blank names |
| R57 | SATISFIED | `FieldType.java:60-67, 80-87` — `toSchema` passes `fd.encryption()` |
| R58 | SATISFIED | `FieldType.java:80-87` — second overload accepts and propagates `maxDepth` |
| R59 | SATISFIED | `DocumentSerializer.java:140-142` — rejects `fieldCount > 0xFFFF` with IAE |

**Overall: PASS**

Obligations resolved: 0
Obligations remaining: 0
Undocumented behavior:
- `Builder.maxDepth(int)` throws `IllegalStateException` on nested builders (`JlsmSchema.java:293-296`) — enforces that `maxDepth` can only be configured on the root builder. Not currently covered by a requirement; consider R60.
- `DocumentSerializer` also rejects schema `version > 0xFFFF` (`DocumentSerializer.java:132-135`); this is F12 serialization territory, not F13.

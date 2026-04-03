---
{
  "id": "F14",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["engine"],
  "requires": ["F13"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": []
}
---

# F14 — JlsmDocument

## Requirements

### Construction

R1. `JlsmDocument` must be a `public final class` in the `jlsm.table` package. It must not be subclassable. `[EXPLICIT]`

R2. `JlsmDocument` must have package-private constructors only. Direct construction is restricted to code within `jlsm.table`. Public construction must go through the `of()` or `preEncrypted()` static factory methods. `[EXPLICIT]`

R3. The two-argument package-private constructor `JlsmDocument(JlsmSchema, Object[])` must delegate to the three-argument constructor with `preEncrypted = false`. `[EXPLICIT]`

R4. The three-argument package-private constructor must validate via assertions: schema is non-null, values is non-null, and `values.length == schema.fields().size()`. When assertions are disabled, these checks are skipped. `[IMPLICIT]`

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

R31. `JlsmDocument.of` must not defensively copy values for non-`VectorType` fields (including `Object[]` for `ArrayType`, `String`, boxed primitives, nested `JlsmDocument`). `[IMPLICIT]`

R32. `JlsmDocument.preEncrypted` must defensively copy `VectorType` field values via `clone()`, consistent with the `of()` factory. `[EXPLICIT]`

### Typed getters

R33. All typed getters must reject a null field name with a `NullPointerException` (via `requireIndex` calling `Objects.requireNonNull`). `[EXPLICIT]`

R34. All typed getters must reject an unknown field name with an `IllegalArgumentException`. `[EXPLICIT]`

R35. All typed getters (except `isNull`) must throw `NullPointerException` when the field value is null. `[EXPLICIT]`

R36. All typed getters must throw `IllegalArgumentException` when the field's declared type does not match the getter's expected type. `[EXPLICIT]`

R37. `getString(field)` must accept fields of type `STRING` or `BoundedString`. `[EXPLICIT]`

R38. `getLong(field)` must accept fields of type `INT64` or `TIMESTAMP`. `[EXPLICIT]`

R39. `getFloat16Bits(field)` must return the raw `short` value for `FLOAT16` fields, representing IEEE 754 half-precision bits. `[EXPLICIT]`

R40. `getArray(field)` must return a defensive copy of the `Object[]` value via `clone()`. `[EXPLICIT]`

R41. `getObject(field)` must return the nested `JlsmDocument` directly without copying. `[IMPLICIT]`

### Schema accessor

R42. `schema()` must return the `JlsmSchema` passed at construction. The return value is the same reference, not a copy. `[EXPLICIT]`

### Null field query

R43. `isNull(String field)` must return `true` when the value at the field's index is null, `false` otherwise. `[EXPLICIT]`

R44. `isNull(String field)` must reject a null field name with `NullPointerException` and an unknown field name with `IllegalArgumentException`. `[EXPLICIT]`

### Serialization (JSON)

R45. `toJson()` must serialize the document to a compact JSON string (no indentation). `[EXPLICIT]`

R46. `toJson(boolean pretty)` must serialize with 2-space indentation when `pretty` is true, compact when false. `[EXPLICIT]`

R47. `fromJson(String json, JlsmSchema schema)` must deserialize a JSON string into a `JlsmDocument` conforming to the given schema. `[EXPLICIT]`

### Serialization (YAML)

R48. `toYaml()` must serialize the document to a YAML block-style string. `[EXPLICIT]`

R49. `fromYaml(String yaml, JlsmSchema schema)` must deserialize a YAML string into a `JlsmDocument` conforming to the given schema. `[EXPLICIT]`

### Pre-encrypted document support

R50. `isPreEncrypted()` must be package-private. It must return `true` for documents created via `preEncrypted()`, `false` for documents created via `of()` or the package-private constructors. `[EXPLICIT]`

R51. `values()` must be package-private. It must return the internal `Object[]` array directly, without defensive copy. `[EXPLICIT]`

### Internal access (DocumentAccess)

R52. `JlsmDocument` must register a `DocumentAccess.Accessor` in a static initializer block. The accessor must provide access to `values()`, `create(JlsmSchema, Object[])`, and `isPreEncrypted()` from the `jlsm.table.internal` package. `[EXPLICIT]`

R53. The `create` method on the accessor must invoke the two-argument package-private constructor, producing a non-pre-encrypted document. `[EXPLICIT]`

### Immutability and thread safety

R54. The `schema` and `preEncrypted` fields are `private final` and immutable after construction. `[EXPLICIT]`

R55. The `values` array reference is `private final`, but the array contents are mutable. Package-private callers with access to `values()` can mutate array elements. `[IMPLICIT]`

R56. `JlsmDocument` does not provide any synchronization. Concurrent read and write access to the same document instance (via package-private `values()` array mutation) is not thread-safe. `[IMPLICIT]`

R57. For documents constructed via `of()` and accessed only through public typed getters, the document is effectively immutable for primitive and String field values. Mutable field values (nested `JlsmDocument` from `getObject()`, the original `Object[]` for `ArrayType` fields held in `values`) can still be externally mutated. `[IMPLICIT]`

### Structural equality

R58. `JlsmDocument` must implement `equals()` based on `schema`, `preEncrypted`, and deep equality of the `values` array (via `Arrays.deepEquals`). `[EXPLICIT]`

R59. `JlsmDocument` must implement `hashCode()` consistent with `equals()`, incorporating `schema`, `preEncrypted`, and `Arrays.deepHashCode(values)`. `[EXPLICIT]`

### Vector getters

R60. `getFloat32Vector(String field)` must return a defensive copy (`clone()`) of the `float[]` value for `VectorType(FLOAT32)` fields. It must throw `IllegalArgumentException` if the field is not `VectorType(FLOAT32)`, and `NullPointerException` if the value is null. `[EXPLICIT]`

R61. `getFloat16Vector(String field)` must return a defensive copy (`clone()`) of the `short[]` value for `VectorType(FLOAT16)` fields. It must throw `IllegalArgumentException` if the field is not `VectorType(FLOAT16)`, and `NullPointerException` if the value is null. `[EXPLICIT]`

### Absent behaviors

R62. `JlsmDocument` does not implement `toString()`. The default `Object.toString()` is used. Use `toJson()` for human-readable output. `[ABSENT]`

R63. `JlsmDocument` does not implement `Serializable` or any serialization interface. Binary serialization is handled by `DocumentSerializer` in `jlsm.table.internal`. `[ABSENT]`

R64. `JlsmDocument` does not validate that nested `JlsmDocument` values for `ObjectType` fields conform to the `ObjectType`'s declared field list. Only the Java type (`JlsmDocument`) is checked. `[ABSENT]`

R65. There is no `set` or `with` method for updating individual field values. Documents are write-once from the public API perspective. `[ABSENT]`

---

## Design Narrative

### Intent

`JlsmDocument` is the runtime representation of a document stored in a `JlsmTable`. It associates a `JlsmSchema` with an ordered array of field values. The class is consumed by the document serializer (binary encoding/decoding), the index registry (field value extraction for indexing), the query executor (scan-and-filter evaluation), the SQL translator (result representation), the encryption dispatch (pre-encrypted ciphertext handling), the partitioned table (CRUD routing), and the clustering layer (remote serialization). Despite being referenced by 9 specs, this type had no standalone specification until now.

### Why this approach

**Final class over sealed hierarchy or record:** `JlsmDocument` has mutable internal state (the `values` array is not defensively copied at construction for performance), a package-private internal access bridge (`DocumentAccess`), and multiple construction paths (`of`, `preEncrypted`, package-private constructors). A record would expose the `values` array as a public component. A sealed hierarchy is not appropriate because there are no semantic subtypes.

**Object[] over typed storage:** The `values` array stores heterogeneous field values as `Object`. This is simpler than a struct-of-arrays approach and allows the schema to define fields of any type without specialization. The cost is boxing for primitive types, which is acceptable for the current design. Future optimization (value types, primitive specialization) is out of scope.

**Package-private constructors over public constructors:** The two-argument constructor is used by the deserializer and `DocumentAccess` bridge. These internal callers provide pre-validated, pre-ordered value arrays and must not pay the cost of factory method validation. The public API forces callers through `of()` or `preEncrypted()`, which perform full validation.

**Defensive copy for vectors but not arrays:** Vector arrays (`float[]`, `short[]`) are mutable and represent fixed-dimension numeric data that is frequently mutated by callers (e.g., normalization, quantization). Defensive copying at both `of()` and `preEncrypted()` prevents the common bug where a caller mutates a vector after storing it. `Object[]` for `ArrayType` is not defensively copied at construction (only at `getArray()`), which is an inconsistency but reflects the lower likelihood of array mutation in practice.

### What was ruled out

- **Defensive copy of all values at construction:** Would impose unnecessary overhead for immutable types (String, boxed primitives) and for internal callers (deserializer) that construct values arrays specifically for the document.
- **Thread-safe document:** Synchronizing access to the values array would add overhead to every getter call. Documents are intended to be written once and read from a single thread or passed between threads with proper happens-before.
- **Typed vector getters returning raw references:** Vector getters return defensive copies to maintain the same immutability guarantee as `getArray()`.

### Known limitations

- The `values` array is not defensively copied at construction. Package-private code that mutates the array after constructing a document will see the mutation reflected in the document. This is a deliberate trust boundary, not a bug.
- Nested `JlsmDocument` for `ObjectType` fields is not validated against the nested schema. A caller can store a document with a mismatched schema.

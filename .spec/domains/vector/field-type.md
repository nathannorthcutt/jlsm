---
{
  "id": "vector.field-type",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "vector"
  ],
  "requires": [
    "vector.float16-vector-support"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "vector-type-serialization-encoding",
    "index-definition-api-simplification"
  ],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F12"
  ]
}
---
# vector.field-type — Vector Field Type

## Requirements

### VectorType as a FieldType implementation

R1. `FieldType.VectorType` must be a `record` implementing the sealed `FieldType` interface, with components `elementType` (FieldType.Primitive) and `dimensions` (int).

R2. `FieldType.VectorType` must be listed as a permitted implementation of the sealed `FieldType` interface.

R3. `FieldType.VectorType` must reject a null `elementType` at construction with a `NullPointerException`.

R4. `FieldType.VectorType` must reject an `elementType` that is not `FLOAT16` or `FLOAT32` at construction with an `IllegalArgumentException`.

R5. `FieldType.VectorType` must reject a `dimensions` value of zero or negative at construction with an `IllegalArgumentException`.

R6. `FieldType.VectorType` must accept any positive `dimensions` value without an upper bound enforced at the type level.

### Static factory method

R7. `FieldType.vector(Primitive elementType, int dimensions)` must return a new `VectorType` instance with the given element type and dimensions.

R8. `FieldType.vector` must propagate the validation exceptions from the `VectorType` constructor (null elementType, invalid elementType, non-positive dimensions).

### Schema builder convenience

R9. `JlsmSchema.Builder.vectorField(String name, Primitive elementType, int dimensions)` must add a field with type `FieldType.VectorType` to the schema.

R10. `JlsmSchema.Builder.vectorField` must reject a null `name` with a `NullPointerException`.

R11. `JlsmSchema.Builder.vectorField` must reject a null `elementType` with a `NullPointerException`.

R12. `JlsmSchema.Builder.vectorField` must delegate to `FieldType.vector(elementType, dimensions)` so that element type and dimension validation is centralized in the `VectorType` constructor.

### IndexDefinition simplification

R13. `IndexDefinition` must be a record with exactly three components: `fieldName` (String), `indexType` (IndexType), and `similarityFunction` (SimilarityFunction, nullable).

R14. `IndexDefinition` must not carry a `vectorDimensions` field or parameter.

R15. `IndexDefinition` must provide a two-argument convenience constructor `(fieldName, indexType)` that passes null for `similarityFunction`.

R16. `IndexDefinition` must require a non-null `similarityFunction` when `indexType` is `VECTOR`, rejecting null with a `NullPointerException`.

R17. `IndexDefinition` must reject a non-null `similarityFunction` when `indexType` is not `VECTOR` with an `IllegalArgumentException`.

### IndexRegistry validation — VectorType required for VECTOR indices

R18. `IndexRegistry` must reject a `VECTOR` index definition on a field whose `FieldType` is `ArrayType` with an `IllegalArgumentException`.

R19. `IndexRegistry` must reject a `VECTOR` index definition on a field whose `FieldType` is any `Primitive` with an `IllegalArgumentException`.

R20. `IndexRegistry` must reject a `VECTOR` index definition on a field whose `FieldType` is `ObjectType` with an `IllegalArgumentException`.

R21. `IndexRegistry` must reject a `VECTOR` index definition on a field whose `FieldType` is `BoundedString` with an `IllegalArgumentException`.

R22. `IndexRegistry` must accept a `VECTOR` index definition on a field whose `FieldType` is `VectorType` without throwing.

R23. `IndexRegistry` must derive vector dimensions from the schema field's `VectorType.dimensions()` at construction time, not from `IndexDefinition`.

### DocumentSerializer — VectorType measure

R24. `DocumentSerializer` must measure a `VectorType` field as exactly `dimensions * 4` bytes when `elementType` is `FLOAT32`.

R25. `DocumentSerializer` must measure a `VectorType` field as exactly `dimensions * 2` bytes when `elementType` is `FLOAT16`.

R26. `DocumentSerializer` must not include a VarInt length prefix in the measured size for `VectorType` fields, because dimensions are fixed and known from the schema.

### DocumentSerializer — VectorType encode

R27. `DocumentSerializer` must encode a `FLOAT32` `VectorType` field as `dimensions` consecutive big-endian 32-bit IEEE 754 floats with no length prefix.

R28. `DocumentSerializer` must encode a `FLOAT16` `VectorType` field as `dimensions` consecutive big-endian 16-bit IEEE 754 half-precision values with no length prefix.

R29. `DocumentSerializer` must verify at encode time that the provided vector array length equals the schema-declared `dimensions`, throwing `IllegalArgumentException` on mismatch.

### DocumentSerializer — VectorType decode

R30. `DocumentSerializer` must decode a `FLOAT32` `VectorType` field by reading `dimensions` consecutive big-endian 32-bit floats and returning a `float[]`.

R31. `DocumentSerializer` must decode a `FLOAT16` `VectorType` field by reading `dimensions` consecutive big-endian 16-bit values and returning a `short[]`.

R32. `DocumentSerializer` must decode `VectorType` fields using the schema-declared dimensions without reading a length prefix from the byte stream.

### DocumentSerializer — round-trip integrity

R33. `DocumentSerializer` must round-trip a document containing a `FLOAT32` `VectorType` field: `deserialize(serialize(doc))` must produce a `float[]` with identical bit patterns to the original.

R34. `DocumentSerializer` must round-trip a document containing a `FLOAT16` `VectorType` field: `deserialize(serialize(doc))` must produce a `short[]` with identical bit patterns to the original.

### Null vector field handling

R35. `DocumentSerializer` must serialize a null `VectorType` field by marking it in the null bitmask and emitting zero payload bytes for that field.

R36. `DocumentSerializer` must deserialize a null `VectorType` field (null bit set) as a null value without reading any bytes from the payload.

### Schema evolution — dimension mismatch

R37. When a document was serialized with schema version V1 (declaring `VectorType(FLOAT32, 128)`) and is deserialized with schema version V2 (declaring `VectorType(FLOAT32, 256)`), the decoder must read only the V1 field count from the header and must not attempt to read 256 floats from a 128-float payload.

R38. `DocumentSerializer` must use the write-time field count from the serialized header (not the current schema's field count) to determine how many fields to decode, ensuring forward-compatible schema evolution.

### VectorType identity and equality

R39. Two `VectorType` instances with the same `elementType` and `dimensions` must be equal per `equals()` and produce the same `hashCode()`.

R40. Two `VectorType` instances with different `elementType` values must not be equal per `equals()`.

R41. Two `VectorType` instances with different `dimensions` values must not be equal per `equals()`.

### No test regressions

R42. All existing tests that use `ArrayType` for non-vector fields must continue to pass without modification after `VectorType` is added.

R43. All existing `IndexDefinition` tests must continue to pass after removal of `vectorDimensions`, given that `IndexDefinition` already has the three-component form `(fieldName, indexType, similarityFunction)`.

### Audit-hardened requirements

R44. `FieldType.arrayOf()` and `FieldType.objectOf()` must reject null arguments with a `NullPointerException` via `Objects.requireNonNull`, not via `assert` alone.

R45. `DocumentSerializer` must verify at encode time that the provided vector array length equals the schema-declared dimensions using a runtime check (`IllegalArgumentException`), not an `assert` statement.

R46. `DocumentSerializer` must validate at decode time that the byte buffer contains sufficient bytes for the vector dimensions before reading, throwing `IllegalArgumentException` with a descriptive message on truncated input.

R47. `DocumentSerializer` must store the write-time boolean field count in the serialized schema header, and must validate it against the current schema's boolean count during deserialization to detect field type evolution mismatches.

R48. `DocumentSerializer` must wrap field-level encode operations in error handling that re-throws type mismatches as `IllegalArgumentException` with the field name and expected type in the message.

R49. `JlsmDocument.validateType` must reject a `VectorType` field whose array length does not match the declared `VectorType.dimensions()`, throwing `IllegalArgumentException` at document construction time. JSON serialization paths (e.g., `JsonValueAdapter.vectorToJson`) operate on pre-validated documents and are not required to re-check dimensions.

R50. `DocumentSerializer` must reject schema version values exceeding 65535 with `IllegalArgumentException` at serialization time, because the wire format encodes version as an unsigned 16-bit integer.

---

## Design Narrative

### Intent

Introduce a first-class `VectorType` into the `FieldType` sealed hierarchy so that vector fields carry their element precision and dimension count in the schema, rather than requiring out-of-band dimension configuration in `IndexDefinition`. This tightens the type system: the schema declares a field is a fixed-dimension vector, and all downstream consumers (serializer, index registry, query executor) can derive vector properties from the schema alone.

### Why this approach

**Schema-embedded dimensions over IndexDefinition dimensions:** Dimensions are a property of the data, not the index. A vector field has a fixed dimension regardless of whether it is indexed. Embedding dimensions in the schema means the serializer can encode/decode without a length prefix (the dimension count is known statically), the index registry can validate dimension compatibility at construction, and schema evolution rules apply uniformly. Storing dimensions in `IndexDefinition` conflated two concerns: what the data looks like (schema) and how it is queried (index).

**No VarInt length prefix for VectorType:** Unlike `ArrayType`, which has variable-length elements, `VectorType` has a fixed byte size computable from `elementType` and `dimensions`. Omitting the length prefix saves 1-5 bytes per vector and simplifies the codec. The serializer knows the exact byte count from the schema, so no self-describing framing is needed. This matches the flat encoding convention established in ADR `vector-type-serialization-encoding`.

**Sealed interface enforcement:** Adding `VectorType` as a permitted implementation of the sealed `FieldType` makes the compiler enforce exhaustive pattern matching everywhere `FieldType` is switched on (serializer, codec, index registry). Any code path that handled `ArrayType` for vectors is forced to add a `VectorType` case, preventing silent mishandling.

**Element type restricted to FLOAT16 and FLOAT32:** These are the only two precisions supported by the vector index layer (F01). Allowing arbitrary element types (e.g., FLOAT64, INT32) would create schema declarations that no index can serve. The restriction is enforced at `VectorType` construction, failing fast before a schema can be built.

### What was ruled out

- **VectorType as a subtype of ArrayType:** Would conflate fixed-dimension vectors with variable-length arrays. ArrayType carries no dimension information, and its serialization includes a VarInt length prefix that vectors do not need. A separate type avoids ambiguity and enables distinct serialization paths.
- **Dimension upper bound in VectorType:** While extremely high dimensions (e.g., 10 million) are impractical, imposing an arbitrary cap would require a magic number that may not suit all use cases. The index layer already validates dimension compatibility at construction. The type layer validates positivity only.
- **SIMD byte-swap for VectorType FLOAT32 encode:** The current implementation uses a scalar loop for vector encoding. SIMD byte-swap is used for `ArrayType` INT32/INT64/FLOAT32/FLOAT64 arrays. Adding SIMD to `VectorType` encoding is a performance optimization deferred to a future spec, not a correctness requirement.
- **Backward-compatible IndexDefinition with deprecated vectorDimensions:** Adding `@Deprecated` fields to records is awkward in Java. Since `IndexDefinition` already has the simplified three-component form in the current codebase, no migration is needed.

---

## Verification Notes

### Verified: v2 — 2026-04-17

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | FieldType.java:117 (VectorType record) |
| R2 | SATISFIED | FieldType.java:19-20 (permits clause) |
| R3 | SATISFIED | FieldType.java:119 (requireNonNull) |
| R4 | SATISFIED | FieldType.java:120-123 |
| R5 | SATISFIED | FieldType.java:124-127 |
| R6 | SATISFIED | FieldType.java:117-128 (no upper bound) |
| R7 | SATISFIED | FieldType.java:186-188 |
| R8 | SATISFIED | FieldType.java:186-188 (delegates to ctor) |
| R9 | SATISFIED | JlsmSchema.java:237-243 |
| R10 | SATISFIED | JlsmSchema.java:238 |
| R11 | SATISFIED | JlsmSchema.java:239 |
| R12 | SATISFIED | JlsmSchema.java:242 |
| R13 | SATISFIED | IndexDefinition.java:27-28 |
| R14 | SATISFIED | IndexDefinition.java:27-28 (no dims field) |
| R15 | SATISFIED | IndexDefinition.java:33-35 |
| R16 | SATISFIED | IndexDefinition.java:43-45 |
| R17 | SATISFIED | IndexDefinition.java:46-49 |
| R18 | SATISFIED | IndexRegistry.java:466-472 |
| R19 | SATISFIED | IndexRegistry.java:466-472 |
| R20 | SATISFIED | IndexRegistry.java:466-472 |
| R21 | SATISFIED | IndexRegistry.java:466-472 |
| R22 | SATISFIED | IndexRegistry.java:466-472 |
| R23 | SATISFIED | IndexDefinition has no dims; registry derives from schema |
| R24 | SATISFIED | DocumentSerializer.java:471-474 |
| R25 | SATISFIED | DocumentSerializer.java:471-474 |
| R26 | SATISFIED | DocumentSerializer.java:471-474 (no varInt call) |
| R27 | SATISFIED | DocumentSerializer.java:647-656 |
| R28 | SATISFIED | DocumentSerializer.java:658-667 |
| R29 | SATISFIED | DocumentSerializer.java:649-651, 660-662 |
| R30 | SATISFIED | DocumentSerializer.java:884-890 |
| R31 | SATISFIED | DocumentSerializer.java:892-898 |
| R32 | SATISFIED | DocumentSerializer.java:876 (uses vt.dimensions()) |
| R33 | SATISFIED | VectorTypeTest.documentSerializer_roundTripsFloat32Vector |
| R34 | SATISFIED | VectorTypeTest.documentSerializer_roundTripsFloat16Vector |
| R35 | SATISFIED | DocumentSerializer.java:428-430, 1042-1048 |
| R36 | SATISFIED | DocumentSerializer.java:386-392 |
| R37 | SATISFIED | DocumentSerializer.java:350, 354 + decodeVector bounds check |
| R38 | SATISFIED | DocumentSerializer.java:350, 354 |
| R39 | SATISFIED | record auto-generated equals/hashCode |
| R40 | SATISFIED | record auto-generated equals |
| R41 | SATISFIED | record auto-generated equals |
| R42 | SATISFIED | full jlsm-table suite passes |
| R43 | SATISFIED | full jlsm-table suite passes |
| R44 | SATISFIED | FieldType.java:196-198, 207-210 |
| R45 | SATISFIED | DocumentSerializer.java:649-651, 660-662 |
| R46 | SATISFIED | DocumentSerializer.java:879-883 |
| R47 | SATISFIED | DocumentSerializer.java:243, 351, 363-371 |
| R48 | SATISFIED | DocumentSerializer.java:431-437, 499-505 |
| R49 | SATISFIED | JlsmDocument.java:541-567 (validateType); JsonValueAdapter operates on validated docs |
| R50 | SATISFIED | DocumentSerializer.java:133-136 |

**Overall: PASS**

Amendments applied: 1 (R49)
Code fixes applied: 0
Regression tests added: 0
Tests added for coverage: 15 (R2, R3, R6, R8, R10, R11, R14, R20, R21, R23, R26/R32, R29, R39, R40, R41, R44, R46, R47, R48, R50)
Obligations deferred: 0

#### Amendments
- **R49** (v1 → v2): Reworded to reflect the true enforcement site.
  - Old: "`JsonWriter.writeVector` must validate that the vector array length matches the declared `VectorType.dimensions()` before serializing, throwing `IllegalArgumentException` on mismatch."
  - New: "`JlsmDocument.validateType` must reject a `VectorType` field whose array length does not match the declared `VectorType.dimensions()`, throwing `IllegalArgumentException` at document construction time. JSON serialization paths (e.g., `JsonValueAdapter.vectorToJson`) operate on pre-validated documents and are not required to re-check dimensions."
  - Reason: `JsonWriter.writeVector` does not exist in the codebase; the canonical dimension check happens at `JlsmDocument.of()` construction, which is the correct architectural layer. JSON serialization receives only pre-validated documents.

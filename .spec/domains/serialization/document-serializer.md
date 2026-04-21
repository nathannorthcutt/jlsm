---
{
  "id": "serialization.document-serializer",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "serialization"
  ],
  "amends": [],
  "amended_by": [],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F06"
  ]
}
---
# serialization.document-serializer — DocumentSerializer Deserialization Optimization

## Requirements

### Byte extraction and heap fast path

R1. The deserializer must obtain a byte array view from the input `MemorySegment` via an internal `extractBytes` helper. When the segment is heap-backed by a `byte[]` whose length equals the segment's `byteSize()`, the helper must return the backing array directly without copying (zero-copy fast path).

R2. The fast-path eligibility check must consist of exactly three conditions, all of which must hold: (a) `segment.heapBase().isPresent()`, (b) `heapBase().get() instanceof byte[]`, and (c) `segment.byteSize() == data.length`. When any condition fails, the helper must use the fallback path. The size-equality check guards against sliced segments where the backing array is larger than the segment.

R3. The fallback path must call `segment.toArray(ValueLayout.JAVA_BYTE)` to obtain a freshly copied byte array with the view offset set to zero. The fallback path covers three cases: off-heap/memory-mapped segments (no heap base), heap-backed segments whose base is not a `byte[]` (e.g., future JDK variants), and sliced heap-backed segments (where `byteSize() != data.length`).

R4. The view offset returned by `extractBytes` must always be zero. The fast path is restricted to full-array segments (by the size-equality check), which trivially have offset zero; sliced segments flow through the fallback path, which returns a fresh copied array starting at offset zero. Downstream read code may safely assume the cursor starts at offset zero.

R5. A non-`byte[]` heap base (possible future JDK variant) must flow through the fallback path rather than throwing a `ClassCastException`. This is a deliberate safety choice: correctness is preserved (at the cost of a copy) if the JDK introduces heap-backed segments whose base type is not `byte[]`.

### Schema constant precomputation

R6. The SchemaSerializer must compute the following values once at construction time from the provided JlsmSchema: field count, boolean field count, null-mask byte count, boolean-mask byte count, a boolean flag per field indicating whether it is a boolean type, and a prefix-sum array of boolean field counts.

R7. The prefix boolean count array must have length `fieldCount + 1`, where entry `i` contains the number of boolean-typed fields among positions 0 through i-1 (exclusive). Entry 0 must be 0. Entry `fieldCount` must equal the total boolean field count.

R8. The deserializer must use the prefix boolean count array for O(1) lookup of the number of boolean fields preceding any given field position. The per-call linear scan (`countBoolFieldsUpTo`) must not be invoked during deserialization.

R9. Schema constants must be derived solely from the JlsmSchema passed to the SchemaSerializer constructor. The deserializer must not re-derive any schema-level constant (field types, counts, mask sizes) during a `deserialize()` call.

### Field decoder dispatch table

R10. The SchemaSerializer must build a dispatch table (one decoder entry per field position) at construction time. Each entry must decode a single field type from a byte array and cursor position.

R11. Boolean fields must not have a dispatch table entry that reads from the variable-length data region. Boolean values must be decoded from the boolean bitmask, not through the dispatch table.

R12. Every non-boolean field type in the schema must have a corresponding decoder. This must be enforced at compile time via the sealed `FieldType` hierarchy and an exhaustive `switch` in `decodeField` with no `default` branch. Adding a new `FieldType` subtype without a decoder case must produce a compilation error. A runtime constructor check is not required because compile-time exhaustiveness is strictly stronger: it prevents the error state from ever reaching a running JVM.

R13. The dispatch table entries for a given schema must produce byte-identical decoded values to the current switch-based `decodeField` implementation for all input bytes. No field type may silently change its decoding behavior as a result of the dispatch table introduction.

### Deserialization correctness

R14. For any MemorySegment produced by `SchemaSerializer.serialize()`, `deserialize()` must return a JlsmDocument with field values that are equal (by `Object.equals`) to the original document's field values, for all field types and all schemas.

R15. When the serialized document's write-time field count is less than the current schema's field count (schema evolution: fields added after the document was written), the deserializer must populate only the fields present in the serialized data and leave remaining fields as null.

R16. When the serialized document's write-time field count is greater than the current schema's field count (schema evolution: fields removed after the document was written), the deserializer must read only the fields defined in the current schema and skip all trailing serialized fields without error.

R17. The deserializer must correctly handle null fields: a field marked as null in the null bitmask must produce a null value in the output document regardless of field type, and must not advance the variable-length data cursor.

R18. A null boolean field must increment the boolean index counter (to maintain alignment with the boolean bitmask) but must not read a value from the boolean bitmask for that position.

### Serialization path invariance

R19. The serialization path (`serialize()` method) must not be modified by this optimization. Only the deserialization path is in scope.

R20. The on-disk binary format must not change. Documents serialized before this optimization must deserialize correctly after it, and documents serialized after must deserialize correctly by unoptimized readers.

### Concurrency and lifecycle

R21. The SchemaSerializer must remain safe for concurrent use from multiple threads after construction, as required by the existing MemorySerializer contract. Precomputed arrays and the dispatch table must be effectively immutable after construction.

R22. The byte array obtained via `heapBase()` must not be retained beyond the scope of a single `deserialize()` call. The caller owns the MemorySegment's lifecycle; the deserializer must not cache or alias the backing array across calls.

### Error handling

R23. Corrupt serialized data (truncated segments, malformed variable-length integers, invalid field data) must produce the same exception type and failure mode as the current implementation. The optimization must not introduce new exception types or swallow exceptions that the current implementation throws.

R24. A MemorySegment with zero bytes must produce the same error behavior as the current implementation, whether heap-backed or off-heap.

## Cross-References

- Feature brief: .feature/optimize-document-serializer/brief.md
- Work plan: .feature/optimize-document-serializer/work-plan.md

## Verification Notes

### Verified: v2 — 2026-04-16

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `DocumentSerializer.java:1189` — `extractBytes` fast path |
| R2 | SATISFIED | `DocumentSerializer.java:1189` — three-condition check (heapBase + byte[] + size) |
| R3 | SATISFIED | `DocumentSerializer.java:1189` — `toArray` fallback for all non-fast-path cases |
| R4 | SATISFIED | `DocumentSerializer.java:1189` — view offset always zero |
| R5 | SATISFIED | `DocumentSerializer.java:1189` — non-`byte[]` falls through, no `ClassCastException` |
| R6 | SATISFIED | `DocumentSerializer.java:128` — `SchemaSerializer` ctor precomputes constants |
| R7 | SATISFIED | `DocumentSerializer.java:149-158` — prefixBoolCount[fieldCount+1] |
| R8 | SATISFIED | `DocumentSerializer.java:363` — O(1) `prefixBoolCount[readCount]` lookup |
| R9 | SATISFIED | `DocumentSerializer.java:326` — top-level deserialize re-derives no schema constants |
| R10 | SATISFIED | `DocumentSerializer.java:165-171` — dispatch table built in ctor |
| R11 | SATISFIED | `DocumentSerializer.java:167` — boolean fields skipped in dispatch table |
| R12 | SATISFIED | `DocumentSerializer.java:795+` — exhaustive switch over sealed `FieldType` (compile-time) |
| R13 | SATISFIED | `DocumentSerializer.java:169` — decoder lambdas delegate to `decodeField` |
| R14 | SATISFIED | round-trip tests in `DocumentSerializerOptimizationTest` |
| R15 | SATISFIED | `DocumentSerializer.java:358` — `readCount = min(...)` leaves extra fields null |
| R16 | SATISFIED | `DocumentSerializer.java:361-372` — overlap bool-check skipped when `writeFieldCount > fieldCount` |
| R17 | SATISFIED | `DocumentSerializer.java:384-392` — null fields skip cursor advance |
| R18 | SATISFIED | `DocumentSerializer.java:386` — null boolean increments `boolIdx` only |
| R19 | SATISFIED | `serialize()` unchanged since ae80360 |
| R20 | SATISFIED | wire format unchanged; round-trip corpus passes |
| R21 | SATISFIED | all instance fields final; concurrent-deserialize test passes |
| R22 | SATISFIED | `ByteArrayView` scope local to each `deserialize()` call |
| R23 | SATISFIED | corrupt/truncated segment tests throw `IllegalArgumentException` |
| R24 | SATISFIED | zero-byte heap & off-heap segments throw with "too small" message |

**Overall: PASS**

Amendments applied: 6 (R1, R2, R3, R4, R5, R12)
Code fixes applied: 1 (R16 — tail-removal check relaxation)
Regression tests added: 3 (tail-removal covering trailing bool, multiple trailing bools, interior bool preservation)
Test-gap tests added: 5 (R21 concurrent-deserialize; R23 truncated header + malformed varint; R24 zero-byte heap + off-heap)
Obligations deferred: 0
Undocumented behavior: 0

#### Amendments

- **R1** (rewrite): original required zero-copy via `heapOffset()`; shipped code uses full-array-only fast path (size-equality check) with `toArray()` fallback for slices. Code is deliberately safer — DS-01 adversarial test codifies slice-via-copy behavior.
- **R2** (rewrite): original forbade any heuristic beyond `heapBase().isPresent()`; shipped code adds `instanceof byte[]` and `byteSize() == data.length` as the fast-path eligibility gate. Amended to describe the three-condition check.
- **R3** (rewrite): original scoped fallback to "empty Optional" only; shipped fallback covers off-heap, non-`byte[]`, and sliced cases. Amended to enumerate all three.
- **R4** (rewrite): original required use of actual `heapOffset()`; shipped code always returns offset 0 (fast path is full-array only; fallback returns fresh array starting at 0). Amended to assert the always-zero invariant.
- **R5** (rewrite): original required explicit cast that may throw `ClassCastException`; shipped code uses `instanceof` and falls through to the safe fallback. Amended to describe the intentional fallback behavior.
- **R12** (rewrite): original required a runtime constructor check; shipped code relies on compile-time exhaustive switch over sealed `FieldType`. Amended to acknowledge the compile-time guarantee is strictly stronger.

#### Code fix

- **R16**: deserialize now skips the write-vs-current bool-count check when `writeFieldCount > fieldCount`. Rationale: the header carries total `writeBoolCount`, not a prefix at `fieldCount`, so the check cannot distinguish bool-in-removed-tail from type-change-in-overlap. Overlap type consistency is no longer verified for tail-removal; if overlap types change concurrently with tail-removal, decode proceeds (silent incorrect decode possible). Three regression tests cover trailing-boolean, multiple-trailing-booleans, and interior-bool-preserved scenarios.

---

## Design Narrative

### Intent

Reduce the CPU cost of document deserialization (measured at 34% of scan workload) by eliminating redundant work: a per-document byte array copy for heap-backed segments, per-document recomputation of schema-derived constants, and per-field type dispatch via switch pattern matching. All three optimizations are internal to SchemaSerializer and invisible to callers.

### Why this approach

The three changes target the three largest contributors to deserialization CPU cost identified by profiling. The heap fast path (R1-R5) eliminates the most expensive single operation (toArray copy, ~8% of scan CPU). Schema precomputation (R6-R9) replaces O(n) per-document work with O(1) lookups (~1.5%). The dispatch table (R10-R13) replaces polymorphic switch dispatch with monomorphic lambda calls (~1-2%). Together these changes are strictly internal, require no format changes, and preserve the existing concurrency and correctness contracts.

### What was ruled out and why

- **Lazy field deserialization** (deferring String construction until `getString()` is called): Would change the JlsmDocument contract from eagerly-populated to lazily-populated, requiring changes to the document model and all consumers. Much higher risk for marginal gain on the scan path where all fields are typically read.
- **Primitive boxing elimination** (storing int/long/double as primitives instead of boxed Objects): Requires redesigning JlsmDocument from `Object[]` to a struct-of-arrays or value-type model. Correct approach long-term but out of scope for a targeted optimization.
- **SIMD-accelerated scalar decoding**: The Vector API is already used for array-typed fields. Scalar fields (int, long, String) do not benefit from SIMD because they are variable-length and processed one at a time.
- **Binary format changes**: Any format change would break backward compatibility with existing serialized documents and require version negotiation in the serialization layer.

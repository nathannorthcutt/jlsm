---
{
  "id": "F06",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["serialization"],
  "amends": [],
  "amended_by": [],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": []
}
---

# F06 — DocumentSerializer Deserialization Optimization

## Requirements

### Byte extraction and zero-copy heap path

R1. The deserializer must obtain a byte array view from a heap-backed MemorySegment by retrieving the backing array via `heapBase()` and the starting position via `heapOffset()`, without copying the segment contents into a new array.

R2. The deserializer must detect a heap-backed segment by checking whether `heapBase()` returns a non-empty Optional. No other heuristic (class name inspection, segment size, arena type) may be used for this determination.

R3. When `heapBase()` returns an empty Optional (off-heap, memory-mapped, or remote-backed segments), the deserializer must fall back to `toArray(ValueLayout.JAVA_BYTE)` to obtain a copied byte array, with the view offset set to zero.

R4. The deserializer must not assume that the heap offset is zero. A heap-backed segment created as a slice of a larger segment may have a non-zero heap offset, and the deserializer must use the actual offset for all subsequent byte-level reads.

R5. The deserializer must cast the object returned by `heapBase().get()` to `byte[]`. If a future JDK introduces heap-backed segments backed by a non-byte-array type, this cast will throw ClassCastException. This is an accepted known limitation; the cast must not be guarded with a silent fallback that hides the type mismatch.

### Schema constant precomputation

R6. The SchemaSerializer must compute the following values once at construction time from the provided JlsmSchema: field count, boolean field count, null-mask byte count, boolean-mask byte count, a boolean flag per field indicating whether it is a boolean type, and a prefix-sum array of boolean field counts.

R7. The prefix boolean count array must have length `fieldCount + 1`, where entry `i` contains the number of boolean-typed fields among positions 0 through i-1 (exclusive). Entry 0 must be 0. Entry `fieldCount` must equal the total boolean field count.

R8. The deserializer must use the prefix boolean count array for O(1) lookup of the number of boolean fields preceding any given field position. The per-call linear scan (`countBoolFieldsUpTo`) must not be invoked during deserialization.

R9. Schema constants must be derived solely from the JlsmSchema passed to the SchemaSerializer constructor. The deserializer must not re-derive any schema-level constant (field types, counts, mask sizes) during a `deserialize()` call.

### Field decoder dispatch table

R10. The SchemaSerializer must build a dispatch table (one decoder entry per field position) at construction time. Each entry must decode a single field type from a byte array and cursor position.

R11. Boolean fields must not have a dispatch table entry that reads from the variable-length data region. Boolean values must be decoded from the boolean bitmask, not through the dispatch table.

R12. The dispatch table must cover every non-boolean field type supported by the current serializer. If a schema contains a field type that is not boolean and has no corresponding decoder entry, the SchemaSerializer constructor must fail eagerly with an exception, not defer the error to the first `deserialize()` call.

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

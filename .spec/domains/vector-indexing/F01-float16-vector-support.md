---
{
  "id": "F01",
  "version": 2,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["vector-indexing", "serialization"],
  "amends": [],
  "amended_by": [],
  "requires": [],
  "invalidates": [],
  "decision_refs": ["vector-type-serialization-encoding"],
  "kb_refs": [
    "algorithms/vector-encoding/flat-vector-encoding",
    "algorithms/vector-encoding/precision-overflow-silent-data-loss",
    "algorithms/vector-encoding/non-finite-vector-element",
    "algorithms/vector-encoding/float16-vector-support"
  ],
  "open_obligations": []
}
---

# F01 — Float16 Vector Support

## Requirements

### Precision model

R1. The system must support exactly two vector storage precisions: FLOAT32 (4 bytes per component) and FLOAT16 (2 bytes per component).

R2. Every vector index must expose its configured precision. The precision must be queryable after construction and must never be null.

R3. Precision must be an explicit builder choice. The default must be FLOAT32. Null precision configuration must be rejected at build time.

R4. A vector index must operate at a single precision chosen at build time. No mixed-precision storage within a single index instance. The configured precision must not change after construction.

### Encoding and byte format

R5. Float16 encoding must convert each float32 component to IEEE 754 binary16 using the JDK standard conversion (not a custom implementation). The output must be big-endian bytes with length exactly dimensions * 2.

R6. Float16 decoding must convert big-endian IEEE 754 binary16 bytes back to float32 using the JDK standard conversion. Input length must equal dimensions * 2. The output must be a float32 array of the specified dimensions.

R7. Vector encoding must dispatch by precision: FLOAT32 produces dimensions * 4 bytes, FLOAT16 produces dimensions * 2 bytes. Decoding must accept a precision parameter and produce float32 values regardless of storage precision.

R8. All vector encoding must use big-endian byte order, matching the convention established in ADR vector-type-serialization-encoding. This byte order must not be changed without a format migration path for persisted data.

R9. Serialized vector data must not carry a precision marker or header byte. The encoding format is caller-described: the caller must provide the correct precision to decode. Decoding with the wrong precision produces undefined results with no runtime detection of mismatch.

### Precision boundary — no mixing

R10. Float16 and float32 are separate precision modes. The precision boundary applies at three layers independently:

R10a. Scores from vector indexes configured with different precisions are not directly comparable. Applications that query multiple indexes must not merge score-ranked results across precisions without explicit normalization.

R10b. For partitioned indexes (IVF-style), centroid assignment must use the quantized vector value (the result of encoding to the configured precision then decoding back to float32), not the original float32 input. Centroid coordinates must remain float32. The precision boundary is at the stored vector, not the centroid.

R10c. The table/storage layer stores raw full-fidelity vector data regardless of index precision. Float16 quantization applies only within the vector index layer. A float16 vector index does not imply float16 storage in the underlying data store.

### Input validation

R11. When precision is FLOAT16, vector indexing must validate all components before encoding. Any finite float32 value with magnitude greater than 65504 (the maximum finite float16 value) must be rejected. This prevents silent overflow to Infinity in float16 representation.

R12. Subnormal float32 values that flush to zero in float16 representation must be accepted without error. The precision loss (value becomes 0.0) is an inherent property of float16 quantization and is documented, not prevented.

R13. NaN and Infinity values in input vectors must be handled consistently with the non-finite vector element policy. If the document layer rejects non-finite values at construction time, the index layer must not encounter them. If the document layer permits them, the index layer must either reject them explicitly or document the behavior (NaN produces NaN scores; Infinity produces Infinity distances that corrupt ranking).

### Partitioned index (IVF-style) integration

R14. In a partitioned index, posting-list vectors must be stored at the configured precision. Centroid vectors must always be stored at float32 regardless of the index precision.

R15. Centroid assignment during indexing must use the quantized vector value (encode-then-decode through the configured precision), not the original float32 input. This ensures the stored vector is assigned to the centroid it is actually closest to after quantization.

R16. Search must decode posting-list vectors at the configured precision. The decoded float32 values must be used for distance computation. The distance computation path must not change between precisions — only the decode step differs.

R17. Search must use the original float32 query vector (not quantized) for all distance computations — both centroid selection and posting-list scoring. The asymmetry between float32 query and quantized stored vectors is intentional and matches standard ANN practice.

R18. Float16 posting-list storage must use exactly dimensions * 2 bytes per vector, achieving approximately 50% storage reduction compared to float32.

### Graph index (HNSW-style) integration

R19. Graph node serialization must use the configured precision for the vector portion. The node size must reflect dimensions * bytesPerComponent for the vector portion. Neighbor links (document identifiers and layer structure) must be unaffected by precision.

R20. Graph construction (neighbor selection and edge trimming) must use the quantized vector (decoded back to float32) for all distance computations, not the original float32 input. This ensures graph edges are optimized for the same precision that search queries will encounter.

R21. Graph node deserialization must validate that the remaining bytes (after parsing neighbor links) are exactly divisible by the precision's bytes-per-component. If not divisible, deserialization must fail with a runtime error (not an assertion), so corrupted data is detected regardless of runtime configuration.

R22. Float16 graph storage must reduce per-node size by exactly dimensions * 2 bytes (the delta between float32 and float16 vector portions). Neighbor link overhead must remain unchanged.

### Graph index mutation semantics

R23. When a graph index receives an indexing request for a document that already exists, the implementation must either (a) remove old bidirectional edges from all former neighbors before inserting the new node with fresh neighbor selection, or (b) document in the public API that re-indexing without a prior remove may degrade graph quality.

R24. Graph index removal must use soft-deletion. Soft-deleted nodes must remain traversable during graph search as waypoints for neighbor expansion but must be excluded from search results. This preserves graph connectivity after deletion.

### Search result integrity

R25. Search results must reject Infinity scores (both positive and negative) in addition to NaN. Infinity scores can arise from dot-product overflow on large-magnitude vectors and would always rank first, corrupting result ordering. The rejection must use a runtime check, not an assertion.

R25a. All search paths must filter out Infinity scores during candidate accumulation (alongside existing NaN filtering), preventing Infinity from reaching search result construction. Without this, enforcing R25 at the result level would crash the entire search call instead of gracefully excluding the invalid score.

### Cross-module encoding boundary

R26. The vector index layer must use exclusively JDK standard float16 conversion intrinsics. Any other float16 implementation in the codebase (e.g., a table serialization layer utility) is a separate implementation. No cross-layer assumption of bit-for-bit compatibility between implementations is permitted — they may differ in rounding behavior, NaN canonicalization, and subnormal handling.

### Score computation observability

R27. Vectors containing NaN components that are accepted into the index (per the non-finite policy in R13) produce NaN scores on every search computation. NaN scores must be excluded from search results. This is a silent degradation — the vector is stored but invisible to search with no error or diagnostic. This behavior must be documented in the public API.

### Thread safety

R28. Vector encoding and decoding operations must be stateless with no shared mutable state. They must be safe to call concurrently from multiple threads without synchronization.

### Distance computation

R29. Distance computation must always use float32 arithmetic regardless of storage precision. Float16 vectors must be decoded to float32 before any similarity computation. When native float16 SIMD becomes available in the JVM, the inner distance loop may be changed to native float16 SIMD without changing the storage format or the external API contract.

---

## Design Narrative

### Intent
Add IEEE 754 half-precision vector storage to achieve ~50% storage reduction per vector in both partitioned (IVF) and graph (HNSW) index implementations. Precision is an explicit builder choice — existing float32 indexes are completely unaffected. Float16 is storage-only quantization; computation always uses float32.

### Why this approach
Store-as-float16, compute-as-float32 is the standard approach in Java vector search (Lucene, FAISS Java bindings). Java 25 has no native float16 SIMD. The storage format is forward-compatible with future float16 SIMD — only the distance loop changes, not the encoding.

Centroids remain float32 in partitioned indexes because centroid quality directly affects recall. The precision loss from float16 centroids would change assignment boundaries for many vectors simultaneously, whereas float16 posting vectors affect only individual distance calculations.

Both partitioned and graph indexes must use the quantized vector (encode then decode through float16) for all internal distance computations during construction — centroid assignment (partitioned) and neighbor selection (graph). Using the original float32 input creates an asymmetry where the index structure is optimized for distances that differ from what search queries encounter. This was identified as a concrete bug pattern: using the float32 input for assignment misfiles borderline vectors and degrades recall.

No self-describing format (no precision header in encoded vectors) because: the precision is always known from the index configuration, adding a header changes the encoding boundary shared between index types, and the ADR for vector serialization specifies zero-overhead flat encoding.

### What was ruled out and why
- **Mixed-precision within a single index:** complexity of maintaining two decode paths in the hot loop, unclear benefit since the use case is "trade precision for storage" not "precision per vector."
- **Auto-selected precision:** violates the principle of explicit configuration. The recall impact of float16 depends on the data distribution and the application's tolerance — the user must choose.
- **Self-describing format with precision header byte:** changes the encoding contract shared between index types, adds 1 byte per vector overhead, and contradicts the flat encoding ADR.
- **Validation that rejects subnormals:** subnormal flush-to-zero is an inherent property of float16, not an error. Rejecting subnormals would make float16 unusable for data distributions with small values.

### Invalidated requirements
None.

## Verification Notes

---
problem: "vector-storage-cost-optimization"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/IndexDefinition.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/VectorFieldIndex.java"
---

# ADR — Vector Storage Cost Optimization

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Scalar Quantization | SQ8 — first implementation target | [`.kb/algorithms/vector-quantization/scalar-quantization.md`](../../.kb/algorithms/vector-quantization/scalar-quantization.md) |
| RaBitQ | Second implementation target, provable bounds | [`.kb/algorithms/vector-quantization/rabitq.md`](../../.kb/algorithms/vector-quantization/rabitq.md) |
| Binary Quantization | Third target, extreme compression | [`.kb/algorithms/vector-quantization/binary-quantization.md`](../../.kb/algorithms/vector-quantization/binary-quantization.md) |
| Vector Quantization (category) | Implementation priority ranking | [`.kb/algorithms/vector-quantization/CLAUDE.md`](../../.kb/algorithms/vector-quantization/CLAUDE.md) |

---

## Files Constrained by This Decision

- `IndexDefinition.java` — gains `QuantizationConfig quantization` field
- `IndexRegistry.java` — validates quantization config for VECTOR indices
- `VectorFieldIndex.java` — delegates to quantization policy for encode/decode
- `LsmVectorIndex.java` — IvfFlat/HNSW internals use quantized storage

## Problem
Flat vector encoding stores every element at full precision (4 bytes/float32).
At billion-scale (1B × 768-dim = 3TB), this is prohibitively expensive.
Quantization can reduce storage 4-32x with bounded recall loss. The question is
where quantization configuration lives in the architecture and which methods
to implement first.

## Constraints That Drove This Decision
- **Fit (weight 3)**: Quantization is a storage/index concern, not a type system
  concern. VectorType must stay unchanged — a float32 vector is the same logical
  type regardless of compression.
- **Accuracy (weight 3)**: Users must choose the accuracy/storage tradeoff. SQ8
  (97-99% recall, 4x compression) through BQ (50-70% recall, 32x compression).
- **Streaming compatibility (Operational, weight 2)**: Default quantization must
  not require offline codebook training. SQ8, RaBitQ, BQ are all streaming.

## Decision
**Chosen approach: QuantizationConfig on IndexDefinition + custom SPI escape hatch**

Add a `QuantizationConfig` sealed interface to IndexDefinition. Built-in options
for SQ8, BQ, and RaBitQ. A `Custom(QuantizationPolicy)` variant allows callers
to bring any quantization algorithm (PQ with trained codebooks, TurboQuant,
domain-specific schemes). VectorType is unchanged. jlsm ships built-in
implementations; callers extend via SPI.

## Rationale

### Why IndexDefinition config + custom SPI
- **Schema-visible**: Catalog, monitoring, and migration tools can introspect
  which quantization an index uses without querying the live index instance.
- **Batteries included**: SQ8, BQ, RaBitQ ship as built-in options — callers
  don't need to implement common algorithms.
- **Extensible**: `Custom(QuantizationPolicy)` means jlsm never blocks a caller
  from using a quantization scheme it doesn't ship. Same composability principle
  as BlobStore SPI.
- **Per-index**: Two indices on the same field can use different quantization
  (SQ8 for fast scan, flat for high-recall reranking).

### Why not QuantizedVectorType sealed permit
- Mixes type system with storage concern. A float32 vector is a float32 vector.
  Adds ~10 switch arms across jlsm-table/sql for no type-safety benefit.

### Why not pure builder policy
- Hides quantization from schema metadata. Catalog tools can't report "index X
  uses SQ8" without querying the live index.

### Why not pure SPI only
- No built-in implementations. Every caller must implement their own SQ8.
  Over-abstraction when the algorithms are well-known and pure-Java implementable.

## Implementation Guidance

### QuantizationConfig
```
sealed interface QuantizationConfig {
    record None() implements QuantizationConfig {}
    record ScalarInt8(boolean rotation) implements QuantizationConfig {}
    record Binary(int bits) implements QuantizationConfig {}
    record RaBitQ(int bits) implements QuantizationConfig {}
    record Custom(QuantizationPolicy policy) implements QuantizationConfig {}
}
```

### QuantizationPolicy SPI
```
interface QuantizationPolicy {
    MemorySegment quantize(MemorySegment vector, int dimensions);
    void dequantize(MemorySegment quantized, MemorySegment output, int dims);
    int quantizedSize(int dimensions);
    boolean requiresTraining();
}
```

### IndexDefinition change
```
record IndexDefinition(String fieldName, IndexType indexType,
    SimilarityFunction similarityFunction, QuantizationConfig quantization) {
    // Default: QuantizationConfig.None() for backward compatibility
}
```

### Implementation priority (from KB)
1. **SQ8** — 4x compression, 97-99% recall, ByteVector SIMD, streaming
2. **RaBitQ** — 28x compression, provable bounds, streaming, Long.bitCount SIMD
3. **BQ** — 32x compression, candidate generation tier, POPCNT distance

## What This Decision Does NOT Solve
- **Query routing between quantized indices** — IndexRegistry single-dispatch
  returns first matching index. No mechanism to choose "use SQ8 index for this
  query, flat for that query."
- **Automatic quantization selection** — choosing the right scheme based on data
  characteristics (dimension count, distribution, recall requirements).
- **Re-quantization during compaction** — changing an index's quantization scheme
  after creation without rebuilding.

## Conditions for Revision
This ADR should be re-evaluated if:
- IndexDefinition accumulates too many optional fields per IndexType — may need
  to refactor to a builder or sealed-config-per-type pattern
- A quantization method proves to need index-wide state (e.g., PQ codebooks)
  that doesn't fit the per-vector QuantizationPolicy interface
- TurboQuant or SAQ benchmarks show they should replace SQ8 as the default

---
*Confirmed by: user deliberation | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*

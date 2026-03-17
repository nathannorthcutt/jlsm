---
problem: "vector-type-serialization-encoding"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR-001 — Vector Type Serialization Encoding

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Flat Vector Encoding | Chosen approach | [`.kb/algorithms/vector-encoding/flat-vector-encoding.md`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md) |
| Sparse Vector Encoding | Rejected candidate | [`.kb/algorithms/vector-encoding/sparse-vector-encoding.md`](../../.kb/algorithms/vector-encoding/sparse-vector-encoding.md) |
| Lossless Vector Compression | Rejected candidate | [`.kb/algorithms/vector-encoding/lossless-vector-compression.md`](../../.kb/algorithms/vector-encoding/lossless-vector-compression.md) |

---

## Problem
Design the binary encoding for `FieldType.VectorType` in `DocumentSerializer`. Vectors have
fixed dimensions (known from schema) and element type FLOAT16 or FLOAT32. The encoding must
be lossless, prioritise deserialization speed, and work efficiently over remote I/O backends.

## Constraints That Drove This Decision
- **Deserialization speed**: Ranking algorithms read vectors far more often than they are written;
  zero decode overhead is the ideal
- **Lossless + FLOAT16**: Bit-exact round-trip is non-negotiable; the table layer stores raw
  full-fidelity data while vector indices handle quantization independently
- **Fit with existing patterns**: Must integrate with DocumentSerializer's MemorySegment-based
  encoding and SIMD byte-swap infrastructure

## Decision
**Chosen approach: [Flat Vector Encoding](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md)**

Store VectorType fields as `dimensions × sizeof(elementType)` contiguous bytes with no per-vector
metadata. Since dimensions are known from the schema, no length prefix is needed. Deserialization
is a direct memory read plus SIMD byte-swap for big-endian encoding. This keeps the serialization
layer simple and full-fidelity, leaving compression and quantization to the index layer.

## Rationale

### Why [Flat Vector Encoding](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md)
- **Deserialization speed**: Zero decode overhead — read `d × sizeof(T)` bytes and optionally
  byte-swap. Fastest possible path for ranking reads.
- **Lossless**: Raw byte storage is lossless by definition. No transform, no approximation.
- **Fit**: Directly matches DocumentSerializer's existing SIMD-accelerated float32/float64 array
  encoding. Same byte-swap shuffle, same MemorySegment bulk operations.
- **Remote I/O**: Single contiguous read — no multi-seek, no block decode.
- **No length prefix**: VectorType carries dimensions in the schema, so the serializer knows
  the exact byte count. This saves 1–5 bytes per vector vs ArrayType's VarInt prefix.

### Why not [Sparse Vector Encoding](../../.kb/algorithms/vector-encoding/sparse-vector-encoding.md)
- **Variable output size**: Contradicts VectorType's fixed-dimension invariant. Encoded size
  depends on data content, breaking predictable I/O and buffer pre-allocation.

### Why not [Lossless Vector Compression](../../.kb/algorithms/vector-encoding/lossless-vector-compression.md)
- **Decode overhead**: Always slower than flat's zero-decode path. Ranking workloads read
  thousands of vectors per query — even 2–10 GB/s decompression adds measurable latency vs
  zero-decode. Also requires external entropy coding dependency.

## Implementation Guidance
Key parameters from [`flat-vector-encoding.md#key-parameters`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md#key-parameters):
- `dimensions`: from `VectorType.dimensions()` in schema — do not encode per-vector
- `element_width`: 2 bytes for FLOAT16, 4 bytes for FLOAT32
- `byte_order`: big-endian (matching existing DocumentSerializer convention)
- `alignment`: unaligned writes into byte[] buffer (matching existing pattern)

Known edge cases from [`flat-vector-encoding.md#edge-cases-and-gotchas`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md#edge-cases-and-gotchas):
- Float16 stored as raw `short` (IEEE 754 bits), not converted to float during serialization
- No null elements within a vector — the vector as a whole may be null (document-level null bitmask)
- SIMD byte-swap shuffle for FLOAT32; scalar 2-byte swap for FLOAT16

SIMD acceleration paths:
- FLOAT32: reuse existing `encodeFloat32Array()` / `decodeFloat32Array()` SIMD paths
- FLOAT16: scalar loop (2-byte big-endian short writes), consistent with current FLOAT16 array handling

Full implementation detail: [`.kb/algorithms/vector-encoding/flat-vector-encoding.md`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md)
Code scaffold: [`flat-vector-encoding.md#code-skeleton`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md#code-skeleton)

## What This Decision Does NOT Solve
- Storage cost optimisation at billion-scale — flat encoding stores every element at full precision.
  This is intentional: the table layer stores raw data; vector indices handle quantization.
- Sparse vector use cases — VectorType is for dense fixed-dimension vectors. Sparse representations
  should use a different mechanism if needed in the future.

## Conditions for Revision
This ADR should be re-evaluated if:
- Remote I/O latency dominates ranking time and SSTable-level block compression is insufficient
- A FLOAT16 SIMD byte-swap path becomes available in the JVM, changing the FLOAT16 encode/decode
  performance profile
- A new use case requires inline compression at the field level (not SSTable block level)

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*

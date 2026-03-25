---
title: "float16-vector-support"
type: feature-footprint
domains: ["vector-encoding", "lsm-index-patterns"]
constructs: ["VectorPrecision", "VectorIndex", "LsmVectorIndex.IvfFlat", "LsmVectorIndex.Hnsw"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/indexing/VectorPrecision.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/indexing/VectorIndex.java"
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
research_status: stable
last_researched: "2026-03-25"
---

# float16-vector-support

## What it built
Added IEEE 754 binary16 (half-precision) vector storage to both IvfFlat and
Hnsw implementations. Vectors accepted as float[] at the API, quantized to
float16 on write, decoded to float32 for SIMD distance computation. ~50%
storage reduction per vector component. Precision is an explicit builder choice
via VectorPrecision enum.

## Key constructs
- `VectorPrecision` — enum (FLOAT32/FLOAT16) with bytesPerComponent()
- `VectorIndex.precision()` — interface method exposing configured precision
- `LsmVectorIndex.encodeFloat16s/decodeFloat16s` — float[] <-> big-endian float16 bytes
- `LsmVectorIndex.encodeVector/decodeVector` — precision-dispatched encode/decode

## Adversarial findings
- composite-key-reindex-orphan: IvfFlat.index() left orphan posting under old centroid on re-index -> [KB entry](../../systems/lsm-index-patterns/composite-key-reindex-orphan.md)
- soft-delete-reindex-tombstone: Hnsw.index() did not clear soft-delete tombstone after remove(), making re-indexed docs invisible -> [KB entry](../../systems/lsm-index-patterns/soft-delete-reindex-tombstone.md)
- nan-score-ordering-corruption: NaN scores from NaN/overflow vectors corrupted PriorityQueue ordering in search -> [KB entry](../../systems/lsm-index-patterns/nan-score-ordering-corruption.md)
- precision-overflow-silent-data-loss: Float32 values > 65504 overflowed to Infinity in float16, making vectors invisible in cosine search -> [KB entry](precision-overflow-silent-data-loss.md)

## Cross-references
- KB: algorithms/vector-encoding/flat-vector-encoding.md (float32 baseline)
- KB: algorithms/vector-quantization/ (quantization research)
- Related features: none (standalone)

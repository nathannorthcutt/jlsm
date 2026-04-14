---
problem: "How to add quantized vector storage to jlsm-vector"
slug: "vector-storage-cost-optimization"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — vector-storage-cost-optimization

## Problem Statement
Flat vector encoding stores every element at full precision (4 bytes/float32,
2 bytes/float16). At billion-scale, this is prohibitively expensive. Quantization
(SQ8, RaBitQ, BQ) can reduce storage 4-32x with bounded recall loss. The question
is where quantization configuration lives in the architecture.

## Constraints

### Scale
Billion-vector workloads. 768-dim float32 = 3KB/vector flat. At 1B vectors = 3TB.
SQ8 reduces to 768B = 768GB. The compression ratio directly determines feasibility.

### Resources
Pure Java 25. SIMD via jdk.incubator.vector (Vector API). No GPU. Must work within
ArenaBufferPool memory budget.

### Complexity Budget
Weight 1. Expert team. KB has implementation pseudocode for all quantization methods.

### Accuracy / Correctness
Quantization is lossy by design. SQ8: 97-99% recall. BQ: 50-70% recall without
rescoring. The user must choose the accuracy/storage tradeoff — the system must
make this configurable, not hardcoded.

### Operational Requirements
Streaming-compatible quantization required (no offline codebook training for the
default path). SQ8 and RaBitQ are streaming. PQ/RQ require batch training.

### Fit
Must integrate with existing IvfFlat and HNSW implementations. VectorType is
`record VectorType(Primitive elementType, int dimensions)` — the logical type
should not change. Quantization is a storage/index concern, not a type system
concern.

## Key Constraints (most narrowing)
1. **Streaming compatibility** — default quantization must not require codebook
   training, ruling out PQ/RQ as the first implementation
2. **Configurable accuracy** — users choose the tradeoff, not the library
3. **Fit with existing index architecture** — quantization is index-level config,
   not type-level

## Unknown / Not Specified
None — KB coverage is comprehensive.

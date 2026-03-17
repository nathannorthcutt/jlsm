---
problem: "vector-type-serialization-encoding"
evaluated: "2026-03-17"
candidates:
  - path: ".kb/algorithms/vector-encoding/flat-vector-encoding.md"
    name: "Flat Vector Encoding"
  - path: ".kb/algorithms/vector-encoding/sparse-vector-encoding.md"
    name: "Sparse Vector Encoding"
  - path: ".kb/algorithms/vector-encoding/lossless-vector-compression.md"
    name: "Lossless Vector Compression"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — vector-type-serialization-encoding

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The encoding must be lossless for both FLOAT16 and FLOAT32, prioritise deserialization speed
over serialization, minimise memory usage in cloud environments, and work efficiently over
remote I/O (single contiguous reads preferred). Complexity is unconstrained — sophisticated
approaches are acceptable if they deliver on the resource and operational dimensions.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Billions of docs × 32–4096 dims; overhead per vector matters but is secondary to speed |
| Resources | 3 | Memory is most cost-prohibitive in cloud; off-heap allocation required |
| Complexity | 1 | No constraint — complexity acceptable for critical data path |
| Accuracy | 3 | Lossless is non-negotiable; FLOAT16 must bit-exact round-trip |
| Operational | 3 | Deserialization speed is highest priority; ranking reads vectors constantly |
| Fit | 2 | Must work with MemorySegment, SIMD byte-swap, existing DocumentSerializer patterns |

---

## Candidate: Flat Vector Encoding

**KB source:** [`.kb/algorithms/vector-encoding/flat-vector-encoding.md`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md)
**Relevant sections read:** `#complexity-analysis`, `#tradeoffs`, `#implementation-notes`, `#edge-cases-and-gotchas`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Zero overhead per vector (`#memory-footprint`); no length prefix since dims from schema |
| Resources | 3 | 3 | 9 | No compression = full-size storage, but zero transient allocs during decode (`#build-phase`) |
| Complexity | 1 | 5 | 5 | Simplest format — no parsing, no metadata (`#tradeoffs #strengths`) |
| Accuracy | 3 | 5 | 15 | Lossless by definition — raw bytes, bit-exact (`#summary`) |
| Operational | 3 | 5 | 15 | Zero decode overhead, O(d/SIMD_width) with byte-swap (`#query-phase`) |
| Fit | 2 | 5 | 10 | Direct match for MemorySegment bulk ops, SIMD shuffle, existing patterns (`#code-skeleton`) |
| **Total** | | | **62** | |

**Hard disqualifiers:** None

**Key strengths for this problem:**
- Zero decode overhead — deserialization is a direct memory read + optional byte-swap (`#tradeoffs #strengths`)
- Single contiguous read for remote I/O — entire vector is one `d × sizeof(T)` byte span (`#edge-cases-and-gotchas`)
- No per-vector metadata — dimensions known from schema, eliminating VarInt prefix (`#how-it-works`)
- SIMD byte-swap amortises big-endian cost on little-endian hardware (`#algorithm-steps`)

**Key weaknesses for this problem:**
- No compression — larger storage and I/O at billion-scale (`#tradeoffs #weaknesses`)
- Sparse data wastes space — zeros stored at full cost (`#tradeoffs #weaknesses`)

---

## Candidate: Sparse Vector Encoding

**KB source:** [`.kb/algorithms/vector-encoding/sparse-vector-encoding.md`](../../.kb/algorithms/vector-encoding/sparse-vector-encoding.md)
**Relevant sections read:** `#complexity-analysis`, `#tradeoffs`, `#break-even-analysis`, `#edge-cases-and-gotchas`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 3 | 6 | Saves space only at >50% sparsity (COO); adds overhead when dense (`#break-even-analysis`) |
| Resources | 3 | 3 | 9 | Variable-size encoding complicates buffer pre-allocation (`#data-structure-requirements`) |
| Complexity | 1 | 3 | 3 | COO is moderate; bitmap requires popcount logic (`#algorithm-steps`) |
| Accuracy | 3 | 4 | 12 | Lossless for values stored, but IEEE -0.0 vs +0.0 is a gotcha (`#edge-cases-and-gotchas`) |
| Operational | 3 | 2 | 6 | Decode requires scatter writes, not SIMD-friendly, slower than flat (`#tradeoffs #weaknesses`) |
| Fit | 2 | 2 | 4 | Does not match DocumentSerializer's contiguous byte[] pattern (`#code-skeleton`) |
| **Total** | | | **40** | |

**Hard disqualifiers:**
- Variable-size encoding means vector size depends on data content, breaking the fixed-size
  guarantee that VectorType is designed to provide (`#how-it-works`)

**Key strengths for this problem:**
- Massive savings for truly sparse data (>95% zeros) (`#tradeoffs #strengths`)

**Key weaknesses for this problem:**
- Dense neural embeddings rarely benefit — near-zero sparsity (`#edge-cases-and-gotchas`)
- Not SIMD-friendly — scattered indices prevent contiguous loads (`#tradeoffs #weaknesses`)
- Variable output size contradicts VectorType's fixed-dimension invariant

---

## Candidate: Lossless Vector Compression

**KB source:** [`.kb/algorithms/vector-encoding/lossless-vector-compression.md`](../../.kb/algorithms/vector-encoding/lossless-vector-compression.md)
**Relevant sections read:** `#complexity-analysis`, `#tradeoffs`, `#float16-considerations`, `#block-size-vs-random-access`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | 30–60% size reduction helps at billion-scale (`#memory-footprint`) |
| Resources | 3 | 4 | 12 | Reduced storage/memory footprint from compression (`#tradeoffs #strengths`) |
| Complexity | 1 | 3 | 3 | Byte-split + entropy coder requires additional infrastructure (`#data-structure-requirements`) |
| Accuracy | 3 | 5 | 15 | Bit-exact lossless — handles NaN, Inf, subnormals (`#edge-cases-and-gotchas`) |
| Operational | 3 | 2 | 6 | Decode overhead always present; block granularity hurts random access (`#query-phase`) |
| Fit | 2 | 2 | 4 | Requires entropy coding library (zstd/LZ4), block index management (`#data-structure-requirements`) |
| **Total** | | | **48** | |

**Hard disqualifiers:** None, but:
- Float16 compression yields diminishing returns — only 2 byte groups (`#float16-considerations`)
- Block-level decode amplification conflicts with per-vector ranking reads (`#block-size-vs-random-access`)

**Key strengths for this problem:**
- Significant I/O reduction for remote backends (`#tradeoffs #strengths`)
- Bit-exact round-trip for all IEEE 754 values (`#edge-cases-and-gotchas`)

**Key weaknesses for this problem:**
- Decode overhead always slower than flat's zero-decode path (`#query-phase`)
- Float16 benefits are marginal — already compact, only 2 byte groups to split (`#float16-considerations`)
- Requires entropy coding dependency — conflicts with "no external runtime dependencies" (`#reference-implementations`)

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [Flat](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md) | | 8 | 9 | 5 | 15 | 15 | 10 | **62** |
| [Sparse](../../.kb/algorithms/vector-encoding/sparse-vector-encoding.md) | | 6 | 9 | 3 | 12 | 6 | 4 | **40** |
| [Compression](../../.kb/algorithms/vector-encoding/lossless-vector-compression.md) | | 8 | 12 | 3 | 15 | 6 | 4 | **48** |

## Preliminary Recommendation
Flat Vector Encoding wins on weighted total (62 vs 48 vs 40). The decisive factors are
the Operational and Fit dimensions — zero decode overhead and direct compatibility with
existing DocumentSerializer patterns outweigh compression's resource savings.

## Risks and Open Questions
- **Risk**: If remote I/O becomes the dominant bottleneck at scale, compression's 30–60% I/O
  reduction could outweigh its decode cost. This would flip the Operational scoring.
- **Risk**: If sparse embeddings become a primary use case, flat encoding wastes significant
  space. However, VectorType is designed for dense fixed-dimension vectors.
- **Open**: A future hybrid approach (flat for hot data, compressed for cold/archival) could
  capture both candidates' strengths. This is outside current scope.

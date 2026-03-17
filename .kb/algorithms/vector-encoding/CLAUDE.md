# Vector Encoding — Category Index
*Topic: algorithms*

Serialization formats and lossless encoding schemes for fixed-dimension float16/float32
vector arrays. Covers the spectrum from flat (zero overhead, zero-copy) to sparse-aware
(index+value pairs) to compressed (byte-split + entropy coding). Key considerations for
jlsm-table: lossless round-trip, deserialization speed, remote I/O efficiency, and
SIMD compatibility.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [flat-vector-encoding.md](flat-vector-encoding.md) | Flat contiguous encoding | stable | O(1) access, 0 overhead | Dense vectors, speed-critical reads |
| [sparse-vector-encoding.md](sparse-vector-encoding.md) | COO / bitmap sparse encoding | mature | O(nnz) storage | Vectors with >50% zeros |
| [lossless-vector-compression.md](lossless-vector-compression.md) | Byte-split + entropy coding | active | 30–60% reduction | I/O-bound workloads, archival |

## Comparison Summary

**Flat encoding** is the baseline: zero overhead, zero decode cost, SIMD-friendly. Best
when deserialization speed matters and storage/bandwidth is not the bottleneck.

**Sparse encoding** saves space only when vectors have significant zero content (>50% for
COO, >3–12% for bitmap depending on dimensions). Dense neural embeddings rarely benefit.

**Lossless compression** achieves 30–60% size reduction at the cost of decode latency.
Beneficial when I/O (especially remote) is the bottleneck, but adds block-level granularity
constraints and implementation complexity. Float16 data benefits less than float32.

**Decision framework:**
- Dense vectors + local I/O → flat encoding
- Dense vectors + remote I/O → flat encoding (simplicity) or byte-split compression (if I/O dominates)
- Sparse vectors (>50% zeros) → sparse COO/bitmap encoding
- Archival / cold storage → lossless compression

## Recommended Reading Order
1. Start: [flat-vector-encoding.md](flat-vector-encoding.md) — baseline format
2. Then: [sparse-vector-encoding.md](sparse-vector-encoding.md) — when sparsity matters
3. Then: [lossless-vector-compression.md](lossless-vector-compression.md) — advanced compression

## Research Gaps
- Adaptive format selection (auto-detect sparse vs dense per block)
- SIMD-accelerated byte transposition benchmarks on JVM (Panama Vector API)
- Float16-specific compression techniques

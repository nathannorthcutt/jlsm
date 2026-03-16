# Vector Quantization — Category Index
*Topic: algorithms*

Compression techniques for high-dimensional vectors in ANN search. Covers the spectrum
from simple scalar mapping (4x compression, no training) to multi-codebook methods
(128-512x compression, batch training required). Key considerations for jlsm-vector:
streaming compatibility, Java SIMD support, and LSM-tree alignment.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [scalar-quantization.md](scalar-quantization.md) | INT8/INT4 + Rotational SQ | mature | 4x compression, <3% recall loss | First implementation, streaming, SIMD |
| [binary-quantization.md](binary-quantization.md) | 1-bit / 1.5-bit / 2-bit BQ | active | 32x compression, POPCNT distance | High-dim embeddings, candidate generation |
| [rabitq.md](rabitq.md) | RaBitQ + Extended RaBitQ + SAQ | active | 28x compression, provable bounds | High-dim, streaming, formal guarantees |
| [product-quantization.md](product-quantization.md) | PQ / OPQ / IVF-PQ / FastScan | mature | 64x compression, ADC lookup | Billion-scale batch indexing, IVF-PQ pipelines |
| [residual-quantization.md](residual-quantization.md) | RQ / AQ / LSQ / QINCo2 | active | 5-20% better recall than PQ | Max accuracy per byte, batch indexing |

## Comparison Summary

**Streaming-compatible (no codebook training):** Scalar quantization and binary quantization
are fully streaming — each vector quantized independently. RaBitQ is also streaming (fixed
rotation matrix). These three are the most relevant for jlsm-vector's LSM-tree architecture.

**Batch-only (codebook training required):** Product quantization is the foundational
multi-codebook method — 64x compression with moderate standalone recall (50-60%), but excellent
when combined with IVF and reranking. Residual/additive quantization builds on PQ and achieves
5-20% better recall at the same code size but is 10-100x slower to encode.

**Compression vs recall spectrum:**

| Method | Compression | Recall (no rescore) | Streaming? | Java SIMD |
|--------|-------------|--------------------|-----------|-----------|
| SQ8 | 4x | 97-99% | Yes | ByteVector |
| SQ4 | 8x | 90-95% | Yes | ByteVector |
| RaBitQ 1-bit | ~28x | 80-90% | Yes | Long.bitCount |
| Binary 1-bit | 32x | 50-70% | Yes | Long.bitCount |
| PQ (M=8) | 64x | 50-60% | No | Lookup table |
| RQ (M=8) | 512x | 70-85% | No | Lookup table |

**Implementation priority for jlsm-vector:**
1. SQ8 (+ WHT rotation) — highest ROI, simplest
2. RaBitQ — novel, provable bounds, streaming
3. Binary quantization — extreme compression for candidate generation
4. Residual quantization — advanced, batch-only

## Recommended Reading Order
1. Start: [scalar-quantization.md](scalar-quantization.md) — simplest, production standard
2. Then: [binary-quantization.md](binary-quantization.md) — extreme compression tradeoffs
3. Then: [rabitq.md](rabitq.md) — novel technique with formal guarantees
4. Then: [product-quantization.md](product-quantization.md) — foundational multi-codebook method
5. Then: [residual-quantization.md](residual-quantization.md) — maximum accuracy per byte

## Research Gaps
- Matryoshka Representation Learning (MRL) — embedding-level, not index-level
- Hardware-specific quantization (GPU tensor cores, AMX)
- Quantization-aware embedding training (QAT)

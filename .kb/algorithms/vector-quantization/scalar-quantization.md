---
title: "Scalar Quantization (INT8/INT4) and Rotational SQ"
aliases: ["scalar-quantization", "sq8", "sq4", "int8-quantization", "rotational-quantization"]
topic: "algorithms"
category: "vector-quantization"
tags: ["quantization", "scalar", "int8", "simd", "streaming"]
complexity:
  time_build: "O(N * D) range computation + O(N * D) quantization"
  time_query: "O(D) SIMD int8 dot product"
  space: "O(N * D * B/8) + O(N * 8) range metadata"
research_status: "mature"
last_researched: "2026-03-16"
sources:
  - url: "https://weaviate.io/blog/8-bit-rotational-quantization"
    title: "Weaviate 8-bit Rotational Quantization"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://www.clemsau.com/posts/scalar-quantization-making-vector-search-lean-and-fast/"
    title: "Scalar Quantization Deep Dive"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://qdrant.tech/documentation/guides/quantization/"
    title: "Qdrant Quantization Guide"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://arxiv.org/abs/2402.02044"
    title: "Locally-Adaptive Quantization for Streaming Vector Search"
    accessed: "2026-03-16"
    type: "paper"
---

# Scalar Quantization (INT8/INT4) and Rotational SQ

## summary

Maps each float32 dimension independently to an integer (int8 = 1 byte, int4 = 0.5 bytes)
using linear range mapping. The industry standard for production vector search due to
simplicity, minimal recall loss (1-3% for int8), and excellent SIMD acceleration. Weaviate's
8-bit Rotational Quantization (RQ, 2025) applies a Walsh-Hadamard Transform before SQ8,
improving quality without training. LVQ (Locally-adaptive Vector Quantization) extends SQ
for streaming scenarios with per-vector statistics. **Highest implementation priority for
jlsm-vector**: simple, fully streaming, excellent Java Vector API support via `ByteVector`.

## how-it-works

**Standard SQ8:**
1. Compute per-dimension (or global) min/max across training data
2. For each vector x, each dimension i: `q[i] = round((x[i] - min[i]) / (max[i] - min[i]) * 255)`
3. Store q as byte array + per-vector or per-dimension min/max for reconstruction
4. Distance: SIMD int8 dot product with correction factors

**Rotational SQ8 (Weaviate RQ):**
1. Apply Walsh-Hadamard Transform (WHT): fast pseudo-random rotation, O(D log D)
2. Per-vector scalar quantization: map each rotated dimension to uint8 using the vector's own min/max
3. The rotation spreads information across dimensions, so per-vector range is tighter → less quantization error

**LVQ (Locally-Adaptive Vector Quantization):**
Per-vector quantization using local statistics. Turbo LVQ: optimized distance computation
(28% faster). Multi-means LVQ: multiple mean estimators per vector (27% better recall).
Designed specifically for streaming updates.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| bits | Quantization precision | 4, 6, 8 | 8 = <3% recall loss; 4 = noticeable degradation |
| range_type | Global, per-dimension, or per-vector | per-dimension typical | Per-vector = best quality, more metadata |
| rotation | None, WHT, random orthogonal | WHT preferred | WHT improves quality with no training |
| symmetric | Symmetric vs asymmetric quantization | asymmetric | Asymmetric = slightly better accuracy |

## algorithm-steps

1. **Train (optional):** Scan dataset for per-dimension min/max. For WHT variant: compute transform factors (one-time O(D log D))
2. **Quantize(x):** (Optional) rotate via WHT. Compute per-vector min/max on rotated values. Linear map each dimension to [0, 2^B-1]. Store as byte/nibble array + 2 floats (min, scale)
3. **Distance(q_float, x_quantized):** Quantize query with same range. SIMD int8 dot product. Apply correction: `dist_approx = scale * raw_int_dot + offset`
4. **Rescore (optional):** For top candidates, recompute with full-precision vectors

## implementation-notes

### data-structure-requirements
- Per-vector: D bytes (int8) or D/2 bytes (int4) + 8 bytes (min + scale floats)
- Per-dimension range (alternative): 2 * D floats global, shared across all vectors
- WHT factors: O(D) precomputed butterfly constants

### edge-cases-and-gotchas
- **Outlier dimensions:** Global or per-dimension range is sensitive to outliers — a single extreme value wastes quantization range for all vectors. Per-vector range avoids this.
- **INT4 clipping:** Aggressive quantization; 4-bit range [0,15] loses fine-grained distinctions. Works best with rotation pre-processing.
- **WHT dimension rounding:** WHT requires D to be a power of 2; pad to nearest power of 2 (e.g., 1536 → 2048). Weaviate rounds to nearest multiple of 64.
- **Distance type matters:** For cosine similarity, normalize vectors first; for L2, use asymmetric distance (float query vs int8 stored) for better accuracy.

## complexity-analysis

### build-phase
Range computation: O(N * D) single pass. Quantization: O(N * D). WHT rotation: O(N * D log D). Total: O(N * D log D) with rotation, O(N * D) without.

### query-phase
SIMD int8 dot product: O(D) with hardware acceleration. Java `ByteVector.SPECIES_PREFERRED` processes 16-64 bytes per SIMD instruction. 2-3x faster than float32 distance.

### memory-footprint
| Config | Per-vector (D=1024) | Compression |
|--------|-------------------|-------------|
| SQ8 per-dim range | 1024 + 8 = 1032 bytes | 4x |
| SQ4 per-dim range | 512 + 8 = 520 bytes | 7.8x |
| SQ8 + WHT (D=1024) | 1024 + 8 = 1032 bytes | 4x |

## tradeoffs

### strengths
- Simplest quantization to implement — linear mapping, no codebooks
- Minimal recall loss: <3% for int8, recoverable with rescoring
- Fully streaming: each vector quantized independently (especially per-vector range)
- Excellent SIMD support: native int8 ops on all modern CPUs, Java `ByteVector`
- WHT rotation improves quality without training — deterministic, fast
- LVQ variant designed specifically for streaming/evolving datasets
- Production-proven: Qdrant, Weaviate, Azure AI Search, SQL Server 2025

### weaknesses
- Limited compression: 4x (int8) or 8x (int4) vs 32x (binary/RaBitQ)
- INT4 causes noticeable recall degradation without rotation
- Per-dimension range requires a training/calibration pass
- No provable error bounds (unlike RaBitQ)

### compared-to-alternatives
- vs [rabitq](rabitq.md): SQ is simpler but achieves lower compression; RaBitQ has provable bounds. WHT+SQ8 approaches RaBitQ quality.
- vs [binary-quantization](binary-quantization.md): SQ retains much more precision (8 bits vs 1 bit); BQ achieves 32x vs 4x compression
- vs [residual-quantization](residual-quantization.md): SQ needs no codebook training; RQ achieves better accuracy at same byte budget but requires batch training

## practical-usage

### when-to-use
- First quantization to implement in any vector library (highest ROI)
- When 4x compression is sufficient and minimal recall loss is required
- Streaming/online workloads where vectors arrive continuously
- Java implementation: `jdk.incubator.vector.ByteVector` for SIMD int8 distance

### when-not-to-use
- When >4x compression is needed (use RaBitQ or binary quantization)
- Very low-dimensional vectors where 8-bit precision is coarser than needed

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Weaviate RQ | Go | https://github.com/weaviate/weaviate | Active |
| Qdrant SQ | Rust | https://github.com/qdrant/qdrant | Active |
| FAISS SQ | C++/Python | https://github.com/facebookresearch/faiss | Active |
| SVS (LVQ) | C++ | https://github.com/intel/ScalableVectorSearch | Active |

## code-skeleton

```java
class ScalarQuantizer {
    private final int bits; // 4 or 8

    record QuantizedVector(byte[] codes, float min, float scale) {}

    QuantizedVector quantize(float[] vector) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : vector) { min = Math.min(min, v); max = Math.max(max, v); }
        float scale = (max - min) / ((1 << bits) - 1);
        byte[] codes = new byte[vector.length * bits / 8];
        for (int i = 0; i < vector.length; i++) {
            int q = Math.round((vector[i] - min) / scale);
            setBits(codes, i, q, bits);
        }
        return new QuantizedVector(codes, min, scale);
    }

    float distance(float[] query, QuantizedVector stored) {
        // SIMD int8 dot product via ByteVector.SPECIES_PREFERRED
        float dot = 0;
        for (int i = 0; i < query.length; i++)
            dot += query[i] * (getBits(stored.codes(), i, bits) * stored.scale() + stored.min());
        return dot;
    }
}
```

## sources

1. [Weaviate 8-bit Rotational Quantization](https://weaviate.io/blog/8-bit-rotational-quantization) — WHT + SQ8 production implementation
2. [LVQ for Streaming Vector Search](https://arxiv.org/abs/2402.02044) — per-vector quantization for evolving datasets
3. [Qdrant Quantization Guide](https://qdrant.tech/documentation/guides/quantization/) — production SQ8 configuration
4. [Scalar Quantization Deep Dive](https://www.clemsau.com/posts/scalar-quantization-making-vector-search-lean-and-fast/) — implementation details

---
*Researched: 2026-03-16 | Next review: 2026-09-12*

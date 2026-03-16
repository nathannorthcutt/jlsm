---
title: "RaBitQ and Extended RaBitQ"
aliases: ["rabitq", "randomized-binary-quantization", "extended-rabitq"]
topic: "algorithms"
category: "vector-quantization"
tags: ["quantization", "binary", "provable-bounds", "simd", "rotation"]
complexity:
  time_build: "O(D^2) rotation + O(N*D) quantization"
  time_query: "O(D/64) via POPCNT (1-bit) or FastScan (B-bit)"
  space: "O(N * B * D / 8) + O(N * 16) auxiliary"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://arxiv.org/abs/2405.12497"
    title: "RaBitQ: Quantizing High-Dimensional Vectors with a Theoretical Error Bound"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2409.09913"
    title: "Practical and Asymptotically Optimal Quantization (Extended RaBitQ)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2509.12086"
    title: "SAQ: Pushing the Limits of Vector Quantization"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://lancedb.com/blog/feature-rabitq-quantization/"
    title: "LanceDB RaBitQ Integration"
    accessed: "2026-03-16"
    type: "blog"
---

# RaBitQ and Extended RaBitQ

## summary

RaBitQ (Randomized Binary Quantization, SIGMOD 2024) quantizes D-dimensional float32
vectors into D-bit binary strings via a random orthogonal rotation, achieving 32x
compression with provable theoretical error bounds — unlike PQ which has no formal
guarantees. Extended RaBitQ (SIGMOD 2025) generalizes to B bits per dimension (2-6 bits)
with tunable compression, outperforming PQ at the same bit budget. SAQ (SIGMOD 2026)
further improves on Extended RaBitQ with 80% lower quantization error via PCA-based
dimension segmentation and code adjustment. All three are highly relevant to jlsm-vector:
no codebook training, streaming-compatible, and SIMD-friendly via POPCNT/FastScan.

## how-it-works

**RaBitQ (1-bit):**
1. Sample a random orthogonal matrix P (or use Walsh-Hadamard Transform for O(D log D))
2. For each vector x: compute x_rotated = P^(-1) * x
3. Quantize: each dimension → sign bit (1 if >= 0, 0 otherwise)
4. Store auxiliary scalars per vector: norm, centroid distance (for unbiased distance estimation)
5. Distance computation: Hamming distance via POPCNT correlates with inner product in rotated space

**Extended RaBitQ (B-bit):**
Same rotation, but quantize each dimension to B bits using uniform scalar quantization.
Uses FastScan (same as PQ's fast distance computation) for SIMD-accelerated scanning.
Achieves asymptotic optimality in the space-vs-error tradeoff.

**SAQ (code adjustment + dimension segmentation):**
1. PCA projection concentrates variance in leading dimensions
2. Dynamic programming allocates more bits to high-variance segments
3. Coordinate-descent refinement of quantization codes (avoids exhaustive enumeration)
Result: 80% lower error than Extended RaBitQ, 80x faster encoding.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| B (bits/dim) | Bits per dimension | 1 (RaBitQ), 2-6 (Extended) | Higher = better accuracy, less compression |
| D | Vector dimensionality | 512-3072 | RaBitQ best above 512 dims |
| Rotation type | Random orthogonal or WHT | WHT preferred | WHT is O(D log D) vs O(D^2) |
| Segments (SAQ) | PCA dimension groups | 4-16 | More = finer bit allocation |

## algorithm-steps

1. **One-time setup:** Generate random orthogonal matrix P (or compute WHT butterfly factors)
2. **Quantize(x):** Rotate x_r = P^(-1) * x; for each dim i: code_i = quantize(x_r[i], B bits); store auxiliary (norm, centroid_dist)
3. **Build distance table (query):** Rotate query q_r = P^(-1) * q; precompute per-dimension lookup tables
4. **Distance(q, code):** 1-bit: Hamming via POPCNT + auxiliary correction. B-bit: FastScan lookup-add
5. **Rescore (optional):** Re-rank top candidates with full-precision vectors for higher recall

## implementation-notes

### data-structure-requirements
- Rotation matrix: D * D floats (or WHT factors: O(D log D) — much smaller)
- Per-vector: B * D / 8 bytes (code) + 16 bytes (norm + centroid_dist)
- No codebook storage (unlike PQ)

### edge-cases-and-gotchas
- **Low dimensionality:** Binary quantization loses too much precision below ~512 dims
- **Rotation quality:** WHT is fast but pseudo-random; truly random orthogonal matrix gives slightly better bounds but costs O(D^2)
- **SAQ requires PCA:** Data-dependent training step; not purely streaming for initial setup
- **Auxiliary scalars are critical:** Distance estimation is unbiased only with proper norm/centroid corrections

## complexity-analysis

### build-phase
RaBitQ: O(D^2) rotation setup (one-time) + O(N * D) quantization. WHT variant: O(D log D) setup + O(N * D) quantization. SAQ: adds O(N * D) PCA + O(N * D * B) code refinement.

### query-phase
1-bit: O(D/64) per distance via POPCNT — 3x faster than PQ at same accuracy.
B-bit: O(B * D / 64) via FastScan with SIMD. Both are sub-linear in D per machine word.

### memory-footprint
| Config | Per-vector | Compression vs float32 |
|--------|-----------|----------------------|
| 1-bit, D=1024 | 128 + 16 = 144 bytes | ~28x |
| 2-bit, D=1024 | 256 + 16 = 272 bytes | ~15x |
| 4-bit, D=1024 | 512 + 16 = 528 bytes | ~7.7x |

## tradeoffs

### strengths
- Provable theoretical error bounds (unique among vector quantizers)
- No codebook memory overhead — rotation matrix is the only structure
- Streaming-compatible: new vectors quantized independently using fixed rotation
- SIMD-friendly: POPCNT (1-bit), FastScan (B-bit)
- 3x faster than PQ at same accuracy for single-vector distance (1-bit)
- Outperforms PQ at same bit budget (Extended RaBitQ)

### weaknesses
- Best for high-dimensional vectors (512+); less effective below
- 1-bit alone needs rescoring for high-recall applications
- SAQ variant requires PCA training step (not fully data-independent)
- Newer technique — fewer production deployments than PQ

### compared-to-alternatives
- vs [scalar-quantization](scalar-quantization.md): RaBitQ achieves better accuracy at same bits via rotation; SQ is simpler
- vs [binary-quantization](binary-quantization.md): RaBitQ IS binary quantization with provable bounds; plain BQ is sign-bit without rotation
- vs [residual-quantization](residual-quantization.md): RaBitQ is simpler (no codebooks); RQ achieves better accuracy at medium bit budgets (8-16 bytes)

## practical-usage

### when-to-use
- High-dimensional embeddings (512-3072 dims) from modern models (OpenAI, Cohere)
- Maximum compression with formal accuracy guarantees
- Streaming/online insertions where codebook retraining is unacceptable
- Java implementation: `Long.bitCount()` for POPCNT, `FloatVector` for WHT

### when-not-to-use
- Low-dimensional vectors (< 512 dims)
- When medium compression (4-8x) with very high recall is needed (use SQ8 instead)
- When codebook-based methods are already trained and performing well

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| RaBitQ | C++ | https://github.com/gaoj0017/RaBitQ | Research |
| Extended RaBitQ | C++ | https://github.com/VectorDB-NTU/Extended-RaBitQ | Research |
| RaBitQ Library | C++ | https://github.com/VectorDB-NTU/RaBitQ-Library | Research |
| rabitq-rs | Rust | https://github.com/lqhl/rabitq-rs | Active |
| SAQ | C++ | https://github.com/howarlii/SAQ | Research |
| LanceDB | Rust/Python | https://lancedb.com/ | Active (integrated) |

## code-skeleton

```java
class RaBitQQuantizer {
    private final float[][] rotation; // [D][D] or WHT factors
    private final int bitsPerDim;

    byte[] quantize(float[] vector) {
        float[] rotated = applyRotation(vector);
        int totalBits = vector.length * bitsPerDim;
        byte[] code = new byte[totalBits / 8];
        for (int i = 0; i < vector.length; i++)
            setBits(code, i * bitsPerDim, quantizeScalar(rotated[i], bitsPerDim));
        return code;
    }

    float distance(float[] query, byte[] code, float storedNorm) {
        float[] rotatedQuery = applyRotation(query);
        if (bitsPerDim == 1) {
            // Hamming distance via POPCNT
            long hammingDist = 0;
            byte[] queryBits = quantize(query);
            for (int i = 0; i < code.length; i += 8) {
                long a = bytesToLong(code, i), b = bytesToLong(queryBits, i);
                hammingDist += Long.bitCount(a ^ b);
            }
            return estimateDistance(hammingDist, storedNorm, query);
        }
        // B-bit: FastScan lookup-add
        return fastScanDistance(rotatedQuery, code);
    }
}
```

## sources

1. [RaBitQ (SIGMOD 2024)](https://arxiv.org/abs/2405.12497) — provable error bounds for binary vector quantization
2. [Extended RaBitQ (SIGMOD 2025)](https://arxiv.org/abs/2409.09913) — asymptotically optimal B-bit generalization
3. [SAQ (SIGMOD 2026)](https://arxiv.org/abs/2509.12086) — 80% lower error via code adjustment + segmentation
4. [LanceDB RaBitQ](https://lancedb.com/blog/feature-rabitq-quantization/) — production integration

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

---
title: "Binary Quantization"
aliases: ["binary-quantization", "bq", "1-bit-quantization", "hamming-search"]
topic: "algorithms"
category: "vector-quantization"
tags: ["quantization", "binary", "extreme-compression", "popcnt", "simd"]
complexity:
  time_build: "O(N * D)"
  time_query: "O(D / 64) via POPCNT"
  space: "O(N * D / 8)"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://qdrant.tech/blog/qdrant-1.15.x/"
    title: "Qdrant 1.15: 1.5-bit and 2-bit Binary Quantization"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://huggingface.co/blog/embedding-quantization"
    title: "HuggingFace Embedding Quantization Guide"
    accessed: "2026-03-16"
    type: "blog"
  - url: "https://blog.vespa.ai/combining-matryoshka-with-binary-quantization-using-embedder/"
    title: "Vespa: Matryoshka + Binary Quantization"
    accessed: "2026-03-16"
    type: "blog"
---

# Binary Quantization

## summary

Extreme quantization reducing each float32 dimension to a single bit (sign bit), yielding
32x compression. Distance computed via Hamming distance using CPU POPCNT — the fastest
possible vector comparison. Recall loss is significant without rescoring, so production
deployments oversampling candidates and rescore with full-precision vectors. Best for
high-dimensional embeddings (1024+) from models like OpenAI text-embedding-3 or Cohere
embed-v3. Recent advances (2025): Qdrant introduced 1.5-bit and 2-bit variants with
explicit zero encoding, and asymmetric quantization (float query vs binary stored).

## how-it-works

**Standard 1-bit BQ:**
1. For each dimension: bit = 1 if value >= threshold (usually 0 or mean), else 0
2. Pack D bits into D/8 bytes
3. Hamming distance between two binary vectors = number of differing bits = POPCNT(a XOR b)
4. Hamming distance correlates with angular distance for normalized vectors

**1.5-bit BQ (Qdrant 1.15+):**
Encodes three states per dimension: positive (1,1), negative (0,0), zero (0,1 or 1,0).
Uses 2 bits storage but captures the zero/near-zero signal that 1-bit loses. 24x compression.

**2-bit BQ (Qdrant 1.15+):**
Four quantization levels per dimension. 16x compression. Better accuracy than 1-bit,
still much faster than SQ8.

**Asymmetric BQ:**
Store vectors in 1-bit binary. Quantize queries to higher precision (4-bit or float).
Score using asymmetric distance (float query × binary stored). Better accuracy than
symmetric (binary × binary) with minimal query-time overhead.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| bits | 1, 1.5, or 2 bits per dim | 1 (max compression) | More bits = better recall, less compression |
| threshold | Quantization threshold | 0 or per-dim mean | Mean-centered often works better |
| oversampling | Candidates fetched for rescore | 2x-10x of final k | More = higher recall, slower |
| rescore | Use full-precision for top candidates | enabled (recommended) | Essential for high recall |

## algorithm-steps

1. **Quantize(x):** For each dim i: bit_i = (x[i] >= threshold) ? 1 : 0. Pack into byte array.
2. **Store:** Binary code (D/8 bytes) + keep full-precision vector for rescoring
3. **Query(q, k):** Compute binary code for q. Scan all binary codes with POPCNT. Select top oversampling*k candidates.
4. **Rescore:** Load full-precision vectors for top candidates. Compute exact distance. Return top k.

## implementation-notes

### data-structure-requirements
- Binary codes: D/8 bytes per vector (e.g., 1536 dims = 192 bytes)
- Full-precision vectors: D*4 bytes per vector (for rescoring — can be on disk)
- 1.5-bit: D/4 bytes per vector (two bit-planes)

### edge-cases-and-gotchas
- **Low-dim vectors:** BQ is ineffective below ~512 dims — too few bits to distinguish vectors
- **Model compatibility:** Works best with models trained for angular/cosine similarity where sign bits are meaningful. Models with many near-zero dimensions benefit from 1.5-bit.
- **Recall without rescore:** 1-bit BQ alone achieves 50-70% recall on typical benchmarks; rescore is essential for production quality
- **Matryoshka combination:** Truncate MRL-trained embeddings (e.g., 1536→256) THEN apply BQ for multiplicative compression (up to 384x). Vespa and HuggingFace document this pipeline.

## complexity-analysis

### build-phase
O(N * D) — trivial; each dimension is a single comparison.

### query-phase
Binary scan: O(N * D/64) via POPCNT — processes 64 bits per machine word. This is the fastest possible vector comparison, ~25-45x faster than float32. Rescore: O(candidates * D) with float32.

### memory-footprint
| Config | Per-vector (D=1536) | Compression |
|--------|-------------------|-------------|
| 1-bit | 192 bytes | 32x |
| 1.5-bit | 384 bytes | 16x |
| 2-bit | 384 bytes | 16x |
| + rescore vectors (on disk) | +6144 bytes | stored on SSD |

## tradeoffs

### strengths
- Maximum compression (32x) of any quantization method
- Fastest possible distance: POPCNT is a single CPU instruction per 64 bits
- Trivial to implement — no codebooks, no training
- Fully streaming: each vector quantized independently
- Combinable with Matryoshka truncation for extreme compression (384x)
- Java: `Long.bitCount(a ^ b)` for Hamming distance

### weaknesses
- Significant recall loss without rescoring (50-70% raw recall)
- Requires keeping full-precision vectors for rescore (partially offsets memory savings)
- Ineffective for low-dimensional vectors (< 512 dims)
- Not suitable when exact recall > 95% is needed without rescore budget

### compared-to-alternatives
- vs [rabitq](rabitq.md): RaBitQ adds random rotation before binarization → provable bounds and better accuracy. BQ is simpler but less accurate.
- vs [scalar-quantization](scalar-quantization.md): BQ achieves 32x vs 4x compression but needs rescore; SQ8 has <3% recall loss without rescore
- vs [residual-quantization](residual-quantization.md): completely different approach — BQ is one-shot, RQ is multi-stage refinement

## practical-usage

### when-to-use
- High-dimensional embeddings (1024+) from modern models
- First-pass candidate generation in a two-stage pipeline (BQ filter → exact rescore)
- Memory-constrained deployments where 32x compression is essential
- Combinable with MRL-trained embeddings for extreme compression

### when-not-to-use
- Low-dimensional vectors (< 512 dims)
- When rescoring budget is unavailable (no access to full-precision vectors)
- When SQ8's 4x compression is sufficient (simpler, better recall)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Qdrant | Rust | https://github.com/qdrant/qdrant | Active |
| Weaviate | Go | https://github.com/weaviate/weaviate | Active |
| Vespa | Java/C++ | https://github.com/vespa-engine/vespa | Active |
| FAISS IndexBinaryFlat | C++/Python | https://github.com/facebookresearch/faiss | Active |

## code-skeleton

```java
class BinaryQuantizer {
    byte[] quantize(float[] vector) {
        byte[] code = new byte[vector.length / 8];
        for (int i = 0; i < vector.length; i++)
            if (vector[i] >= 0) code[i / 8] |= (byte)(1 << (i % 8));
        return code;
    }

    int hammingDistance(byte[] a, byte[] b) {
        assert a.length == b.length;
        int dist = 0;
        for (int i = 0; i < a.length; i += 8) {
            long la = bytesToLong(a, i), lb = bytesToLong(b, i);
            dist += Long.bitCount(la ^ lb);
        }
        return dist;
    }

    long[] query(float[] q, byte[][] index, int k, int oversample) {
        byte[] qBinary = quantize(q);
        // Phase 1: binary scan — fast candidate generation
        int[] candidates = topByHamming(qBinary, index, k * oversample);
        // Phase 2: rescore with full-precision vectors
        return rescoreWithFloat(q, candidates, k);
    }
}
```

## sources

1. [Qdrant 1.15: 1.5-bit and 2-bit BQ](https://qdrant.tech/blog/qdrant-1.15.x/) — production multi-bit binary quantization
2. [HuggingFace Embedding Quantization](https://huggingface.co/blog/embedding-quantization) — MRL + BQ combination pipeline
3. [Vespa: Matryoshka + BQ](https://blog.vespa.ai/combining-matryoshka-with-binary-quantization-using-embedder/) — extreme compression pipeline

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

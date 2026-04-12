---
title: "Residual and Additive Quantization"
aliases: ["residual-quantization", "additive-quantization", "rq", "aq", "lsq", "qinco"]
topic: "algorithms"
category: "vector-quantization"
tags: ["quantization", "multi-codebook", "residual", "beam-search", "compression"]
complexity:
  time_build: "O(M * B * D * K) per vector (beam search)"
  time_query: "O(M * K * D + N * M) ADC"
  space: "O(N * M) codes + O(M * K * D) codebooks"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://github.com/facebookresearch/faiss/wiki/Additive-quantizers"
    title: "Faiss Additive Quantizers Wiki"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://arxiv.org/html/2401.08281v3"
    title: "The Faiss Library (Douze et al., 2024)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://arxiv.org/abs/2501.03078"
    title: "QINCo2: Improved Implicit Neural Codebooks (ICLR 2025)"
    accessed: "2026-03-16"
    type: "paper"
  - url: "https://link.springer.com/chapter/10.1007/978-3-030-01270-0_30"
    title: "LSQ++: Lower Running Time and Higher Recall (ECCV 2018)"
    accessed: "2026-03-16"
    type: "paper"
related:
  - "systems/vector-partitioning/quantization-aware.md"
---

# Residual and Additive Quantization

## summary

Multi-codebook quantization that approximates a vector as a sum of codewords from M
codebooks: x' = T_1[i_1] + T_2[i_2] + ... + T_M[i_M]. Unlike Product Quantization
(where each codebook operates on a d/M-dimensional subspace), additive quantizers use
full d-dimensional codebooks, capturing cross-dimension correlations. Residual Quantization
(RQ) encodes greedily stage-by-stage; Local Search Quantization (LSQ) optimizes the joint
assignment via simulated annealing; QINCo2 (ICLR 2025) uses neural networks to predict
per-stage codebooks. Achieves 5-20% higher recall than PQ at the same code size, but
encoding is 10-100x slower — best for batch/offline indexing. Unique feature: RQ supports
prefix-suffix splitting to convert a flat index into IVF without re-encoding.

## how-it-works

**Residual Quantization (sequential greedy):**
1. Stage 1: find i_1 = argmin ||T_1[j] - x||^2. Residual r_1 = x - T_1[i_1].
2. Stage m: find i_m = argmin ||T_m[j] - r_{m-1}||^2. Residual r_m = r_{m-1} - T_m[i_m].
3. Reconstruction: x' = sum of selected codewords.

**With beam search:** Maintain B candidate partial encodings. At each stage, extend each
candidate by trying all K codewords (B * K options), prune back to B. Beam width B = 4-8
significantly improves over greedy.

**Local Search Quantization (LSQ/LSQ++):** Start from initial encoding, iteratively perturb
one codebook assignment at a time using simulated annealing (SR-D stochastic relaxation).

**QINCo2 (ICLR 2025):** A neural network predicts the codebook for each stage conditioned
on the running approximation. 6x faster training, 3x faster encoding via candidate
pre-selection. State-of-the-art accuracy at low bit budgets.

**Product Residual Quantization (PRQ):** Hybrid — splits vector into sub-vectors (like PQ),
applies RQ independently per sub-vector. Combines PQ's encoding speed with RQ's accuracy.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| M | Stages/codebooks | 4-16 | More = better accuracy, slower encoding |
| K | Codebook size | 256 (8-bit) | Larger K = finer quantization, more training data needed |
| max_beam_size | Beam width for RQ | 1-32 | 4-8 usually sufficient; linear cost increase |
| encode_ils_iters | LSQ optimization iterations | 8-16 | More = better accuracy, slower encoding |
| by_residual | Encode x or x-centroid (IVF) | true (recommended) | Residual encoding is more accurate with IVF |

## algorithm-steps

1. **Train codebooks (RQ):** For m = 1..M: run k-means on the residuals from stage m-1 to produce T_m with K centroids
2. **Encode(x) with beam search:** Initialize beam B_0 = {(x, empty_code)}. For m = 1..M: for each (residual, code) in beam: try all K codewords; compute new residual; keep top B candidates by total distortion
3. **Precompute LUT (query):** For query q: LUT_m[j] = dot(T_m[j], q) for all m, j. Cost: O(M * K * D)
4. **ADC scan:** For each stored code (i_1..i_M): dist = sum of LUT_m[i_m] for m=1..M. Cost: O(M) per vector
5. **IVF integration:** Assign to nearest coarse centroid. Encode residual (x - centroid) using stages 2+. First stage(s) serve as coarse quantizer prefix.

## implementation-notes

### data-structure-requirements
- Codebooks: M * K * D floats (much larger than PQ's K * D total)
- Per-vector codes: M bytes (with K=256); or M * ceil(log2(K))/8 bytes for non-256 K
- Cross-product table (beam LUT): M^2 * K^2 floats (precomputed once for encoding)
- LUT per query: M * K floats

### edge-cases-and-gotchas
- **Encoding is NP-hard:** Optimal joint assignment is NP-hard (for general AQ). All practical methods are approximations.
- **Codebook collapse:** Early stages may dominate, leaving later stages with degenerate codebooks. ERVQ (2025) adds balancing regularization.
- **Diminishing returns:** Beyond ~12 stages, each additional stage provides minimal improvement.
- **Greedy suboptimality:** Without beam search, greedy RQ can lock into poor early-stage choices. Beam size 4-8 is sufficient.
- **Codebook storage:** M * K * D floats can be large (e.g., M=8, K=256, D=1024 → 8 MiB) — much more than PQ's codebooks.

## complexity-analysis

### build-phase
Training: O(M * K * D * N_train * iters) — sequential k-means per stage.
Encoding per vector: greedy O(M * K * D), beam O(M * B * K * D), beam+LUT O(M * D * K + M^2 * B * K). Encoding is 10-100x slower than PQ.

### query-phase
LUT build: O(M * K * D) per query — slower than PQ's O(M * K * D/M) = O(K * D).
Code scan: O(N * M) — identical to PQ. Total: O(M * K * D + N * M). The M factor in LUT build is the key overhead vs PQ.

### memory-footprint
| Config | Per-vector | Codebooks | Compression vs float32 (D=1024) |
|--------|-----------|-----------|-------------------------------|
| M=8, K=256 | 8 bytes | 8 MiB | 512x |
| M=16, K=256 | 16 bytes | 16 MiB | 256x |
| M=32, K=256 | 32 bytes | 32 MiB | 128x |

## tradeoffs

### strengths
- 5-20% higher recall than PQ at same code size (full-dimensional codebooks capture cross-subspace correlations)
- Prefix-suffix splitting: flat RQ index convertible to IVF without re-encoding (unique to RQ)
- Variable-rate: trained M-stage RQ usable at fewer stages for coarser results
- Non-uniform bit allocation across stages for optimal precision distribution
- State-of-the-art at low bit budgets (8-16 bytes per vector)

### weaknesses
- Encoding 10-100x slower than PQ — unsuitable for real-time streaming inserts
- Larger codebook storage than PQ (M * K * D vs K * D)
- LUT build per query is M times slower than PQ
- Training is sequential — each stage depends on the previous
- Requires batch training; no established incremental codebook update protocol

### compared-to-alternatives
- vs [scalar-quantization](scalar-quantization.md): RQ achieves much higher compression (128-512x vs 4x) but requires batch codebook training
- vs [rabitq](rabitq.md): RQ achieves better accuracy at medium bit budgets (8-16 bytes); RaBitQ is training-free and streaming
- vs [binary-quantization](binary-quantization.md): RQ at 8 bytes often beats BQ + rescore while using less total memory (no rescore vectors needed)

## current-research

### key-papers
1. Babenko & Lempitsky, "Additive Quantization for Extreme Vector Compression," CVPR 2014
2. Martinez et al., "LSQ++: Lower Running Time and Higher Recall," ECCV 2018
3. Douze et al., "The Faiss Library," arXiv 2024
4. "QINCo2: Improved Implicit Neural Codebooks," ICLR 2025
5. "ERVQ: Enhanced Residual Vector Quantization," 2025 — codebook balancing

### active-research-directions
- Neural codebook prediction (QINCo2) for state-of-the-art accuracy
- Hybrid PQ+RQ (ProductResidualQuantizer) balancing speed and accuracy
- ERVQ: codebook balancing and diversity regularization
- Online/incremental codebook updates (nascent research area)

## practical-usage

### when-to-use
- Billion-scale datasets where maximum accuracy per byte is critical
- Batch/offline indexing workflows (encoding speed is acceptable)
- When IVF integration with prefix-suffix splitting is valuable
- When 8-16 bytes per vector must achieve high recall without rescoring

### when-not-to-use
- Real-time streaming insertions (use SQ or RaBitQ instead)
- When PQ's simpler training and encoding are sufficient
- Small datasets where brute force is feasible
- When codebook storage overhead matters (memory-tight environments)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Faiss RQ/LSQ/AQ | C++/Python | https://github.com/facebookresearch/faiss | Active |
| QINCo2 | Python/PyTorch | https://github.com/facebookresearch/Qinco | Research |
| Faiss PRQ | C++/Python | https://github.com/facebookresearch/faiss | Active |

## code-skeleton

```java
class ResidualQuantizer {
    private final float[][][] codebooks; // [M][K][D]
    private final int M, K, D;

    byte[] encode(float[] vector, int beamSize) {
        // Beam search encoding
        record Candidate(float[] residual, byte[] codes, float distortion) {}
        List<Candidate> beam = List.of(new Candidate(vector, new byte[M], 0));
        for (int m = 0; m < M; m++) {
            List<Candidate> next = new ArrayList<>();
            for (var c : beam)
                for (int k = 0; k < K; k++) {
                    float[] newResidual = subtract(c.residual(), codebooks[m][k]);
                    float dist = c.distortion() + norm2(newResidual);
                    byte[] codes = c.codes().clone(); codes[m] = (byte) k;
                    next.add(new Candidate(newResidual, codes, dist));
                }
            beam = topK(next, beamSize);
        }
        return beam.getFirst().codes();
    }

    float asymmetricDistance(float[] query, byte[] codes) {
        // Precompute LUT then scan
        float[][] lut = new float[M][K]; // LUT[m][k] = dot(codebooks[m][k], query)
        for (int m = 0; m < M; m++)
            for (int k = 0; k < K; k++)
                lut[m][k] = dot(codebooks[m][k], query);
        float dist = 0;
        for (int m = 0; m < M; m++) dist += lut[m][codes[m] & 0xFF];
        return dist;
    }
}
```

## sources

1. [Faiss Additive Quantizers Wiki](https://github.com/facebookresearch/faiss/wiki/Additive-quantizers) — comprehensive guide to RQ/LSQ/AQ in Faiss
2. [QINCo2 (ICLR 2025)](https://arxiv.org/abs/2501.03078) — neural codebook prediction, beam search encoding
3. [The Faiss Library (2024)](https://arxiv.org/html/2401.08281v3) — overview including additive quantizer benchmarks
4. [LSQ++ (ECCV 2018)](https://link.springer.com/chapter/10.1007/978-3-030-01270-0_30) — simulated annealing for joint code optimization

---
*Researched: 2026-03-16 | Next review: 2026-06-14*

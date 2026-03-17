---
title: "Lossless Vector Compression"
aliases: ["float array compression", "lossless float compression", "byte-split compression"]
topic: "algorithms"
category: "vector-encoding"
tags: ["compression", "lossless", "float32", "float16", "byte-split", "delta-coding", "ALP"]
complexity:
  time_build: "O(d) per vector — transform + entropy coding"
  time_query: "O(d) per vector — entropy decode + inverse transform"
  space: "O(d × sizeof(T) × ratio) — ratio typically 0.4–0.8"
research_status: "active"
last_researched: "2026-03-17"
sources:
  - url: "https://aras-p.info/blog/2023/02/03/Float-Compression-5-Science/"
    title: "Float Compression 5: Science! — Aras Pranckevičius"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://gwlucastrig.github.io/GridfourDocs/notes/LosslessCompressionForFloatingPointData.html"
    title: "Gridfour Lossless Compression for Floating-Point Data"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://dl.acm.org/doi/10.1145/3626717"
    title: "ALP: Adaptive Lossless floating-Point Compression (SIGMOD 2024)"
    accessed: "2026-03-17"
    type: "paper"
  - url: "https://computing.llnl.gov/projects/floating-point-compression"
    title: "LLNL Floating Point Compression — fpzip and zfp"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://github.com/LLNL/zfp"
    title: "zfp — Compressed Numerical Arrays"
    accessed: "2026-03-17"
    type: "repo"
---

# Lossless Vector Compression

## summary

Lossless compression for float arrays exploits the structure of IEEE 754 representation:
sign, exponent, and mantissa bits have different statistical properties. The most effective
approaches separate these byte groups before applying standard entropy coding. This achieves
30–60% size reduction while preserving bit-exact round-trips. Key tradeoff: deserialization
is slower than flat encoding due to the decode step.

## how-it-works

IEEE 754 float32 values have internal structure that generic compressors cannot exploit:

```
Float32 bit layout:
┌──────┬──────────┬───────────────────────┐
│ sign │ exponent │ mantissa              │
│ 1 bit│ 8 bits   │ 23 bits               │
└──────┴──────────┴───────────────────────┘
```

The sign and exponent bits tend to be highly repetitive (low entropy), while mantissa
bits are closer to random. Mixing them together (as in standard byte order) defeats
generic compressors. **Byte-splitting** separates them into groups with uniform
statistical properties, dramatically improving compression.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| block_size | Vectors compressed together | 64–1024 | Larger = better ratio, worse random access |
| transform | Pre-compression transform | byte-split, delta, XOR | Determines ratio vs speed |
| entropy_coder | Backend compressor | Deflate, zstd, LZ4 | Speed/ratio tradeoff |

## algorithm-steps

### byte-split-and-compress (most practical approach)

This is the highest-ROI technique: simple to implement, 30–50% compression, fast.

1. **Group by byte position**: For `n` float32 values, create 4 sub-arrays:
   - Byte 0 (MSB — sign + exponent high): `b0[0..n]`
   - Byte 1 (exponent low + mantissa high): `b1[0..n]`
   - Byte 2 (mantissa mid): `b2[0..n]`
   - Byte 3 (mantissa low): `b3[0..n]`

2. **Optional delta filter**: Apply byte-level delta to mantissa groups (b1–b3).
   Skip delta on b0 (exponent group compresses well without it).

3. **Entropy code each group**: Compress each sub-array independently with
   zstd or Deflate. The MSB group will compress heavily (low entropy), the LSB
   group less so.

4. **Frame**: Write header with original count, then each compressed group
   with its length prefix.

```
Byte-split layout (n=4 float32 values):
Original:  [F0:b0 b1 b2 b3] [F1:b0 b1 b2 b3] [F2:b0 b1 b2 b3] [F3:b0 b1 b2 b3]

Transposed: [F0:b0, F1:b0, F2:b0, F3:b0]  ← high entropy similarity
            [F0:b1, F1:b1, F2:b1, F3:b1]
            [F0:b2, F1:b2, F2:b2, F3:b2]
            [F0:b3, F1:b3, F2:b3, F3:b3]  ← low entropy similarity
```

### gridfour-5-group-split (refined approach for float32)

Improves on simple byte-splitting by aligning groups to IEEE 754 field boundaries:

1. **Sign bits**: Pack 8 per byte. No delta. (~d/8 bytes, compresses ~90%)
2. **Exponents** (bits 23–30): 1 byte per value. No delta. (compresses ~70%)
3. **Mantissa high** (bits 16–22): 7 bits per value. Delta coded. (compresses ~40%)
4. **Mantissa mid** (bits 8–15): 8 bits per value. Delta coded. (compresses ~30%)
5. **Mantissa low** (bits 0–7): 8 bits per value. Delta coded. (compresses ~20%)

Achieves ~30–39% better compression than TIFF-style byte splitting.

### alp-for-decimal-originated-floats

ALP (Adaptive Lossless floating-Point Compression, SIGMOD 2024) is the
state-of-the-art for columnar databases. Key insight: most real-world floats
originated as decimal values (e.g., 3.14, 99.99).

1. **Detect decimal origin**: Check if `value × 10^e` produces an exact integer
   for a common exponent `e` across a block of values.
2. **If decimal**: Multiply all values by `10^e`, store as integers using
   Frame-of-Reference + bit-packing. ~1–2 bits per value overhead.
3. **If not decimal (ALPRD)**: XOR consecutive values, encode the leading
   zero count and remaining bits.
4. **Block-adaptive**: Choose ALP or ALPRD per 1024-value block.

ALP achieves 1–2 orders of magnitude faster decompression than fpzip while
matching or exceeding its compression ratio. However, it is optimized for
columnar databases where thousands of same-field values are compressed together.

## implementation-notes

### float16-considerations

Float16 has 1 sign bit, 5 exponent bits, 10 mantissa bits (2 bytes total).
Byte-splitting produces only 2 groups — less granularity for compression.

```
Float16 byte-split:
  Byte 0 (MSB): sign(1) + exponent(5) + mantissa_high(2)
  Byte 1 (LSB): mantissa_low(8)
```

For float16, the compression ratio is typically lower than float32 because:
- The data is already 2× smaller (less absolute savings)
- Only 2 byte groups vs 4 (less separation benefit)
- Mantissa is only 10 bits (less redundancy to exploit)

**Recommendation**: For float16 vectors, flat encoding is often preferred
unless block sizes are large (>256 vectors) and the data has spatial correlation.

### block-size-vs-random-access

Lossless compression requires decompressing entire blocks. This creates a tension:

| Block size | Compression ratio | Random access cost |
|------------|-------------------|-------------------|
| 1 vector | Poor (~5–10%) | O(d) — single vector |
| 64 vectors | Moderate (~25–35%) | O(64 × d) — must decode full block |
| 1024 vectors | Good (~40–50%) | O(1024 × d) — significant decode cost |

For vector search workloads where individual vectors are accessed during ranking,
large block sizes create amplified read costs. Block size should match the expected
access pattern.

### data-structure-requirements

- Block index mapping vector ID → block offset for random access
- Pre-allocated decode buffer (reusable across queries)
- Thread-safe if decode buffers are shared

### edge-cases-and-gotchas

- **NaN and Infinity**: IEEE 754 special values must round-trip exactly.
  Byte-split handles this naturally. ALP's decimal detection must skip these.
- **Subnormal floats**: Float16 subnormals have implicit zero exponent.
  Byte-split handles correctly. Delta coding on exponents may produce
  anomalous deltas at subnormal boundaries.
- **Endianness**: Byte-split groups assume a specific byte order. Must match
  the encoding byte order, not the platform native order.
- **Compression ratio variability**: Random or high-entropy float data
  may expand slightly under compression (header overhead). Always check
  compressed size < original size before storing compressed form.

## complexity-analysis

### build-phase

- **Byte-split + zstd**: O(d × n) transform + O(d × n) entropy coding per block
- **ALP**: O(n) decimal detection + O(n) integer encoding per block
- **Practical speed**: 500 MB/s–2 GB/s compression throughput (byte-split + zstd)

### query-phase

- **Byte-split + zstd**: O(block_size × d) decode per block access
- **ALP**: O(block_size) integer decode + O(block_size) multiply per block
- **Practical speed**: 2–10 GB/s decompression throughput (zstd level 1)
- **Critical**: Decompression is always slower than flat encoding's zero-decode path

### memory-footprint

Typical compression ratios on embedding-like float32 data:

| Method | Ratio | Speed (decompress) | Notes |
|--------|-------|--------------------| ------|
| Byte-split + zstd | 0.55–0.70 | ~4 GB/s | Best general-purpose |
| Byte-split + LZ4 | 0.65–0.80 | ~8 GB/s | Faster, less compression |
| Gridfour 5-group | 0.50–0.65 | ~2 GB/s | Best ratio, more complex |
| ALP | 0.10–0.40 | ~10 GB/s | Excellent for decimal data |
| fpzip | 0.40–0.60 | ~0.5 GB/s | Slow decompression |

## tradeoffs

### strengths

- **Significant space savings** — 30–60% reduction, lossless
- **Reduced I/O** — fewer bytes from disk/network compensates decode cost
- **Bit-exact** — no precision loss, no approximation

### weaknesses

- **Decode overhead** — always slower than flat encoding for deserialization
- **Block granularity** — random access requires full block decode
- **Implementation complexity** — byte-split + entropy coder is more code
- **Diminishing returns on float16** — already small, less compressible

### compared-to-alternatives

- vs [flat-vector-encoding](flat-vector-encoding.md): Compression saves I/O
  but adds decode latency. For remote backends where I/O dominates, compression
  wins. For local memmap where decode dominates, flat wins.
- vs [sparse-vector-encoding](sparse-vector-encoding.md): Compression handles
  all patterns (not just zeros). Sparse encoding is simpler for truly sparse data.

## current-research

### key-papers

- Afroozeh, Kuffó, Boncz. "ALP: Adaptive Lossless floating-Point Compression."
  SIGMOD 2024. DOI: 10.1145/3626717
- Lindstrom, Isenburg. "Fast and Efficient Compression of Floating-Point Data."
  IEEE TVCG 2006.
- Pranckevičius. "Float Compression" blog series, 2023. Comprehensive benchmarks.

### active-research-directions

- SIMD-accelerated byte transposition (AVX-512 vperm instructions)
- Learned compression models for embedding-specific patterns
- Streaming compression without block boundaries

## practical-usage

### when-to-use

- Storage or network bandwidth is the bottleneck (remote backends, S3/GCS)
- Vectors are read in bulk (batch processing, full scans) not random access
- Float32 data with spatial/temporal correlation across vectors
- Archival or cold storage where decode latency is acceptable

### when-not-to-use

- Individual vector random access during ranking (decode amplification)
- Float16 data where compression savings are marginal
- Hot-path deserialization where every microsecond matters
- Truly random float data (encrypted, hashed) — won't compress

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| zfp | C | https://github.com/LLNL/zfp | Active |
| fpzip | C | https://github.com/LLNL/fpzip | Maintained |
| ALP | C++ | https://github.com/cwida/ALP | Active |
| Blosc | C | https://github.com/Blosc/c-blosc2 | Active |

## code-skeleton

```java
// Byte-split compression for float32 vectors
class ByteSplitVectorCodec {
    private final int dimensions;

    byte[][] split(float[] vector) {
        byte[][] groups = new byte[4][vector.length];
        for (int i = 0; i < vector.length; i++) {
            int bits = Float.floatToRawIntBits(vector[i]);
            groups[0][i] = (byte) (bits >>> 24); // sign + exponent
            groups[1][i] = (byte) (bits >>> 16); // exponent + mantissa high
            groups[2][i] = (byte) (bits >>> 8);  // mantissa mid
            groups[3][i] = (byte) bits;          // mantissa low
        }
        return groups;
    }

    float[] unsplit(byte[][] groups, int count) {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            int bits = ((groups[0][i] & 0xFF) << 24)
                     | ((groups[1][i] & 0xFF) << 16)
                     | ((groups[2][i] & 0xFF) << 8)
                     | (groups[3][i] & 0xFF);
            result[i] = Float.intBitsToFloat(bits);
        }
        return result;
    }
}
```

## sources

1. [Float Compression 5: Science!](https://aras-p.info/blog/2023/02/03/Float-Compression-5-Science/) —
   comprehensive benchmarks of lossless float compression approaches
2. [Gridfour Lossless Float Compression](https://gwlucastrig.github.io/GridfourDocs/notes/LosslessCompressionForFloatingPointData.html) —
   detailed 5-group byte-split algorithm with benchmarks
3. [ALP: Adaptive Lossless floating-Point Compression](https://dl.acm.org/doi/10.1145/3626717) —
   state-of-the-art columnar float compression (SIGMOD 2024)
4. [LLNL Floating Point Compression](https://computing.llnl.gov/projects/floating-point-compression) —
   fpzip and zfp from Lawrence Livermore National Laboratory
5. [zfp GitHub](https://github.com/LLNL/zfp) — compressed numerical arrays with random access

---
*Researched: 2026-03-17 | Next review: 2026-09-17*

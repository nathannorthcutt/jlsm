---
title: "Sparse Vector Encoding"
aliases: ["sparse float array", "compressed sparse vector", "bitmap vector encoding"]
topic: "algorithms"
category: "vector-encoding"
tags: ["serialization", "sparse", "CSR", "bitmap", "COO", "float32", "float16"]
complexity:
  time_build: "O(nnz) — one pass over non-zero elements"
  time_query: "O(log nnz) binary search or O(1) bitmap lookup"
  space: "O(nnz × (sizeof(index) + sizeof(T))) + overhead"
research_status: "mature"
last_researched: "2026-03-17"
sources:
  - url: "https://en.wikipedia.org/wiki/Sparse_matrix"
    title: "Sparse matrix — Wikipedia"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://milvus.io/docs/sparse_vector.md"
    title: "Milvus Sparse Vector Documentation"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://dl.acm.org/doi/fullHtml/10.1145/3673038.3673055"
    title: "Bitmap-Based Sparse Matrix-Vector Multiplication with Tensor Cores"
    accessed: "2026-03-17"
    type: "paper"
  - url: "https://docs.scipy.org/doc/scipy/reference/generated/scipy.sparse.csr_matrix.html"
    title: "SciPy CSR Matrix Documentation"
    accessed: "2026-03-17"
    type: "docs"
---

# Sparse Vector Encoding

## summary

Sparse vector encoding stores only non-zero elements and their indices, reducing
storage for vectors where most dimensions are zero. Common in NLP (bag-of-words,
TF-IDF, BM25 sparse embeddings) and learned sparse representations (SPLADE).
Multiple formats exist with different tradeoffs: COO (simplest), CSR (cache-friendly
sequential access), and bitmap (fast random access for moderate sparsity).

## how-it-works

Instead of storing all `d` elements, sparse encoding stores only `nnz` (number of
non-zero) elements along with their dimension indices.

### format-comparison

```
Dense (d=8):  [0.0, 0.5, 0.0, 0.0, 0.3, 0.0, 0.0, 0.9]
              → 32 bytes (8 × float32)

COO:          indices: [1, 4, 7]    values: [0.5, 0.3, 0.9]
              → 24 bytes (3 × (int32 + float32))

Bitmap:       mask: 0b01001001 = 0x49    values: [0.5, 0.3, 0.9]
              → 13 bytes (1 byte mask + 3 × float32)
```

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| nnz | Non-zero element count | 1–d | Determines storage size |
| sparsity | Fraction of zeros: `1 - nnz/d` | 0.5–0.99 | Break-even vs flat at ~50% |
| index_width | Bytes per index (2 or 4) | 2 for d≤65535, 4 otherwise | Overhead per element |
| element_width | Bytes per value (2 or 4) | 2=float16, 4=float32 | Value storage cost |

## algorithm-steps

### coo-format (coordinate list)

1. **Encode**: Scan input vector. For each non-zero element at index `i`, append
   `(i, value)` to the output. Prefix with `nnz` count.
2. **Decode**: Allocate zero-filled vector of dimension `d`. For each `(i, value)`
   pair, set `vector[i] = value`.
3. **Lookup**: Binary search on sorted indices — O(log nnz).

```
Binary layout:
┌─────────┬───────────────────────────────────────────┐
│ nnz     │ [index₀, value₀, index₁, value₁, ...]    │
│ 4 bytes │ nnz × (index_width + element_width) bytes │
└─────────┴───────────────────────────────────────────┘
```

### bitmap-format

1. **Encode**: Create a bitmask of `d` bits. Set bit `i` if element `i` is non-zero.
   Write bitmask, then write non-zero values contiguously.
2. **Decode**: Read bitmask. For each set bit, read the next value from the values
   array and place it at the corresponding dimension.
3. **Lookup**: Check bit `i` in mask. If set, count set bits before position `i`
   (popcount) to find the value's offset — O(1) with hardware popcount.

```
Binary layout:
┌─────────────────────┬──────────────────────────────┐
│ bitmask             │ values (only non-zeros)       │
│ ceil(d/8) bytes     │ nnz × element_width bytes     │
└─────────────────────┴──────────────────────────────┘
```

### hybrid-format (bitmap + run-length for very sparse data)

For dimensions > 4096 with very high sparsity (>99%), bitmap overhead grows.
Hybrid approaches use run-length encoding on the bitmap itself, or switch
to COO when `nnz × (index_width + elem_width) < ceil(d/8) + nnz × elem_width`.

## implementation-notes

### data-structure-requirements

- Schema must track `dimensions` and `elementType`
- Format choice can be per-vector (adaptive) or per-column (fixed)
- COO indices should be sorted for binary search and merge operations

### break-even-analysis

The sparse format saves space when its encoding is smaller than flat:
```
COO:    nnz × (index_width + elem_width) + 4  <  d × elem_width
Bitmap: ceil(d/8) + nnz × elem_width          <  d × elem_width
```

| Format | Break-even sparsity (d=768, float32) | Break-even (d=4096, float32) |
|--------|--------------------------------------|------------------------------|
| COO (int16 index) | nnz < 512 (33%) | nnz < 2731 (33%) |
| COO (int32 index) | nnz < 384 (50%) | nnz < 2048 (50%) |
| Bitmap | nnz < 744 (3% sparsity needed) | nnz < 3584 (12% sparsity) |

**Key insight**: For typical embedding dimensions (768–4096), COO only saves space
when more than 50% of elements are zero. Bitmap is only beneficial when the bitmap
itself (ceil(d/8) bytes) is small relative to the values saved.

### edge-cases-and-gotchas

- **Not useful for dense embeddings**: Neural network embeddings (BERT, OpenAI)
  are typically dense — very few exact zeros. Sparse encoding adds overhead.
- **Negative zeros**: IEEE 754 has +0.0 and -0.0. Both should be treated as
  zero for sparsity detection, but -0.0 has a non-zero bit pattern.
- **Near-zero thresholding**: Some systems threshold values below ε as zero.
  This is lossy — not suitable when lossless encoding is required.
- **Index overflow**: For d > 65535, int16 indices are insufficient. Use int32.
- **Merge cost**: Computing distance between two sparse vectors requires a
  merge-join on sorted indices — O(nnz₁ + nnz₂), not O(nnz₁ × nnz₂).

## complexity-analysis

### build-phase

- **COO encode**: O(d) scan + O(nnz) writes
- **Bitmap encode**: O(d) scan + O(nnz) writes + O(ceil(d/8)) mask writes

### query-phase

- **Full vector reconstruct**: O(d) for bitmap (scan all bits), O(nnz) for COO
- **Single element lookup**: O(1) for bitmap (bit test + popcount), O(log nnz) for COO
- **Distance computation (two sparse)**: O(nnz₁ + nnz₂) merge-join

### memory-footprint

| Dimensions | nnz | Float32 Flat | COO (int16) | Bitmap | Winner |
|------------|-----|-------------|-------------|--------|--------|
| 768 | 10 | 3,072 | 64 | 136 | COO |
| 768 | 100 | 3,072 | 604 | 496 | Bitmap |
| 768 | 384 | 3,072 | 2,308 | 1,632 | Bitmap |
| 768 | 600 | 3,072 | 3,604 | 2,496 | Bitmap |
| 4096 | 50 | 16,384 | 304 | 712 | COO |
| 4096 | 500 | 16,384 | 3,004 | 2,512 | Bitmap |

## tradeoffs

### strengths

- **Massive savings for truly sparse data** — NLP/BM25 vectors may be 99%+ zeros
- **Reduced I/O** — fewer bytes to read from disk or network
- **Bitmap enables O(1) lookups** — popcount hardware instruction

### weaknesses

- **Overhead for dense data** — indices add per-element cost
- **Complex decode** — more logic than flat encoding
- **Not SIMD-friendly** — scattered indices prevent contiguous SIMD loads
- **Distance computation harder** — requires merge-join rather than element-wise

### compared-to-alternatives

- vs [flat-vector-encoding](flat-vector-encoding.md): Sparse wins only when sparsity
  is high (>50% zeros). For dense embeddings, flat is simpler and faster.
- vs [lossless-vector-compression](lossless-vector-compression.md): Sparse encoding
  is a form of compression, but general lossless compression may achieve better ratios
  on data with patterns beyond just zeros.

## practical-usage

### when-to-use

- BM25 / TF-IDF sparse embeddings (typically >95% zeros)
- Learned sparse representations (SPLADE, etc.)
- Any vector type where most elements are provably zero

### when-not-to-use

- Dense neural embeddings (BERT, OpenAI, Cohere) — near-zero sparsity
- When lossless round-trip of all values (including zeros) is required
- When SIMD-accelerated distance computation is the bottleneck

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Milvus | Go/C++ | https://github.com/milvus-io/milvus | Active |
| Qdrant | Rust | https://github.com/qdrant/qdrant | Active |
| SciPy | Python | https://github.com/scipy/scipy | Active |

## code-skeleton

```java
// COO sparse vector encoding
class SparseVectorCodec {
    private final int dimensions;
    private final int elementBytes; // 2 or 4

    void encode(float[] vector, MemorySegment dest, long offset) {
        int nnz = 0;
        for (float v : vector) if (v != 0.0f) nnz++;
        dest.set(JAVA_INT_BIG_ENDIAN, offset, nnz);
        long pos = offset + 4;
        for (int i = 0; i < vector.length; i++) {
            if (vector[i] != 0.0f) {
                dest.set(JAVA_SHORT_BIG_ENDIAN, pos, (short) i);
                dest.set(JAVA_FLOAT_BIG_ENDIAN, pos + 2, vector[i]);
                pos += 6;
            }
        }
    }
}
```

## sources

1. [Sparse matrix — Wikipedia](https://en.wikipedia.org/wiki/Sparse_matrix) —
   comprehensive overview of COO, CSR, CSC, ELLPACK formats
2. [Milvus Sparse Vector Documentation](https://milvus.io/docs/sparse_vector.md) —
   production sparse vector storage in a vector database
3. [Bitmap-Based SpMV with Tensor Cores](https://dl.acm.org/doi/fullHtml/10.1145/3673038.3673055) —
   bitmap compression for sparse data with hardware acceleration
4. [SciPy CSR Matrix](https://docs.scipy.org/doc/scipy/reference/generated/scipy.sparse.csr_matrix.html) —
   reference CSR implementation

---
*Researched: 2026-03-17 | Next review: 2027-09-17*

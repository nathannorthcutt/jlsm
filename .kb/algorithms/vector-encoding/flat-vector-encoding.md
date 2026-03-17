---
title: "Flat Vector Encoding"
aliases: ["dense vector layout", "contiguous float array encoding"]
topic: "algorithms"
category: "vector-encoding"
tags: ["serialization", "float32", "float16", "zero-copy", "SIMD", "alignment"]
complexity:
  time_build: "O(n) — one pass over n elements"
  time_query: "O(1) random access per element, O(d) full vector read"
  space: "O(d × sizeof(T)) per vector, no overhead"
research_status: "stable"
last_researched: "2026-03-17"
sources:
  - url: "https://arrow.apache.org/docs/format/Columnar.html"
    title: "Arrow Columnar Format"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://deepwiki.com/qdrant/qdrant/3.1-vector-storage-formats"
    title: "Qdrant Vector Storage Formats"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://flatbuffers.dev/internals/"
    title: "FlatBuffers Internals"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://www.freecodecamp.org/news/how-to-integrate-vector-search-in-columnar-storage/"
    title: "Integrating Vector Search in Columnar Storage"
    accessed: "2026-03-17"
    type: "blog"
---

# Flat Vector Encoding

## summary

Flat vector encoding stores fixed-dimension vectors as contiguous byte arrays with
no metadata overhead per vector. Each vector occupies exactly `dimensions × sizeof(element)`
bytes. This is the simplest, fastest, and most widely used format for dense vector storage
in production systems (Qdrant, Milvus, FAISS, Apache Arrow). It enables zero-copy reads
via memory mapping and is naturally SIMD-friendly because elements are laid out sequentially.

## how-it-works

Each vector is a fixed-length byte sequence: `d` elements × element width in bytes.
No length prefix, no delimiter, no per-vector header. The dimension count and element
type are schema-level metadata, not encoded per-vector.

```
Vector layout (d=4, float32, big-endian):
┌──────────┬──────────┬──────────┬──────────┐
│ float[0] │ float[1] │ float[2] │ float[3] │
│  4 bytes │  4 bytes │  4 bytes │  4 bytes │
└──────────┴──────────┴──────────┴──────────┘
Total: 16 bytes, no overhead
```

For float16 vectors, each element is 2 bytes (IEEE 754 half-precision):
```
Vector layout (d=4, float16, big-endian):
┌──────────┬──────────┬──────────┬──────────┐
│ half[0]  │ half[1]  │ half[2]  │ half[3]  │
│  2 bytes │  2 bytes │  2 bytes │  2 bytes │
└──────────┴──────────┴──────────┴──────────┘
Total: 8 bytes, no overhead
```

### key-parameters

| Parameter | Description | Typical Range | Impact on Performance |
|-----------|-------------|---------------|----------------------|
| dimensions | Element count per vector | 32–4096 | Linear impact on size and I/O |
| element_width | Bytes per element (2=float16, 4=float32) | 2 or 4 | 2× size difference |
| byte_order | Endianness (big-endian or little-endian) | Platform choice | Big-endian for sort-preserving keys |
| alignment | Byte alignment of vector start | 1, 4, 8, or 64 | SIMD requires ≥element_width |

## algorithm-steps

1. **Serialize**: For each element in the vector, write `sizeof(T)` bytes in the
   chosen byte order to the output buffer at offset `i × sizeof(T)`.
2. **Deserialize**: Read `d × sizeof(T)` bytes from the input. Interpret as `d`
   elements of type `T` in the declared byte order.
3. **Random access**: Element `i` is at byte offset `i × sizeof(T)` — O(1).
4. **Batch SIMD encode/decode**: Process elements in SIMD-width chunks. For
   big-endian on little-endian hardware, apply a byte-swap shuffle per lane.

## implementation-notes

### data-structure-requirements

- Schema must track `dimensions` and `elementType` (FLOAT16 or FLOAT32)
- No per-vector metadata needed — all vectors in a column share the schema
- For memory-mapped access: align vector start to `sizeof(T)` minimum,
  64-byte alignment preferred for AVX-512 SIMD

### alignment-considerations

| Alignment | Benefit | Cost |
|-----------|---------|------|
| 1-byte (unaligned) | Zero padding waste | Slower unaligned loads |
| 4-byte (float32 natural) | Aligned float32 loads | Up to 3 bytes padding |
| 8-byte (MemorySegment default) | Aligned for most ValueLayouts | Up to 7 bytes padding |
| 64-byte (cache-line / SIMD) | Full SIMD register alignment | Up to 63 bytes padding |

For fixed-dimension vectors where all records have identical size, alignment padding
is zero if `d × sizeof(T)` is a multiple of the alignment. Common dimensions (64, 128,
256, 512, 768, 1024, 1536, 4096) are all multiples of 64 for float32 (4 bytes),
so 64-byte alignment adds no waste for these standard sizes.

### edge-cases-and-gotchas

- **Endianness mismatch**: If vectors are stored big-endian for sort-preserving
  properties but the CPU is little-endian, every read requires byte-swapping.
  SIMD shuffle instructions amortize this cost.
- **Float16 on JVM**: Java has no native float16 type. Values are stored as
  `short` (raw IEEE 754 bits) and converted to `float` only when needed for
  computation. Round-trip must be bit-exact.
- **Zero-copy from remote**: Memory-mapped zero-copy only works for local files.
  Remote backends (S3, GCS) require reading into a buffer — but flat encoding
  still benefits because the entire vector is a single contiguous read.
- **No null elements**: Individual vector elements should not be nullable — the
  vector as a whole may be null (tracked in a document-level null bitmask), but
  partial vectors are semantically meaningless.

## complexity-analysis

### build-phase

- **Time**: O(d) per vector — one pass, element-by-element or SIMD-chunked
- **Allocation**: Zero extra allocation if writing into a pre-sized buffer

### query-phase

- **Full vector read**: O(d) — sequential memory access, cache-friendly
- **Single element access**: O(1) — direct offset calculation
- **SIMD decode**: O(d / SIMD_width) — e.g., 8 float32s per AVX-256 iteration

### memory-footprint

| Dimensions | Float32 (bytes) | Float16 (bytes) |
|------------|-----------------|-----------------|
| 32 | 128 | 64 |
| 128 | 512 | 256 |
| 768 | 3,072 | 1,536 |
| 1536 | 6,144 | 3,072 |
| 4096 | 16,384 | 8,192 |

Zero overhead per vector. At 1 billion documents with 768-dim float32 vectors:
~2.86 TiB raw vector data.

## tradeoffs

### strengths

- **Simplest possible format** — no parsing, no metadata per vector
- **Zero-copy compatible** — memory-map a file, cast to typed array
- **SIMD-friendly** — contiguous layout enables vectorized operations
- **Predictable I/O** — fixed size means exact byte offset for any vector
- **Optimal for deserialization** — no decoding step beyond byte-swap

### weaknesses

- **No compression** — every element stored at full precision
- **Sparse data wastes space** — zeros occupy same space as non-zeros
- **Large I/O for high dimensions** — a 4096-dim float32 vector is 16 KiB

### compared-to-alternatives

- vs [sparse-vector-encoding](sparse-vector-encoding.md): Flat wastes space
  on sparse data but is faster for dense vectors (no index lookups)
- vs [lossless-vector-compression](lossless-vector-compression.md): Flat has
  zero decode overhead but larger on-disk size

## practical-usage

### when-to-use

- Dense vectors (most elements non-zero) — embeddings, neural network outputs
- Deserialization speed is the top priority
- Vectors will be memory-mapped or bulk-loaded
- Fixed-dimension vectors where schema tracks the size

### when-not-to-use

- Highly sparse vectors (>90% zeros) — consider sparse encoding
- Storage cost dominates and vectors are compressible — consider lossless compression
- Variable-length sequences that aren't true fixed-dimension vectors

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Apache Arrow | Multi | https://arrow.apache.org | Active |
| Qdrant | Rust | https://github.com/qdrant/qdrant | Active |
| FAISS | C++/Python | https://github.com/facebookresearch/faiss | Active |
| FlatBuffers | Multi | https://github.com/google/flatbuffers | Active |

## code-skeleton

```java
// Flat vector encoding for fixed-dimension float32/float16
class FlatVectorCodec {
    private final int dimensions;
    private final int elementBytes; // 2 for float16, 4 for float32

    int encodedSize() { return dimensions * elementBytes; }

    void encode(float[] vector, MemorySegment dest, long offset) {
        for (int i = 0; i < dimensions; i++) {
            dest.set(JAVA_FLOAT_BIG_ENDIAN, offset + (long) i * 4, vector[i]);
        }
    }

    void decode(MemorySegment src, long offset, float[] out) {
        for (int i = 0; i < dimensions; i++) {
            out[i] = src.get(JAVA_FLOAT_BIG_ENDIAN, offset + (long) i * 4);
        }
    }
}
```

## sources

1. [Arrow Columnar Format](https://arrow.apache.org/docs/format/Columnar.html) —
   authoritative spec for fixed-size list and primitive array layouts
2. [Qdrant Vector Storage Formats](https://deepwiki.com/qdrant/qdrant/3.1-vector-storage-formats) —
   production vector DB storage architecture (memmap, chunked, float16 support)
3. [FlatBuffers Internals](https://flatbuffers.dev/internals/) —
   zero-copy binary format with alignment rules
4. [Integrating Vector Search in Columnar Storage](https://www.freecodecamp.org/news/how-to-integrate-vector-search-in-columnar-storage/) —
   columnar vs row-oriented I/O tradeoffs for vector data

---
*Researched: 2026-03-17 | Next review: 2027-03-17*

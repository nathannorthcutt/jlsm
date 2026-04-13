---
title: "Block Compression Algorithms for Storage Engines"
aliases: ["block compression", "SSTable compression", "data block compression"]
topic: "algorithms"
category: "compression"
tags: ["lz4", "snappy", "deflate", "zstd", "lz77", "sstable", "block-compression"]
related:
  - "algorithms/compression/wal-compression-patterns.md"
  - "algorithms/compression/zstd-dictionary-compression.md"
complexity:
  time_build: "O(n) — single pass over input"
  time_query: "O(n) — single pass decompression"
  space: "O(1) — fixed working memory (hash table + output buffer)"
research_status: "mature"
last_researched: "2026-03-17"
sources:
  - url: "https://dev.to/konstantinas_mamonas/compression-algorithms-you-probably-inherited-gzip-snappy-lz4-zstd-36h0"
    title: "Compression Algorithms You Probably Inherited: gzip, Snappy, LZ4, zstd"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://dev.to/konstantinas_mamonas/which-compression-saves-the-most-storage-gzip-snappy-lz4-zstd-1898"
    title: "Which Compression Saves the Most Storage?"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://github.com/lz4/lz4"
    title: "LZ4 — Extremely Fast Compression algorithm"
    accessed: "2026-03-17"
    type: "repo"
  - url: "https://cassandra.apache.org/doc/4.0/cassandra/operating/compression.html"
    title: "Apache Cassandra Compression Documentation"
    accessed: "2026-03-17"
    type: "docs"
  - url: "https://www.scylladb.com/2019/10/04/compression-in-scylla-part-one/"
    title: "Compression in ScyllaDB, Part One"
    accessed: "2026-03-17"
    type: "blog"
  - url: "https://morotti.github.io/lzbench-web/"
    title: "lzbench Compression Benchmark"
    accessed: "2026-03-17"
    type: "benchmark"
---

# Block Compression Algorithms for Storage Engines

## summary

Block compression in storage engines (SSTables, LSM-Trees) operates at the data block level —
each block is independently compressed and decompressed. All mainstream algorithms descend from
LZ77 (dictionary-based sliding-window matching). The key tradeoff is speed vs. ratio: fast codecs
(LZ4, Snappy) prioritize throughput at ~2:1 ratio, while ratio codecs (Deflate, ZSTD) achieve
~3:1+ at lower throughput. For 4 KiB blocks, compression ratios degrade compared to larger
inputs because the dictionary window is small — Deflate and ZSTD hold up best at small block sizes.

## how-it-works

All four algorithms are LZ77 variants. The compressor slides a window over input bytes, finds
repeated sequences via a hash table, and encodes them as (offset, length) backreferences.
Unmatched bytes are emitted as literals.

```
Input:  [AAABCAAABCXYZ]
Output: [literal "AABC" | match offset=4 length=4 | literal "XYZ"]
```

The differences between algorithms lie in three areas:
1. **Entropy coding layer** — whether an additional coding pass (Huffman, FSE) is applied
2. **Hash table and match-finding strategy** — speed/quality of match search
3. **Block format encoding** — how tokens, literals, and matches are byte-packed

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Block size | Input chunk size | 4–64 KiB | Larger → better ratio, worse random-access granularity |
| Compression level | Speed/ratio dial (Deflate, ZSTD) | 1–9 (Deflate), 1–22 (ZSTD) | Higher → better ratio, slower compression |
| Min match length | Shortest backreference | 3 (Deflate) or 4 (LZ4/Snappy) | Lower → slightly better ratio |
| Window size | How far back matches can reference | 32 KiB (Deflate), 64 KiB (LZ4) | Larger → better ratio on repetitive data |

## algorithm-steps

### lz4 compression (simplified)

1. **Initialize** hash table (4096 entries, maps 4-byte sequences to positions)
2. **For each position** `p` in input:
   a. Hash the 4 bytes at `p` → index `h`
   b. Look up candidate position `c = table[h]`
   c. If `input[c..c+4] == input[p..p+4]` and `p - c < 65536`:
      - Extend match forward to find total match length `matchLen`
      - Emit **token byte**: high nibble = literal length, low nibble = match length - 4
      - Emit literal bytes (if any accumulated since last match)
      - Emit 2-byte little-endian offset (`p - c`)
      - If literal length ≥ 15: emit extra length bytes (255, 255, ..., remainder)
      - If match length - 4 ≥ 15: emit extra length bytes similarly
   d. Else: accumulate byte at `p` as literal
   e. Store `table[h] = p`
3. **Emit final literals** (last 5 bytes are always literals — end-of-block safety margin)

### lz4 decompression

1. **Read token byte** → literal length (high nibble), match length (low nibble)
2. If literal length == 15: read additional bytes (each 255 adds 255, final byte adds remainder)
3. **Copy** `literalLength` bytes from compressed stream to output
4. **Read 2-byte offset** (little-endian) — this is the match distance
5. Match length += 4 (minimum match). If low nibble == 15: read additional length bytes
6. **Copy** `matchLength` bytes from output buffer at `(currentPos - offset)` to current position
7. Repeat until input is consumed

### deflate (java.util.zip)

1. LZ77 pass: find matches in 32 KiB sliding window (min match 3 bytes)
2. Huffman pass: encode literal/length and distance symbols with dynamic Huffman trees
3. Output: Huffman-coded bitstream with embedded code trees

Java's `Deflater`/`Inflater` wrap zlib's C implementation via JNI — fast but crosses JNI boundary.

## implementation-notes

### data-structure-requirements

- **Hash table**: LZ4 uses a simple 4096-entry table (12-bit hash of 4 bytes). No chaining —
  collisions simply overwrite. This is what makes it fast.
- **Output buffer**: must be pre-allocated. LZ4 worst case: `inputSize + (inputSize / 255) + 16`
- **Deflate**: Java's `Deflater` manages its own internal state; caller provides input/output byte arrays

### edge-cases-and-gotchas

- **Incompressible data**: LZ4 output can be larger than input (all literals + token overhead).
  Always check `compressedSize >= uncompressedSize` and store uncompressed if no savings.
- **4 KiB blocks**: small dictionary window reduces ratio significantly. LZ4 may achieve only
  5–15% savings on small blocks vs. 40–60% on 64 KiB+ blocks.
- **Deflate level trade-off**: level 1 is ~200 MB/s compression, level 6 (default) is ~50 MB/s,
  level 9 is ~15 MB/s — all decompress at the same ~300 MB/s.
- **java.util.zip.Deflater**: must call `end()` to free native memory. Use try-with-resources or
  explicit finally blocks. The `Deflater`/`Inflater` are not thread-safe.
- **Last-literals rule (LZ4)**: the last 5 bytes of input must be emitted as literals (not
  matches) — this is a format requirement for safe decompression.

### no-dependency constraint

For a pure Java library with no external dependencies:
- **Deflate**: available via `java.util.zip.Deflater`/`Inflater` — zero dependencies, backed by
  native zlib. Good ratio, moderate speed.
- **LZ4**: implementable in pure Java. The algorithm is simple (~200 lines of core logic). The
  format is well-specified and BSD-licensed. A pure-Java LZ4 compressor/decompressor is feasible
  and commonly done (see Kafka, Lucene).
- **Snappy**: also implementable in pure Java, but LZ4 is strictly better in both speed and ratio
  in modern benchmarks — no reason to implement Snappy when LZ4 exists.
- **ZSTD**: complex (FSE + Huffman + LZ77 combined). A pure-Java implementation is non-trivial
  (~5000+ lines). Not recommended for hand-rolling.

## complexity-analysis

### build-phase

All algorithms are O(n) with small constants. Per-block:
- LZ4: ~780 MB/s compression (native), ~200–400 MB/s achievable in pure Java
- Deflate (level 6): ~50 MB/s via `java.util.zip`
- ZSTD: ~400 MB/s (native, not applicable for pure Java)

### query-phase

Decompression is uniformly faster than compression:
- LZ4: ~4970 MB/s (native), ~800–1500 MB/s in pure Java
- Deflate: ~300 MB/s via `java.util.zip`
- ZSTD: ~1000 MB/s (native)

### memory-footprint

- LZ4 compression: 16 KiB hash table + output buffer
- LZ4 decompression: output buffer only (no hash table needed)
- Deflate: ~300 KiB internal state per `Deflater` instance (JNI-managed)

## tradeoffs

### strengths

- **LZ4**: fastest compression and decompression; simple to implement in pure Java; excellent
  for latency-sensitive read paths; decompression is branch-free and cache-friendly
- **Deflate**: best ratio available without external deps via `java.util.zip`; ubiquitous;
  good for storage-sensitive or remote/network workloads

### weaknesses

- **LZ4**: lower compression ratio than Deflate, especially on small (4 KiB) blocks
- **Deflate**: 5–10x slower compression than LZ4; JNI overhead per call; `Deflater`/`Inflater`
  instances require explicit lifecycle management

### compared-to-alternatives

- Snappy: strictly dominated by LZ4 in both speed and ratio — no reason to choose Snappy today
- ZSTD: best overall (tunable speed/ratio), but too complex to implement in pure Java without
  external dependency
- Brotli: optimized for HTTP content, not suitable for database block compression

## practical-usage

### when-to-use

- **LZ4**: default choice for SSTable block compression when read latency matters most (OLTP,
  interactive queries). Also preferred when CPU budget is tight.
- **Deflate**: when storage cost or network I/O dominates (remote backends like S3, GCS) and
  the CPU cost of decompression is acceptable.
- **No compression**: when blocks contain already-compressed or random data (encrypted content,
  pre-compressed media).

### when-not-to-use

- Do not compress blocks smaller than ~256 bytes — overhead exceeds savings
- Do not use Deflate level 9 on write-heavy workloads — compression throughput drops to ~15 MB/s
- Do not compress key index or bloom filter blocks — they are small and accessed on every read

## reference-implementations

| Library | Language | URL | Notes |
|---------|----------|-----|-------|
| lz4/lz4 | C | github.com/lz4/lz4 | Reference implementation, BSD-2 |
| lz4-java | Java | github.com/lz4/lz4-java | JNI + pure-Java fallback |
| Apache Kafka | Java | Kafka LZ4BlockOutputStream | Pure-Java LZ4 block codec |
| Apache Lucene | Java | Lucene LZ4 | Pure-Java LZ4 for stored fields |
| java.util.zip | Java | JDK standard library | Deflate via native zlib |

## code-skeleton

```java
// Codec interface
sealed interface CompressionCodec {
    byte[] compress(byte[] input, int offset, int length);
    byte[] decompress(byte[] input, int offset, int length, int uncompressedLength);
    byte codecId();  // stored in block header for self-describing format
}

// LZ4 compressor (pure Java, simplified)
final class Lz4Codec implements CompressionCodec {
    private static final int HASH_TABLE_SIZE = 4096;
    private static final int MIN_MATCH = 4;
    private static final int MAX_OFFSET = 65535;

    public byte[] compress(byte[] src, int off, int len) {
        int[] hashTable = new int[HASH_TABLE_SIZE];
        Arrays.fill(hashTable, -1);
        byte[] dst = new byte[maxCompressedLength(len)];
        int dstPos = 0, anchor = off;
        // ... hash-match-emit loop per algorithm-steps above
        return Arrays.copyOf(dst, dstPos);
    }

    public byte[] decompress(byte[] src, int off, int len, int originalLen) {
        byte[] dst = new byte[originalLen];
        int sPos = off, dPos = 0;
        // ... token-literal-match loop per decompression steps above
        return dst;
    }

    public byte codecId() { return 0x01; }
}

// Deflate codec (java.util.zip wrapper)
final class DeflateCodec implements CompressionCodec {
    private final int level;
    DeflateCodec(int level) { this.level = level; }

    public byte[] compress(byte[] src, int off, int len) {
        var def = new Deflater(level);
        try {
            def.setInput(src, off, len);
            def.finish();
            byte[] buf = new byte[len + 64];
            int written = def.deflate(buf);
            return Arrays.copyOf(buf, written);
        } finally { def.end(); }
    }

    public byte[] decompress(byte[] src, int off, int len, int originalLen) {
        var inf = new Inflater();
        try {
            inf.setInput(src, off, len);
            byte[] buf = new byte[originalLen];
            inf.inflate(buf);
            return buf;
        } catch (DataFormatException e) {
            throw new UncheckedIOException(new IOException("Deflate decompression failed", e));
        } finally { inf.end(); }
    }

    public byte codecId() { return 0x02; }
}
```

## sources

1. [Compression Algorithms You Probably Inherited](https://dev.to/konstantinas_mamonas/compression-algorithms-you-probably-inherited-gzip-snappy-lz4-zstd-36h0) — comprehensive comparison of gzip, Snappy, LZ4, ZSTD with database use-case guidance
2. [LZ4 GitHub Repository](https://github.com/lz4/lz4) — reference C implementation, block and frame format specifications, benchmark data
3. [Apache Cassandra Compression](https://cassandra.apache.org/doc/4.0/cassandra/operating/compression.html) — SSTable compression configuration with LZ4/ZSTD/Snappy/Deflate support
4. [Compression in ScyllaDB](https://www.scylladb.com/2019/10/04/compression-in-scylla-part-one/) — chunk-based SSTable compression with offset maps
5. [lzbench Compression Benchmark](https://morotti.github.io/lzbench-web/) — multi-algorithm benchmark suite with dataset-specific results

---
*Researched: 2026-03-17 | Next review: 2026-09-17*

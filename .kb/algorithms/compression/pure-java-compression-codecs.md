---
title: "Pure-Java Compression Codec Implementation"
aliases: ["pure-Java LZ4", "pure-Java ZSTD", "Panama FFM compression"]
topic: "algorithms"
category: "compression"
tags: ["lz4", "zstd", "pure-java", "panama-ffm", "jni", "compression", "codec"]
complexity:
  time_build: "varies"
  time_query: "varies"
  space: "O(window size)"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-13"
applies_to: []
related:
  - "algorithms/compression/block-compression-algorithms.md"
  - "algorithms/compression/zstd-dictionary-compression.md"
decision_refs: ["pure-java-lz4-codec", "pure-java-zstd-compressor", "memorysegment-codec-api"]
sources:
  - url: "https://github.com/airlift/aircompressor"
    title: "Aircompressor — Java compression library with native and pure-Java paths"
    accessed: "2026-04-13"
    type: "repo"
  - url: "https://github.com/lz4/lz4-java"
    title: "lz4-java — JNI + pure-Java LZ4 implementation"
    accessed: "2026-04-13"
    type: "repo"
  - url: "https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html"
    title: "Oracle — Foreign Function and Memory API (Java 22)"
    accessed: "2026-04-13"
    type: "docs"
---

# Pure-Java Compression Codec Implementation

## summary

Three strategies exist for compression in a zero-dependency Java library:
(1) pure-Java implementations of the algorithms, (2) Panama FFM downcalls
to system-installed native libraries, (3) `java.util.zip.Deflater` as a
built-in baseline. LZ4 is straightforward to implement in pure Java (~200
lines of core logic) with 30-50% of native throughput — adequate for most
SSTable workloads. ZSTD is far harder: the algorithm involves FSE, Huffman,
and a complex match finder totaling 5000+ lines; however, aircompressor
provides a production-grade pure-Java ZSTD (Java 22+, uses `Unsafe`).
Panama FFM offers a third path: call native libzstd/liblz4 at near-native
speed without JNI compile-time dependencies, with graceful fallback when
the native library is absent at runtime.

## approach-comparison

| Strategy | LZ4 feasible? | ZSTD feasible? | Dictionary support | Runtime dep | Throughput vs native |
|----------|---------------|----------------|--------------------|-------------|---------------------|
| Pure Java (hand-rolled) | Yes (~200 LOC) | No (too complex) | LZ4: possible; ZSTD: no | None | 30-50% compress, 50-70% decompress |
| Pure Java (aircompressor) | Yes | Yes | No (neither codec) | None (shade or copy) | Similar to hand-rolled |
| Panama FFM downcall | Yes | Yes | Full (CDict/DDict) | Native lib at runtime | 90-100% (near-native) |
| `java.util.zip` Deflate | N/A | N/A | No | None (JDK built-in) | ~50 MB/s compress, ~300 MB/s decompress |

## pure-java-lz4

### feasibility

LZ4 block compression is a simple LZ77 variant: a 4096-entry hash table,
4-byte minimum match, 16-bit offset encoding, and a token-literal-match
output format. The reference C implementation is ~500 lines; a Java port
is ~200 lines of core logic. Multiple production codebases ship pure-Java
LZ4: Apache Kafka, Apache Lucene, lz4-java (safe fallback), aircompressor.

### performance-expectations

Pure-Java LZ4 achieves roughly:
- **Compression**: 200-400 MB/s (vs ~780 MB/s native) — 30-50% of native
- **Decompression**: 800-1500 MB/s (vs ~4970 MB/s native) — 15-30% of native

Decompression is the larger gap because native LZ4 exploits unaligned
memory access and SIMD copy loops that pure Java cannot replicate. For
SSTable reads at 4-64 KB block sizes, 800+ MB/s decompression is rarely
the bottleneck — disk I/O dominates.

### implementation-complexity

```
hash_table[4096]  // 4-byte sequence → position mapping
for each position p:
    h = hash(input[p..p+4])
    candidate = hash_table[h]
    if match(candidate, p) and offset < 65536:
        emit_token(literal_count, match_length - 4)
        emit_literals(pending)
        emit_offset_le16(p - candidate)
    hash_table[h] = p
emit_final_literals()  // last 5 bytes always literal
```

Decompression is simpler — no hash table, just token parsing and
`System.arraycopy` for literal and match copies. Total: ~300 lines
including edge-case handling (overlapping matches, length overflow bytes).

### dictionary-support

LZ4 dictionary compression pre-seeds the match window with up to 64 KB of
dictionary data. The compressor treats the dictionary as bytes preceding
the input — matches can reference offsets into the dictionary. This is
implementable in pure Java by prepending dictionary bytes to the hash table
initialization pass. Note: lz4-java does NOT expose dictionary APIs in its
Java path; a hand-rolled implementation would need to add this.

## pure-java-zstd

### feasibility

ZSTD is dramatically more complex than LZ4. The algorithm combines:

1. **LZ77 match finder** with configurable search depth and window size
2. **FSE (Finite State Entropy)** — an ANS-family entropy coder for
   encoding literal lengths, match lengths, and offsets
3. **Huffman coding** — used for literal bytes (separate from FSE)
4. **Repeat offset cache** — tracks last 3 match offsets for short encoding
5. **Sequence encoding** — interleaved bitstreams for three symbol types

A correct implementation is 5000+ lines. Hand-rolling this for jlsm is
not recommended — the risk of subtle correctness bugs in the entropy
coding paths is high, and maintenance burden is substantial.

### aircompressor as reference

The aircompressor library (github.com/airlift/aircompressor) provides
production-grade pure-Java ZSTD compression and decompression. Key facts:

- Requires Java 22+ (uses `sun.misc.Unsafe` for memory access)
- Dual native/Java paths: `ZstdNativeCompressor` via Panama FFM,
  `ZstdJavaCompressor` as pure-Java fallback
- **No dictionary support** in the Java path — the ZDICT training API
  and CDict/DDict lifecycle are native-only
- Also provides pure-Java LZ4, Snappy, LZO

Aircompressor could be vendored (source-copied) into jlsm to avoid a
binary dependency, but dictionary compression — critical for the
per-level codec policy — would remain unavailable in the pure-Java path.

### dictionary-support-gap

ZSTD dictionary compression requires:
- Training: the COVER/FastCOVER algorithm to produce dictionary bytes
- CDict: pre-digested compression dictionary with FSE/Huffman tables
- DDict: pre-digested decompression dictionary

The training algorithm alone is ~1500 lines of C. No pure-Java
implementation of ZSTD dictionary training exists in any known library.
This is the critical gap: jlsm's per-level codec policy uses ZSTD
dictionaries for cold levels, and no pure-Java path can provide this.

## panama-ffm-approach

Panama FFM (finalized Java 22, JEP 454) enables `MethodHandle` downcalls
to native functions — no JNI, no compiled wrapper, no build-time native
dependency. Load the library at runtime via `SymbolLookup.libraryLookup()`;
if absent, fall back gracefully to pure-Java codecs.

```
// Conceptual downcall setup
lookup = SymbolLookup.libraryLookup("libzstd.so")
descriptor = (LONG, ADDRESS, LONG, ADDRESS, LONG, INT)
handle = Linker.nativeLinker().downcallHandle(lookup.find("ZSTD_compress"), descriptor)
```

**Key advantages over JNI**: no compile-time native dependency, runtime-
optional detection, lower call overhead than JNI, zero-copy for off-heap
`MemorySegment` data already in `ArenaBufferPool`.

**Dictionary support**: Panama FFM can call the full ZSTD dictionary API
(`ZDICT_trainFromBuffer`, `ZSTD_createCDict`, `ZSTD_decompress_usingDDict`).
CDict/DDict pointers are managed as `MemorySegment` with explicit `Arena`
lifecycle. This is the only path providing dictionary support without a
JNI compile-time dependency.

## recommendation-for-jlsm

**Tiered strategy with Panama FFM as primary path:**

1. **Panama FFM to native libzstd/liblz4** — primary path. Full dictionary
   support, near-native speed, no compile-time dependency, runtime-optional.
2. **Pure-Java LZ4** — hand-rolled fallback (~300 lines). LZ4 dictionary
   possible but limited (64 KB, no entropy pre-seeding).
3. **`java.util.zip` Deflate** — always-available baseline.
4. **Pure-Java ZSTD** — defer. Evaluate vendoring aircompressor only if
   needed, accepting the dictionary gap.

The critical constraint is dictionary compression for cold SSTable levels.
Only the Panama FFM path provides this without JNI. When native libs are
absent, fall back to LZ4 (pure Java) or Deflate.

## sources

1. [Aircompressor](https://github.com/airlift/aircompressor) — Java 22+ compression library with pure-Java and native (Panama FFM) paths for ZSTD, LZ4, Snappy, LZO
2. [lz4-java](https://github.com/lz4/lz4-java) — JNI + safe-Java + Unsafe LZ4; discontinued Dec 2025, fork at yawkat/lz4-java
3. [Oracle FFM API docs](https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html) — Panama FFM downcall pattern, SymbolLookup, Linker, Arena lifecycle

---
*Researched: 2026-04-13 | Next review: 2026-10-13*

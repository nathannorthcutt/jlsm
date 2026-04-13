---
title: "ZSTD Dictionary Compression in Storage Engines"
aliases: ["dictionary compression", "zstd dictionaries", "trained dictionaries", "CDict", "DDict"]
topic: "algorithms"
category: "compression"
tags: ["zstd", "dictionary", "compression", "sstable", "compaction", "small-blocks", "lz4"]
complexity:
  time_build: "O(n) — dictionary training samples all blocks in an SST file"
  time_query: "O(n) — decompression with pre-digested dictionary (CDict/DDict startup amortized)"
  space: "O(dict_size) — one dictionary per SST file stored in meta-block"
research_status: "active"
confidence: "high"
last_researched: "2026-04-12"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/CompressionCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
related:
  - "algorithms/compression/block-compression-algorithms.md"
  - "algorithms/compression/wal-compression-patterns.md"
decision_refs:
  - "codec-dictionary-support"
  - "compression-codec-api-design"
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/Dictionary-Compression"
    title: "RocksDB Dictionary Compression Wiki"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://facebook.github.io/zstd/zstd_manual.html"
    title: "ZSTD 1.5.1 Manual"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://github.com/facebook/zstd"
    title: "Zstandard GitHub Repository"
    accessed: "2026-04-12"
    type: "repo"
  - url: "https://github.com/lz4/lz4-java/issues/81"
    title: "LZ4-Java Dictionary Support Issue #81"
    accessed: "2026-04-12"
    type: "repo"
---

# ZSTD Dictionary Compression in Storage Engines

## summary

Dictionary compression pre-seeds a compressor's match history and entropy
tables with patterns learned from representative data. On small blocks
(1-64 KB typical in SSTable storage), this dramatically improves compression
ratios — 2-2.5x better than without a dictionary on structured data. The
dictionary is trained once per SST file from sampled blocks during compaction,
stored as a meta-block in the SST file, and loaded by the reader before
decompressing any block. ZSTD dictionaries are thread-safe once created
(CDict/DDict are read-only) and identified by a 32-bit dictionary ID
embedded in each compressed frame.

## how-it-works

### the-problem-with-small-blocks

Block compression in LSM trees operates on individual data blocks (typically
4-64 KB). Each block is compressed independently for random-access reads.
At these sizes, the compressor's sliding window has insufficient data to
build a good match history — it literally hasn't seen enough bytes to find
repeated patterns. Result: poor compression ratios on small blocks.

### dictionary-solution

A dictionary is a byte buffer (typically 16-112 KB) containing common
patterns extracted from representative data. Before compressing a block,
the compressor loads the dictionary into its internal state, effectively
giving it a "head start" of pre-observed patterns. The same dictionary
must be available at decompression time.

```
Training phase (compaction time):
  Sample blocks from SST → ZDICT_trainFromBuffer() → dictionary bytes

Compression (write path):
  dictionary + block data → ZSTD_compress_usingCDict() → compressed block
  (dictionary ID embedded in each frame header)

Decompression (read path):
  Load dictionary from SST meta-block → ZSTD_createDDict()
  compressed block + DDict → ZSTD_decompress_usingDDict() → original block
```

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Dictionary size | Byte size of trained dictionary | 16-112 KB | Larger = better ratio, more memory, slower load |
| Training sample size | Total bytes fed to trainer | 100x dict size | More samples = better dictionary quality |
| Training algorithm | COVER vs FastCOVER vs Legacy | COVER default | COVER: best quality, most CPU; FastCOVER: good tradeoff |
| Dictionary ID | 32-bit identifier in frame header | Auto-assigned | 0 = no ID (private dictionaries) |
| Compression level | ZSTD level used with dictionary | 1-22 | CDict digests at a specific level |

## algorithm-steps

### dictionary-training

1. **Sample collection**: During SST file generation (compaction), buffer
   uncompressed data blocks in memory up to `max_dict_buffer_bytes`
2. **Uniform sampling**: Select blocks uniformly/randomly from the buffered
   data to form the training corpus
3. **COVER algorithm**: Analyze d-mer (substring) frequencies across all
   samples, select byte sequences that appear most frequently across
   different samples (not just within one)
4. **Dictionary output**: A byte buffer containing: common byte patterns
   (for LZ77 match seeding) + pre-built Huffman trees + pre-built FSE
   probability tables (for entropy coding warm-start)

### per-sst-lifecycle (rocksdb-reference)

1. **Compaction starts**: subcompaction begins writing a new SST file
2. **Block buffering**: all data blocks are buffered uncompressed in memory
   (charged to block cache since RocksDB 6.25)
3. **Dictionary training**: after all blocks are buffered, train dictionary
   from sampled blocks using ZSTD trainer
4. **Block compression**: compress each buffered block using the trained
   dictionary via `ZSTD_compress_usingCDict()`
5. **Dictionary storage**: write dictionary as a meta-block in the SST file
   (raw bytes, alongside index and filter blocks)
6. **File finalization**: flush compressed blocks + dictionary meta-block +
   index + footer

### reader-side-loading

1. **SST file opened**: reader parses footer, locates meta-block index
2. **Dictionary loaded**: dictionary meta-block read and digested into
   `ZSTD_DDict` (can be cached in block cache or pinned in memory)
3. **Block decompression**: each block decompressed using the pre-loaded
   DDict via `ZSTD_decompress_usingDDict()`

## implementation-notes

### zstd-c-api-shape

```c
// Training (called once per SST file during compaction)
size_t ZDICT_trainFromBuffer(void* dictBuffer, size_t dictBufferCapacity,
    const void* samplesBuffer, const size_t* samplesSizes, unsigned nbSamples);

// Compression dictionary (pre-digested, reusable, thread-safe)
ZSTD_CDict* ZSTD_createCDict(const void* dict, size_t dictSize, int compressionLevel);
size_t ZSTD_compress_usingCDict(ZSTD_CCtx* cctx,
    void* dst, size_t dstCapacity,
    const void* src, size_t srcSize,
    const ZSTD_CDict* cdict);

// Decompression dictionary (pre-digested, reusable, thread-safe)
ZSTD_DDict* ZSTD_createDDict(const void* dict, size_t dictSize);
size_t ZSTD_decompress_usingDDict(ZSTD_DCtx* dctx,
    void* dst, size_t dstCapacity,
    const void* src, size_t srcSize,
    const ZSTD_DDict* ddict);

// Dictionary ID extraction (for routing)
unsigned ZSTD_getDictID_fromFrame(const void* src, size_t srcSize);
unsigned ZSTD_getDictID_fromDict(const void* dict, size_t dictSize);
```

### thread-safety

CDict and DDict are **read-only after creation** and can be safely shared
across threads. CCtx and DCtx (compression/decompression contexts) are
**not** thread-safe — one per thread. This maps well to the jlsm
`CompressionCodec` contract which requires stateless, thread-safe
implementations: the codec instance holds a shared CDict/DDict, and
creates per-call CCtx/DCtx internally.

### dictionary-identification

Each compressed frame embeds the dictionary ID (32-bit) in its header.
The decompressor can extract this ID via `ZSTD_getDictID_fromFrame()`
before decompressing. This enables:
- **Validation**: confirm the reader has the correct dictionary
- **Routing**: select the right DDict from a cache of dictionaries
- **Graceful error**: fail with a clear message if dictionary is missing

A dictionary ID of 0 means "private dictionary" — the caller is
responsible for knowing which dictionary to use (no ID in frame).

### data-structure-requirements

- **Dictionary buffer**: raw bytes, typically 16-112 KB per SST file
- **CDict**: digested form, larger than raw dictionary (includes
  pre-built hash tables). Size estimable via `ZSTD_estimateCDictSize()`
- **DDict**: digested decompression dictionary, smaller than CDict.
  Can be loaded by-reference to avoid copying dictionary bytes.
- **Block cache integration**: RocksDB caches DDict in block cache
  alongside index/filter blocks, with configurable priority

### edge-cases-and-gotchas

- **Dictionary larger than block**: can *hurt* compression on very small
  blocks (< 4 KB). ZSTD treats (dictionary + block) as the input size
  for parameter selection, which may cause it to skip short matches.
  Known issue reported in facebook/zstd#2008.
- **Memory buffering**: training requires buffering ALL uncompressed
  blocks for an SST file in memory before any can be compressed.
  RocksDB charges this to block cache since v6.25.
- **Dictionary staleness**: a dictionary trained on one data distribution
  becomes less effective if the data changes significantly. RocksDB
  mitigates this by training per-SST-file during compaction.
- **Compression level binding**: CDict is digested at a specific
  compression level. Changing the level requires recreating the CDict.

## complexity-analysis

### build-phase

- Dictionary training: O(n) where n = total sample size. COVER algorithm
  is more CPU-intensive than FastCOVER but produces better dictionaries.
- Per-block compression with dictionary: same O(n) as without, but with
  pre-seeded state reducing the cold-start penalty

### query-phase

- DDict creation: one-time cost per SST file open (or cached)
- Per-block decompression with dictionary: same O(n) as without, with
  pre-seeded decode tables reducing setup cost

### memory-footprint

- Raw dictionary: 16-112 KB per SST file
- CDict: ~2-3x raw dictionary size (includes hash tables)
- DDict: ~1.5x raw dictionary size
- Block buffering during training: up to full SST file size (can be tens
  of MB); this is the dominant memory cost

## tradeoffs

### strengths

- **Dramatic ratio improvement on small blocks**: 2-2.5x better on
  structured data at 1 KB block sizes, meaningful improvement at 4-64 KB
- **Self-describing**: dictionary ID in frame header enables validation
  and routing without external metadata
- **Thread-safe sharing**: CDict/DDict are read-only, shareable across
  all compression/decompression threads
- **Per-SST adaptation**: training per file means the dictionary adapts
  to the data distribution of that specific compaction output

### weaknesses

- **Memory buffering**: must buffer all blocks before compressing any
  (cannot stream compress blocks as they arrive)
- **Write path complexity**: training adds CPU cost and memory pressure
  during compaction
- **Reader must have dictionary**: decompression fails without the
  correct dictionary — the dictionary is not optional metadata
- **Not useful for WAL**: WAL records arrive one at a time; no
  opportunity to train a dictionary from the batch

### compared-to-alternatives

- **No dictionary (plain ZSTD)**: simpler, no buffering, but poor ratio
  on small blocks. See [block-compression-algorithms.md](block-compression-algorithms.md)
- **LZ4 with dictionary**: LZ4 supports dictionaries (limited to 64 KB
  dictionary, last 64 KB used). Improves ratio but less dramatically
  than ZSTD because LZ4 has no entropy coding to pre-seed. Java lz4-java
  library does **not** expose dictionary API (JNI bindings only). See
  [lz4-java issue #81](https://github.com/lz4/lz4-java/issues/81).
- **Shared dictionary across files**: simpler (one dictionary for all
  SSTs) but less effective — data distributions vary across compaction
  levels and time. RocksDB explicitly chose per-SST dictionaries.

## practical-usage

### when-to-use

- **Cold/archival SSTable levels**: where storage cost matters more than
  write-path latency. RocksDB production pattern: LZ4 for hot levels,
  ZSTD+dictionary for bottommost levels.
- **Structured data with cross-block repetition**: JSON documents, log
  entries, protobuf records — data with repeated field names and
  structural patterns across blocks.
- **Small block sizes (4-16 KB)**: where plain compression achieves
  poor ratios due to insufficient context.

### when-not-to-use

- **WAL records**: individual records with no batch context for training.
  Use plain ZSTD or Deflate. See [wal-compression-patterns.md](wal-compression-patterns.md).
- **Already-compressed or random data**: dictionaries cannot help with
  encrypted content, media, or truly random bytes.
- **Memory-constrained environments**: the block buffering requirement
  during training can be prohibitive.
- **Hot/write-heavy levels**: the training CPU cost and memory buffering
  adds latency on the write path. Use LZ4 or plain ZSTD for L0-L2.

### rocksdb-production-config

```
// RocksDB recommended configuration
options.compression = kLZ4Compression;                    // L0-L(n-1): fast
options.bottommost_compression = kZSTD;                   // Ln: ratio
options.bottommost_compression_opts.max_dict_bytes = 16384;  // 16 KB dictionary
options.bottommost_compression_opts.zstd_max_train_bytes = 1638400;  // 100x dict size
```

## reference-implementations

| Library | Language | URL | Notes |
|---------|----------|-----|-------|
| RocksDB | C++ | [Dictionary Compression Wiki](https://github.com/facebook/rocksdb/wiki/Dictionary-Compression) | Per-SST dictionary, meta-block storage, block cache integration |
| facebook/zstd | C | [github.com/facebook/zstd](https://github.com/facebook/zstd) | Reference ZSTD implementation with full dictionary API |
| zstd-jni | Java | [github.com/luben/zstd-jni](https://github.com/luben/zstd-jni) | JNI wrapper exposing CDict/DDict + training APIs |
| aircompressor | Java | [github.com/airlift/aircompressor](https://github.com/airlift/aircompressor) | Pure-Java ZSTD (no dictionary support in Java path) |

## code-skeleton

```java
// Conceptual dictionary-aware codec lifecycle for an SST writer
interface DictionaryAwareCodec extends CompressionCodec {
    // Phase 1: collect training samples during block generation
    void addTrainingSample(MemorySegment uncompressedBlock);

    // Phase 2: train dictionary from collected samples
    void trainDictionary();

    // Phase 3: compress blocks using trained dictionary
    // (inherits compress() from CompressionCodec)

    // Phase 4: serialize dictionary for storage in SST meta-block
    MemorySegment dictionaryBytes();

    // Reader-side: create codec from stored dictionary
    static DictionaryAwareCodec fromDictionary(MemorySegment dictBytes, int level);
}
```

## sources

1. [RocksDB Dictionary Compression Wiki](https://github.com/facebook/rocksdb/wiki/Dictionary-Compression) — canonical reference for per-SST dictionary lifecycle in LSM storage
2. [ZSTD 1.5.1 Manual](https://facebook.github.io/zstd/zstd_manual.html) — CDict/DDict API, dictionary ID, thread safety, memory estimation
3. [Zstandard GitHub](https://github.com/facebook/zstd) — reference implementation, ZDICT training API in zdict.h
4. [LZ4-Java Dictionary Issue #81](https://github.com/lz4/lz4-java/issues/81) — dictionary support status in Java LZ4

---
*Researched: 2026-04-12 | Next review: 2026-10-12*

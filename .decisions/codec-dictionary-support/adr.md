---
problem: "codec-dictionary-support"
date: "2026-04-12"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/CompressionCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/CompressionMap.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
---

# ADR — Codec Dictionary Support

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| ZSTD Dictionary Compression | Dictionary lifecycle, per-SST training, CDict/DDict API | [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md) |
| Block Compression Algorithms | Codec survey, pure-Java feasibility | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

---

## Files Constrained by This Decision
- `CompressionCodec.java` — add `zstd()` static factory methods with tiered Panama/pure-Java detection
- `TrieSSTableWriter.java` — add block buffering mode for dictionary training, dictionary meta-block storage
- `TrieSSTableReader.java` — load dictionary meta-block at file open, create dictionary-bound codec
- `CompressionMap.java` — no changes (codec ID per-block is sufficient; dictionary is per-file)
- `SSTableFormat.java` — dictionary meta-block handling in footer/metadata

## Problem
How should the `CompressionCodec` API and SSTable writer support dictionary
compression? Dictionary compression dramatically improves ratios on small blocks
(2-2.5x on structured data) by pre-seeding the compressor with patterns learned
from representative data. Supporting this requires a write-path lifecycle change
(buffer blocks → train dictionary → compress → store dictionary as meta-block),
tiered native/pure-Java codec integration via Panama FFM, and API surface that
fits the existing stateless codec contract.

## Constraints That Drove This Decision
- **Stateless codec contract (F02.R2/R7)**: The `CompressionCodec` interface is
  stateless and thread-safe. Dictionary training is inherently stateful — this
  tension determines where the lifecycle logic lives.
- **Cross-platform readability**: SSTables written with native ZSTD + dictionary
  must be readable by a pure-Java decompressor. Dictionary bytes must be stored
  in the SST file as a meta-block.
- **No mandatory dependencies**: Native `libzstd` is optional. The library must
  compile, test, and run without it. Panama FFM probes at runtime.

## Decision
**Chosen approach: Writer-Orchestrated, Codec Stays Stateless**

The codec remains pure: dictionary bytes are constructor-time configuration.
The dictionary lifecycle (buffering, training, storage) lives in the SSTable
writer and a `ZstdDictionaryTrainer` utility. A tiered Panama FFM detection
pattern (matching the existing `TierDetector` for JSON SIMD) provides native
ZSTD when available with pure-Java decompressor fallback.

### Codec API

```java
// New static factories on CompressionCodec
static CompressionCodec zstd()                                    // tiered, no dictionary
static CompressionCodec zstd(int level)                           // tiered, with level
static CompressionCodec zstd(MemorySegment dictionary)            // tiered, with dictionary
static CompressionCodec zstd(int level, MemorySegment dictionary) // tiered, both
```

Codec ID: `0x03` (ZSTD — reserved in the existing codec ID table).

The codec instance holds the dictionary internally. `compress()` and
`decompress()` remain stateless and thread-safe — the dictionary is read-only
shared state (equivalent to ZSTD's `CDict`/`DDict` which are thread-safe).

### Tiered Detection (Panama FFM)

```
Tier 1 — Panama FFM: probe for libzstd.so via Linker.nativeLinker()
  Full ZSTD: all 22 compression levels, dictionary training, CDict/DDict
  Downcall handles for: ZSTD_compress_usingCDict, ZSTD_decompress_usingDDict,
  ZDICT_trainFromBuffer

Tier 2 — Pure-Java decompressor (~1500-2200 lines + ~150-175 for dictionary):
  Decompress only. Handles dictionary frames (dictionary ID in frame header,
  pre-seeded FSE/Huffman tables, content prefix for match history).
  Cannot compress or train dictionaries.

Tier 3 — Deflate fallback:
  Current DeflateCodec. Used for compression when native lib unavailable.
```

### Dictionary Trainer

```java
// New utility class — native-only (Tier 1)
public final class ZstdDictionaryTrainer {
    void addSample(MemorySegment uncompressedBlock);
    MemorySegment train(int maxDictBytes);  // calls ZDICT_trainFromBuffer via Panama
    static boolean isAvailable();           // true if native lib detected
}
```

### Writer Integration

When dictionary compression is enabled:
1. Writer buffers all uncompressed data blocks in memory
2. After all blocks generated, samples blocks uniformly for training
3. Trains dictionary via `ZstdDictionaryTrainer` (native only)
4. Creates dictionary-bound codec: `CompressionCodec.zstd(level, dictionary)`
5. Compresses all buffered blocks using the dictionary-bound codec
6. Stores dictionary as a meta-block in the SSTable file
7. Writes compressed blocks + dictionary + index + footer

When native lib is unavailable, dictionary training is skipped — writer
compresses blocks as they arrive using Deflate (current behavior).

### Reader Integration

1. Reader parses footer, detects dictionary meta-block presence
2. Loads dictionary bytes from meta-block
3. Creates dictionary-bound codec: `CompressionCodec.zstd(dictionary)`
4. Decompresses blocks using dictionary (works in both Tier 1 and Tier 2)

### SSTable Format

Dictionary stored as an optional meta-block alongside index and bloom filter.
Presence indicated in the footer metadata. Backward compatible: readers that
don't understand the dictionary meta-block skip it; files without dictionaries
are unchanged. Format version handling TBD during implementation — may be
additive to v3 or require v4.

## Rationale

### Why Writer-Orchestrated
- **Preserves stateless contract**: The `CompressionCodec` interface gains no
  new methods. Dictionary bytes are constructor-time configuration, same as
  compression level. F02.R2 and F02.R7 are satisfied.
- **Explicit lifecycle**: Dictionary training is visible in the writer — no
  hidden state mutations inside the codec.
- **Reuses established pattern**: The tiered Panama FFM detection mirrors the
  JSON SIMD `TierDetector` (Tier 1 Panama, Tier 2 Vector API, Tier 3 scalar).

### Why not Dictionary-Aware Codec Subtype (Candidate B)
- **Breaks F02.R2/R7**: Adding `addTrainingSample()` introduces shared mutable
  state. The codec would no longer be stateless or safe to share across threads.

### Why not Factory Pattern (Candidate C)
- **Premature abstraction**: Only ZSTD needs dictionary support. A factory adds
  indirection and changes the writer builder API without benefit today. If a
  second compression-context pattern emerges, reconsider.

## Implementation Guidance

Key parameters from [`zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md):
- Dictionary size: 16-112 KB per SST file (configurable via writer builder)
- Training sample size: aim for ~100x dictionary size in training data
- Training algorithm: COVER (via ZDICT_trainFromBuffer)
- Dictionary is per-SST file, not shared across files

Known edge cases from [`zstd-dictionary-compression.md#edge-cases-and-gotchas`](../../.kb/algorithms/compression/zstd-dictionary-compression.md#edge-cases-and-gotchas):
- Dictionary larger than block can hurt compression on very small blocks (<4 KB)
- Block buffering requires bounded memory — configurable max, fail if exceeded
- CDict is digested at a specific compression level — level change requires new CDict

Pure-Java decompressor dictionary support (~150-175 lines):
- `ZstdDictionary` parser: reuses existing `FseTableReader` and `Huffman` decoders
- Pre-seed repeat offsets and entropy tables in `reset()`
- Content prefix via adjusted `outputAbsoluteBaseAddress`
- Decode loop requires zero changes

## What This Decision Does NOT Solve
- Dictionary sharing across SSTable files — each file trains its own dictionary
- Dictionary compression for WAL records — no batch context for training
- Pure-Java ZSTD compressor — only decompressor is pure-Java; compression requires native lib
- Adaptive compression strategy selection (LZ4 hot / ZSTD+dict cold) — compaction policy concern
- ZSTD without dictionaries as a general-purpose codec — separate from dictionary lifecycle

## Conditions for Revision
This ADR should be re-evaluated if:
- A second compression-context pattern emerges (e.g., adaptive encoding, encryption-then-compression) — factory pattern may be warranted
- Cross-SSTable dictionary sharing becomes desirable for storage efficiency
- Pure-Java ZSTD compression becomes feasible and eliminates the need for tiered detection
- The SSTable format evolution to support dictionary meta-blocks proves incompatible with the current v3 design

---
*Confirmed by: user deliberation | Date: 2026-04-12*
*Full scoring: [evaluation.md](evaluation.md)*

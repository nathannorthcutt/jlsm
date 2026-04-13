---
{
  "id": "F02",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["serialization", "storage"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "sstable-block-compression-format",
    "compression-codec-api-design",
    "codec-dictionary-support",
    "compaction-recompression"
  ],
  "kb_refs": [
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": []
}
---

# F02 — Block-Level SSTable Compression

## Requirements

### Compression codec contract

R1. A compression codec must expose a unique byte identifier (codec ID) that is stored per-block in the on-disk metadata. Codec IDs 0x00-0x7F are reserved for library-provided codecs. Codec IDs 0x80-0xFF are available for consumer-provided implementations. No runtime enforcement prevents consumers from using reserved IDs; this is a convention-based reservation.

R2. Codec compression must accept a byte array with offset and length parameters and return a new byte array containing the compressed output. The operation must be stateless -- no shared mutable state between calls.

R3. Codec decompression must accept a byte array with offset, length, and expected uncompressed length parameters and return a new byte array of exactly the expected uncompressed length. If the decompressed output does not match the expected length, the codec must throw an unchecked I/O exception.

R4. The NONE codec (ID 0x00) must be a passthrough: compression returns a copy of the input region, decompression returns a copy of the input region. For NONE codec decompression, the compressed length must equal the uncompressed length; a mismatch must produce an unchecked I/O exception.

R5. The DEFLATE codec (ID 0x02) must use the JDK's java.util.zip compression with a configurable level (0-9). Level values outside 0-9 must be rejected at construction time with an illegal argument exception.

R6. DEFLATE codec operations must allocate and release native compression resources (Deflater/Inflater) within each call. Native resources must be released in a finally block, never deferred to garbage collection.

R7. All codec operations must be safe to call concurrently from multiple threads without external synchronization. Statelessness (no shared mutable state) is the required mechanism.

### Input validation

R8. All codec methods must reject null input arrays with a null pointer exception.

R9. All codec methods must validate offset and length bounds using overflow-safe arithmetic. Both offset and length must be non-negative. The bounds check must not use addition of offset and length (which can overflow); equivalent subtraction-based patterns (e.g. `offset > input.length - length` after confirming both are non-negative) are acceptable. Out-of-bounds parameters must produce an illegal argument exception.

R10. Decompression must reject negative uncompressed length values with an illegal argument exception, not a negative array size exception or other internal error.

### SSTable file format v2

R11. The v2 SSTable file layout must be: data blocks, compression map, key index, bloom filter, footer (64 bytes). The footer must be the last 64 bytes of the file.

R12. The v2 footer must contain eight 8-byte big-endian fields: compression map offset, compression map length, key index offset, key index length, bloom filter offset, bloom filter length, entry count, and magic number (0x4A4C534D53535402). All offset and length fields are long-width values.

R13. The compression map must consist of a 4-byte big-endian block count followed by one 17-byte entry per block. Each entry contains: block offset (8 bytes), compressed size (4 bytes), uncompressed size (4 bytes), and codec ID (1 byte). All multi-byte values are big-endian.

R14. Key index entries in v2 must reference blocks by index into the compression map and an intra-block byte offset within the decompressed block, not by absolute file offset. The entry format must be: key length (4 bytes), key bytes (variable), block index (4 bytes), intra-block offset (4 bytes).

### Backward compatibility

R15. A v2-capable reader must detect v1 SSTables by reading the magic number from the final 8 bytes of the file. If the magic matches v1 (0x4A4C534D53535401), the reader must fall back to v1 reading logic with no decompression. If the magic matches neither v1 nor v2, the reader must throw an IOException. After detecting v2 magic, the reader must verify the file is at least 64 bytes (v2 footer size) before attempting to read the full footer.

R16. A v1-only reader encountering a v2 file must fail with a descriptive IOException identifying the unknown magic, not with a silent data corruption or internal error.

### Self-describing format and codec resolution

R17. The reader must determine compression codec and block sizes entirely from on-disk metadata. No external configuration (tree config, environment variable, etc.) may be required to read a v2 file beyond providing the set of available codec implementations.

R18. The reader must build a codec ID-to-implementation map from the codec implementations provided at open time. Duplicate codec IDs must be rejected with an illegal argument exception. The check must be explicit (not reliant on silent map overwrite).

R19. The reader must auto-include the NONE codec (ID 0x00) in its codec map only if no codec with ID 0x00 is already present in the provided list. The writer may fall back to NONE for any block where compression does not reduce size, so every v2 file may contain NONE-coded blocks.

R20. If the compression map contains a codec ID not present in the reader's codec map, the reader must throw an IOException identifying the unknown codec ID. This must be a runtime check, not an assertion, because codec IDs come from untrusted on-disk data.

R21. Null elements in the reader's codec list must be rejected with a descriptive exception (including the element index), not a raw NullPointerException from internal iteration.

### Writer behavior

R22. The writer must accept a single compression codec for the entire file. Each data block must be compressed independently using that codec. The NONE fallback per R23 is the sole exception -- a single writer run may produce blocks with two codec IDs (the configured codec and NONE).

R23. If the compressed output for a block is greater than or equal to the uncompressed block size (including block header), the writer must store the block uncompressed and record codec ID 0x00 (NONE) in the compression map entry for that block. The writer must not store an expansion.

R24. The writer must build compression map entries during the write pass and serialize the complete map after all data blocks have been written, before the key index.

### Compression map validation

R25. Compression map entries must reject negative block offset, compressed size, and uncompressed size at construction time with an illegal argument exception.

R26. Compression map entries must reject impossible size combinations: compressed size of 0 with uncompressed size greater than 0 (cannot decompress nothing into something). For non-NONE codecs, uncompressed size of 0 with compressed size greater than 0 must also be rejected. Empty entries (both sizes 0, codec ID 0x00) are valid but the writer must not produce them; the reader must handle them gracefully (skip with no entries).

R27. Compression map serialization and deserialization must use long arithmetic for size calculations (4L + blockCount * 17L) to prevent integer overflow when block count is large. Deserialization must reject overflow or results exceeding Integer.MAX_VALUE with an illegal argument exception. Serialization must reject the same conditions with an illegal state exception (the map's own state is the problem, not a method argument).

R28. Compression map deserialization must reject negative block counts with an illegal argument exception, not silently return an empty map.

### Footer validation

R29. Footer construction from on-disk data must validate all offset and length fields are non-negative. Negative values from corrupt data must produce an IOException, not cascade to uninformative internal exceptions.

R30. Footer validation must guard against long-to-int truncation across all consumers of footer field values -- not just within the footer itself, but in every code path that uses footer offsets and lengths for I/O operations, buffer allocation, or array indexing.

### File offset and length width

R31. All file offsets and section lengths in the read path must be handled as long values. No file offset, section offset, or section length may be narrowed to int-width at any point in the read pipeline. Both eager and lazy reader modes must support SSTables up to Long.MAX_VALUE bytes.

R32. Intra-block offsets and block indices may use int-width values, since individual blocks are bounded by the configured block size (default 4 KiB). The maximum number of blocks per SSTable and the maximum uncompressed size of a single block must not exceed Integer.MAX_VALUE. This is the only permitted use of int-width values for positional data in the SSTable read/write path.

### Key index validation

R33. When reading a v2 key index, the reader must validate that each entry's block index is within [0, blockCount) and each intra-block offset is non-negative. Invalid values from corrupt data must produce an IOException with a descriptive message.

### Footer section ordering

R34. Footer validation must verify that file sections do not overlap: compression map must end before key index starts, key index must end before bloom filter starts, and bloom filter must end before the footer. Specifically: mapOffset + mapLength <= idxOffset, idxOffset + idxLength <= fltOffset, and fltOffset + fltLength <= fileSize - footerSize.

### Concurrency

R35. Lazy reader instances sharing a file channel must synchronize the position-then-read sequence to prevent concurrent reads from interleaving and producing corrupt data or wrong-offset reads. Eager readers are inherently thread-safe for reads (final reference to pre-loaded data) and require no synchronization.

### Block cache integration

R36. The block cache must store decompressed block content. Compression and decompression must occur below the cache layer: reads that hit the cache must return decompressed data without invoking the codec. Reads that miss the cache must decompress before caching.

### Tree builder integration

R37. The tree builder must accept a compression codec configuration, defaulting to NONE (no compression). The builder must propagate the codec to both writer and reader factories so that all SSTables produced by the tree use the configured codec and the reader can decompress them.

### Compaction integration

R38. The compactor must create output writers via an SSTableWriterFactory, not by direct constructor invocation. The factory receives the SSTable ID, target Level, and output Path, and returns a writer configured with the appropriate compression codec for that level. The compactor must not contain codec selection logic — it delegates writer creation entirely to the factory. [Amended by compaction-recompression ADR: was "must use the same compression codec as the tree".]

R39. The tree builder must support a per-level compression policy via a Function<Level, CompressionCodec> (or equivalent). When set, the policy determines the codec for each level. When not set, all levels use the single configured codec (backward compatible). The builder must wire the policy into the SSTableWriterFactory used by both flush and compaction paths. [Amended by compaction-recompression ADR: was "single codec for writing".]

R39a. The compactor must not carry implicit state between output writers. Each writer created by the factory must be independent — no shared buffers, no cross-file dictionary references, no assumptions about prior writers in the same compaction run. This enables the factory to return different codec configurations per output file without coordination. [New: codifies assumption verified during compaction-recompression deliberation.]

### ZSTD codec and tiered detection

R39b. The ZSTD codec (ID 0x03) must use a tiered runtime detection pattern: Tier 1 probes for native libzstd via Panama FFM Linker.nativeLinker() and SymbolLookup; Tier 2 provides a pure-Java ZSTD decompressor (decompression only, no compression); Tier 3 falls back to the DEFLATE codec for compression. Detection must occur once at class-load time and be cached. Detection must catch all exceptions and fall through gracefully to the next tier. [New: from codec-dictionary-support ADR.]

R39c. The pure-Java ZSTD decompressor (Tier 2) must handle dictionary-compressed frames. It must parse the dictionary ID from the frame header, load pre-trained FSE and Huffman tables from dictionary bytes, pre-seed repeat offsets, and prepend dictionary content as match history. The frame-level decode loop must require zero changes — dictionary support is initialization-only. [New: from codec-dictionary-support ADR, verified by feasibility spike.]

R39d. ZSTD CDict and DDict equivalents (native Tier 1) are read-only after creation and must be safely shareable across threads. The codec instance must hold the dictionary internally as constructor-time configuration. The compress() and decompress() methods must remain stateless per F02.R7/F17.R5. [New: from codec-dictionary-support ADR.]

### Dictionary-aware writer lifecycle

R39e. When a dictionary-enabled codec is configured, the SSTable writer must buffer all uncompressed data blocks in memory before compressing any of them. After all blocks are buffered, the writer must sample blocks uniformly for dictionary training, train the dictionary (native Tier 1 only), create a dictionary-bound codec, and compress all buffered blocks. The dictionary training lifecycle must be fully encapsulated inside the writer — callers pass codec configuration at construction, the writer handles buffering and training internally. [New: from codec-dictionary-support ADR.]

R39f. The trained dictionary must be stored as a meta-block in the SSTable file alongside the index and bloom filter. The dictionary meta-block must be loadable from on-disk metadata alone — no external dictionary file or registry. Readers must detect the presence of a dictionary meta-block and load it before decompressing any block. [New: from codec-dictionary-support ADR.]

R39g. The writer's block buffering for dictionary training must be bounded by a configurable maximum (set via the writer builder). If the buffered data exceeds the maximum, the writer must fail with an IOException rather than silently consuming unbounded memory. [New: from codec-dictionary-support ADR.]

R39h. When native libzstd is unavailable (Tier 2 or 3), the writer must skip dictionary training and compress blocks as they arrive using the fallback codec. The writer must not fail at construction time due to missing native library — it must gracefully degrade. [New: from codec-dictionary-support ADR.]

### Error handling

R40. All conditions reachable from untrusted on-disk data (corrupt footer, unknown codec ID, decompression failure, malformed compression map, invalid key index entries) must be checked with runtime logic, not assertions. Assertions are stripped in production and must not be the sole guard for data-dependent conditions.

R41. Corrupted compressed blocks must produce an IOException with a descriptive message, not crash the JVM or propagate an unhandled exception type.

### Silent failure documentation

R42. Compression map deserialization and deflate decompression silently ignore trailing bytes beyond what they consume. This behavior must be documented as a known limitation in the relevant API documentation. If exact-length enforcement is added in the future, it must be gated behind a compatibility flag to avoid breaking existing files.

R43. Iterator behavior after the underlying reader has been closed is undefined. Callers must not rely on hasNext() returning accurate results after close. This must be documented in the reader's public API.

## Cross-References

- ADR: .decisions/sstable-block-compression-format/adr.md
- ADR: .decisions/compression-codec-api-design/adr.md
- KB: .kb/algorithms/compression/block-compression-algorithms.md

---

## Design Narrative

### Intent

Add per-block compression to SSTable storage to reduce disk and network I/O, particularly for remote backends (S3/GCS) where bandwidth is expensive. Compression is opt-in, backward-compatible, and pluggable.

### Why this approach

A compression offset map (loaded eagerly at reader open time) was chosen over inline per-block headers because it enables planned multi-block I/O -- the reader knows all block positions upfront and can issue batch reads for remote backends. An open (non-sealed) codec interface was chosen over sealed/enum patterns to allow consumers to plug in JNI-backed codecs (ZSTD, LZ4) without library source changes. The reader's explicit codec list (varargs at open time) avoids global mutable registries.

### What was ruled out

- **Per-block inline headers:** Forces sequential parsing, breaks multi-block prefetch for remote backends.
- **Sealed codec interface:** Prevents consumer extension without library modification.
- **Global codec registry:** Introduces hidden mutable shared state.
- **Enum + strategy pattern:** Two parallel concepts with no benefit; enum is not extensible.

### Out of scope

- WAL compression (separate concern, different write pattern)
- Key index / bloom filter compression (small metadata structures)
- Custom fast codecs in this feature (may follow from research)
- Compaction-time re-compression with a different codec — **Resolved:** see `compaction-recompression` (accepted 2026-04-12, R38-R39a amended)
- Per-block checksums (compression map can be extended later)

### Audit provenance

This spec incorporates fixes from 14 resolved adversarial audit findings:

| Issue | Requirement |
|-------|-------------|
| INTEGER-OVERFLOW-BOUNDS-CHECK | R9 |
| NEGATIVE-UNCOMPRESSED-LENGTH | R10 |
| COMPRESSION-MAP-ENTRY-NO-VALIDATION | R25 |
| COMPRESSION-MAP-NEGATIVE-BLOCK-COUNT | R28 |
| V2-READER-MISSING-NONE-CODEC | R19 |
| COMPRESSION-MAP-DESERIALIZE-OVERFLOW | R27 |
| COMPRESSION-MAP-SERIALIZE-OVERFLOW | R27 |
| COMPRESSION-MAP-ENTRY-ZERO-SIZE-INVARIANT | R26 |
| FOOTER-NO-FIELD-VALIDATION | R29 |
| LAZY-CHANNEL-CONCURRENT-READ-RACE | R35 |
| CODEC-MAP-NULL-ELEMENT | R21 |
| ASSERT-ONLY-CODEC-GUARD | R40 |
| FOOTER-LONG-TO-INT-TRUNCATION | R30, R31 |
| DUPLICATE-CODEC-ID-SILENT-OVERWRITE | R18 |

Three WATCH items are documented as known limitations: TRAILING-DATA-IGNORED (R42), ZERO-UNCOMPRESSED-LENGTH-ACCEPTED (R26), ITERATOR-CLOSED-STATE-INCONSISTENCY (R43).

Three TENDENCY patterns are encoded as general requirements: BOUNDS-CHECK-OVERFLOW (R9), INTEGER-OVERFLOW-IN-SIZE-CALC (R27), ASSERT-INSTEAD-OF-RUNTIME-CHECK (R40).

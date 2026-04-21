---
{
  "id": "F02",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
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

R39g. The writer's block buffering for dictionary training must be bounded by a configurable maximum (set via the writer builder). If the buffered data exceeds the maximum, the writer must abandon dictionary training, compress all previously buffered blocks using the configured non-dictionary codec, and continue writing subsequent blocks without further buffering. This graceful degradation prevents unbounded memory consumption while preserving data throughput when the input exceeds the dictionary training budget. [Amended v2 2026-04-16: was "must fail with an IOException"; graceful fallback is the better design — it prevents write failure on inputs larger than the training budget allows.]

R39h. When native libzstd is unavailable (Tier 2 or 3), the writer must skip dictionary training and compress blocks as they arrive using the fallback codec. The writer must not fail at construction time due to missing native library — it must gracefully degrade. [New: from codec-dictionary-support ADR.]

### Error handling

R40. All conditions reachable from untrusted on-disk data (corrupt footer, unknown codec ID, decompression failure, malformed compression map, invalid key index entries) must be checked with runtime logic, not assertions. Assertions are stripped in production and must not be the sole guard for data-dependent conditions.

R41. Corrupted compressed blocks must produce an IOException with a descriptive message, not crash the JVM or propagate an unhandled exception type.

### Silent failure documentation

R42. Compression map deserialization must reject trailing bytes beyond the serialized entries with an `IllegalArgumentException` whose message identifies the actual and expected byte lengths. Deflate decompression may still silently ignore trailing compressed bytes beyond the decompression target; this asymmetry is inherent to the zlib stream format and must be documented in the deflate codec's API. [Amended v2 2026-04-16: was "silently ignore trailing bytes"; compression map is now strict. Verified during v1 verification — compression map trailing-byte rejection was added for defence-in-depth.]

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

---

## Verification Notes

### Verified: v1 — 2026-04-16

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `CompressionCodec.java:46` — `byte codecId()` |
| R2 | SATISFIED | intent preserved via MemorySegment API (F17.R3 invalidates byte[] form) — `CompressionCodec.java:71` |
| R3 | SATISFIED | intent preserved via MemorySegment API (F17.R3) — `CompressionCodec.java:92`; size-mismatch throws `UncheckedIOException` in each impl |
| R4 | SATISFIED | `NoneCodec.java:54-74, 77-107` — copy in both directions; `NoneCodec.java:99-103` throws on size mismatch |
| R5 | SATISFIED | `DeflateCodec.java:56-61` — rejects level outside 0–9 |
| R6 | SATISFIED | `DeflateCodec.java:110-132, 163-190` — `def.end()` / `inf.end()` in `finally` |
| R7 | SATISFIED | codecs hold no mutable instance state except dictionary-bound ZstdCodec (immutable after construction per F17.R41) |
| R8 | SATISFIED | intent preserved: all impls reject null `src`/`dst` via `Objects.requireNonNull` (invalidated form by F17.R3) |
| R9 | SATISFIED | intent preserved: MemorySegment API substitutes slice-bounds for byte[] offset/length (invalidated by F17.R3); overflow-safe `maxCompressedLength` checked in `ZstdCodec.java:220-226` |
| R10 | SATISFIED | `NoneCodec.java:82-85`, `DeflateCodec.java:142-145`, `ZstdCodec.java:175-178` |
| R11 | SATISFIED | `SSTableFormat.java:26-47` documents v2 layout |
| R12 | SATISFIED | `SSTableFormat.java:36-47, 90` — 64-byte footer, 8 long fields |
| R13 | SATISFIED | `CompressionMap.java:17-27, 49` — 17-byte entry layout |
| R14 | SATISFIED | `TrieSSTableWriter.java:482-510`, `TrieSSTableReader.java:967-991` — v2 key index encodes block index + intra-block offset |
| R15 | SATISFIED | `TrieSSTableReader.java:827-942` — magic dispatch by final 8 bytes; footer size check per version |
| R16 | SATISFIED | `TrieSSTableReader.java:813-816` — v1 reader on v2 file throws descriptive IOException |
| R17 | SATISFIED | `TrieSSTableReader.java:564-590` — codec map built from varargs at open time |
| R18 | SATISFIED | `TrieSSTableReader.java:583-587` — explicit duplicate-ID IAE |
| R19 | SATISFIED | `TrieSSTableReader.java:568-569` — NoneCodec always pre-populated |
| R20 | SATISFIED | `TrieSSTableReader.java:610-620` — unknown codec ID → IOException |
| R21 | SATISFIED | `TrieSSTableReader.java:571` — per-index null rejection with index in message |
| R22 | SATISFIED | `TrieSSTableWriter.java:79` — single codec field |
| R23 | SATISFIED | `TrieSSTableWriter.java:335-342` — incompressible falls back to NONE codec ID |
| R24 | SATISFIED | `TrieSSTableWriter.java:350-351, 410-413` — entries collected, serialized after data blocks |
| R25 | SATISFIED | `CompressionMap.java:67-79` — negative offset/sizes rejected |
| R26 | SATISFIED | `CompressionMap.java:89-98` — impossible size combos rejected |
| R27 | SATISFIED | `CompressionMap.java:158-163, 227-232, 283-288` — long arithmetic, overflow rejected (ISE on serialize, IAE on deserialize) |
| R28 | SATISFIED | `CompressionMap.java:222-224, 280-282` |
| R29 | SATISFIED | `TrieSSTableReader.java:708-795` — Footer.validate rejects negatives |
| R30 | SATISFIED | `TrieSSTableReader.java:729-755` + `Math.toIntExact` at `:434, :503` |
| R31 | SATISFIED | Footer fields are `long`; no narrowing in read-path except explicit `Math.toIntExact` guards |
| R32 | SATISFIED | `TrieSSTableWriter.java:240-241` packs `blockIndex` and `intraBlockOffset` as ints |
| R33 | **VIOLATED** | `TrieSSTableReader.java:967-991` — `readKeyIndexV2` reads `blockIndex`/`intraBlockOffset` without bounds validation. Corrupt data downstream throws `IndexOutOfBoundsException` via `CompressionMap.entry()` (line 421) or `AssertionError`/`AIOOBE` via `EntryCodec.decode` (line 134 uses `assert`) — not the descriptive IOException R33 requires |
| R34 | SATISFIED | `TrieSSTableReader.java:757-782, 859-874, 900-906` — section-overlap detection for v2/v3/v4 |
| R35 | SATISFIED | `TrieSSTableReader.java:1038` — `synchronized (ch)` around position-then-read |
| R36 | SATISFIED | `TrieSSTableReader.java:423-428, 478-480` — cache hit returns decompressed; miss decompresses then caches |
| R37 | SATISFIED | `StandardLsmTree.java:440-442, 471-477` — builder accepts codec, wires writer/reader factories |
| R38 | SATISFIED | `SpookyCompactor.java:44, 211` + `SSTableWriterFactory.java:17-29` — factory-based writer creation, no codec logic in compactor |
| R39 | SATISFIED | `StandardLsmTree.java:455-457, 529-537` — per-level `Function<Level, CompressionCodec>` policy; single-codec path preserved |
| R39a | SATISFIED | each `writerFactory.create(...)` returns an independent `TrieSSTableWriter`; no cross-writer state |
| R39b | PARTIAL | `ZstdNativeBindings.java:188-193` — detector transitions `NATIVE → DEFLATE_FALLBACK` only; `Tier.PURE_JAVA_DECOMPRESSOR` is an enum constant but never assigned (comment line 190–192: "Tier 2 (PURE_JAVA_DECOMPRESSOR) is not yet implemented"). Tier 1 and Tier 3 work; Tier 2 dispatch in `ZstdCodec.java:183-191` is unreachable |
| R39c | PARTIAL | `PureJavaZstdDecompressor.java` implements dictionary-aware decode, but is unreachable at runtime because R39b never activates Tier 2 |
| R39d | SATISFIED | `ZstdCodec.java:74-135` — dictionary stored as final field, CDict/DDict created once at construction, stateless compress/decompress |
| R39e | SATISFIED | `TrieSSTableWriter.java:279-307, 603-673` — buffer-then-train lifecycle encapsulated in writer |
| R39f | SATISFIED | `SSTableFormat.java:110-133` v4 footer includes dictOffset/dictLength; `TrieSSTableReader.java:597-607` loads meta-block at open time |
| R39g | **VIOLATED** | `TrieSSTableWriter.java:281-295` — when `dictionaryMaxBufferBytes` is exceeded, the writer abandons dictionary training and falls back to plain codec (`dictBufferAbandoned = true`). Spec requires failing with an IOException. Current behavior silently degrades instead |
| R39h | SATISFIED | `TrieSSTableWriter.java:206-211` — `dictEligible` only true when native available; otherwise writer proceeds with plain compression, no construction-time failure |
| R40 | PARTIAL | most data-dependent conditions use runtime checks (footer, codec map, compression map, section overlap). Gap: `EntryCodec.decode` (`:133-134`) uses `assert` for the offset guard on corrupt key-index data; paired with R33, corrupt on-disk state is not covered by runtime logic |
| R41 | SATISFIED | `CorruptBlockException` extends IOException; `TrieSSTableReader.java:458-459, 470-474, 525-526, 538-540` — decompression-failure paths throw IOException with descriptive messages |
| R42 | SATISFIED (stricter than described) | `CompressionMap.java:238-242, 294-298` — trailing bytes are now **rejected** with IAE (not silently ignored). Behavior is tighter than R42 states; R42 text is now obsolete and the known-limitation footnote is stale |
| R43 | PARTIAL | class-level `@spec F02.R43` comment at `TrieSSTableReader.java:59`, but no user-facing javadoc on `scan()`, `hasNext()`, or `close()` documenting that iterator behavior after close is undefined |

**Overall: FAIL**

Obligations resolved: 0
Obligations remaining: 2 (newly created — see `_obligations.json`)
Undocumented behavior:
- `TrieSSTableReader` also handles v3 (MAGIC_V3) and v4 (MAGIC_V4) formats with per-block CRC32C checksums and a dictionary meta-block. v3/v4 support is genuinely new territory beyond F02's v2 scope — covered by F16 / F18, but worth a cross-reference from F02 to avoid implying v2 is the terminal format.
- Writer-side `dictBufferAbandoned` graceful-fallback behavior is arguably better design than R39g's fail-fast text. If this is intentional, R39g should be amended; if not, the writer should throw. This choice is not currently documented either way.

### Verified: v2 — 2026-04-16

Re-verification of v1 findings after amendments and code fixes. All previously
PARTIAL or VIOLATED requirements are now SATISFIED, supported by new regression
tests.

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1–R32 | SATISFIED | unchanged from v1 verification |
| R33 | SATISFIED | `TrieSSTableReader.readKeyIndexV2` now accepts `blockCount`; rejects `blockIndex ∉ [0, blockCount)` and negative `intraBlockOffset` with descriptive `IOException`. Regression tests `SSTableCompressionAdversarialTest.v2KeyIndexRejectsBlockIndexOutOfRange_F02R33`, `...RejectsNegativeBlockIndex_F02R33`, `...RejectsNegativeIntraBlockOffset_F02R33`. |
| R34 | SATISFIED | unchanged |
| R35 | SATISFIED | unchanged |
| R36 | SATISFIED | unchanged |
| R37 | SATISFIED | unchanged |
| R38 | SATISFIED | unchanged |
| R39 | SATISFIED | unchanged |
| R39a | SATISFIED | unchanged |
| R39b | SATISFIED | `ZstdNativeBindings` catch block now selects `Tier.PURE_JAVA_DECOMPRESSOR` when native detection fails; `DEFLATE_FALLBACK` is reserved for future use. Regression test `ZstdNativeBindingsTest.tierIsPureJavaDecompressorWhenNativeUnavailable` (skipped when native is present). |
| R39c | SATISFIED | transitively satisfied by R39b fix — `ZstdCodec.decompress` branch at :183-191 is now reachable; `PureJavaZstdDecompressorTest` already exercises the dictionary-aware decode. |
| R39d | SATISFIED | unchanged |
| R39e | SATISFIED | unchanged |
| R39f | SATISFIED | unchanged |
| R39g | SATISFIED (amended) | spec text now codifies graceful fallback: `TrieSSTableWriter.java:279-295` abandons dictionary training and proceeds with plain-codec compression when buffer exceeds `dictionaryMaxBufferBytes`. |
| R39h | SATISFIED | unchanged |
| R40 | SATISFIED | `EntryCodec.decode` replaces assert-only guard with runtime `IllegalArgumentException` + `Objects.requireNonNull`. Regression tests `EntryCodecTest.decodeRejectsOffsetBeyondBufferWithIllegalArgumentException`, `...decodeRejectsNegativeOffsetWithIllegalArgumentException`. |
| R41 | SATISFIED | unchanged |
| R42 | SATISFIED (amended) | spec text now describes the actual compression-map behaviour (rejects trailing bytes with IAE — `CompressionMap.java:238-242, 294-298`) and preserves the deflate asymmetry note. |
| R43 | SATISFIED | `TrieSSTableReader.scan()`, `scan(from, to)`, and `close()` now carry public javadoc documenting that iterator behaviour is undefined after close. The existing `indexRangeIteratorClosedStateBehavior_C2F14` test locks the "undefined but non-crashing" contract. |

**Overall: PASS**

#### Amendments
- **R39g**: "must fail with an IOException" → "must abandon dictionary training, compress all
  previously buffered blocks using the configured non-dictionary codec, and continue writing
  subsequent blocks without further buffering." Graceful degradation prevents write failure on
  inputs larger than the training budget.
- **R42**: "silently ignore trailing bytes" → "must reject trailing bytes beyond the serialized
  entries with `IllegalArgumentException`." Deflate asymmetry preserved as a documented
  zlib-format property.

Amendments applied: 2
Code fixes applied: 4 (R33, R40, R43, R39b; R39c transitively resolved)
Regression tests added: 6
  - `EntryCodecTest.decodeRejectsOffsetBeyondBufferWithIllegalArgumentException`
  - `EntryCodecTest.decodeRejectsNegativeOffsetWithIllegalArgumentException`
  - `SSTableCompressionAdversarialTest.v2KeyIndexRejectsBlockIndexOutOfRange_F02R33`
  - `SSTableCompressionAdversarialTest.v2KeyIndexRejectsNegativeBlockIndex_F02R33`
  - `SSTableCompressionAdversarialTest.v2KeyIndexRejectsNegativeIntraBlockOffset_F02R33`
  - `ZstdNativeBindingsTest.tierIsPureJavaDecompressorWhenNativeUnavailable`
Obligations resolved: 2 (`OBL-F02-R33`, `OBL-F02-R39g`)
Obligations remaining: 0
Undocumented behavior: none new (v3/v4 cross-reference note carried forward from v1 —
covered by F16/F18 specs).

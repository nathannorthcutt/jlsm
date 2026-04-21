---
{
  "id": "F18",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": ["serialization", "storage"],
  "requires": ["F02", "F17"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "codec-dictionary-support",
    "compaction-recompression",
    "compression-codec-api-design"
  ],
  "kb_refs": [
    "algorithms/compression/zstd-dictionary-compression",
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": [
    "Remove F02 R39b-R39h (letter-suffixed ADR stubs) after F18 implementation — invalidates array cannot reference letter-suffixed requirement IDs"
  ]
}
---

# F18 — ZSTD Dictionary Compression with Per-Level Codec Policy

## Requirements

### ZSTD codec static factories

R1. The CompressionCodec interface must provide four ZSTD static factory methods: `zstd()` (default level, no dictionary), `zstd(int level)` (custom level, no dictionary), `zstd(MemorySegment dictionary)` (default level, with dictionary), and `zstd(int level, MemorySegment dictionary)` (custom level, with dictionary). All four must return a codec with codec ID 0x03 when Tier 1 (native) is active, or codec ID 0x02 when Tier 2 or 3 is active (see R7a).

R2. ZSTD compression level must be validated at construction time. Valid levels are 1 through 22 (standard compression levels only; ZSTD's fast/negative levels and default level 0 are intentionally excluded for simplicity). Values outside 1-22 must produce an IllegalArgumentException regardless of which tier is active.

R3. The `maxCompressedLength(int)` method for the ZSTD codec must return the value from native `ZSTD_compressBound()` when Tier 1 is active. When Tier 2 or 3 is active, it must return the conservative default bound from the CompressionCodec interface.

R3a. The `maxCompressedLength(int)` method must guard against integer overflow. If the computed bound would overflow int, the method must throw an IllegalArgumentException.

### Tiered runtime detection

R4. ZSTD codec tier detection must probe for native libzstd via Panama FFM `Linker.nativeLinker()` and `SymbolLookup.libraryLookup()` at class-load time. The detection result must be cached in a static final field. Detection must catch all Throwable subclasses (including LinkageError, UnsatisfiedLinkError) and fall through to the next tier.

R4a. The active tier must be queryable via a static method returning an enum or equivalent indicating NATIVE (Tier 1), PURE_JAVA_DECOMPRESSOR (Tier 2), or DEFLATE_FALLBACK (Tier 3). This enables operators to verify that native ZSTD is available in their deployment environment.

R5. Tier 1 (native) must bind downcall handles for at minimum: `ZSTD_compress2`, `ZSTD_decompress`, `ZSTD_compressBound`, `ZSTD_isError`, `ZSTD_getErrorName`, `ZSTD_createCCtx`, `ZSTD_freeCCtx`, `ZSTD_createDCtx`, `ZSTD_freeDCtx`, `ZSTD_createCDict`, `ZSTD_freeCDict`, `ZSTD_createDDict`, `ZSTD_freeDDict`, `ZSTD_compress_usingCDict`, `ZSTD_decompress_usingDDict`, and `ZDICT_trainFromBuffer`. If any required symbol is missing, the tier must fail and fall through to Tier 2.

R6. Tier 2 (pure-Java decompressor) must decompress both plain ZSTD frames and dictionary-compressed frames. It must parse the dictionary ID from the frame header, load pre-trained FSE and Huffman tables from dictionary bytes, pre-seed repeat offsets, and prepend dictionary content as match history. It must not compress.

R7. Tier 3 (Deflate fallback) must be used for compression when Tier 1 is unavailable. The resulting data is DEFLATE-compressed.

R7a. The ZSTD codec's `codecId()` method must return the codec ID that its `compress()` method will produce: 0x03 when Tier 1 is active, 0x02 when Tier 2 or 3 is active. This value is fixed at class-load time based on tier detection and does not vary per call. The writer records `codecId()` in the compression map, so the returned ID must match the actual compression format. This means on systems without native libzstd, calling `CompressionCodec.zstd()` produces a codec that compresses with DEFLATE and returns codec ID 0x02.

### Cross-tier interoperability

R8. Data compressed with Tier 1 (native ZSTD) must decompress correctly with Tier 2 (pure-Java ZSTD decompressor). Data compressed with Tier 1 using a dictionary must decompress correctly with Tier 2 given the same dictionary bytes.

R9. Data compressed with the Deflate fallback (Tier 2/3 compression path) must decompress correctly with the standard DEFLATE codec. The ZSTD codec must not produce data that requires a ZSTD decompressor when native ZSTD was unavailable at compression time.

### Dictionary-aware writer lifecycle

R10. The SSTable writer builder must accept an optional dictionary training configuration with two parameters: a boolean enable flag and an integer block-count threshold. The default threshold must be 64 blocks. Dictionary training is a writer-level concern, independent of the codec configuration.

R11. When dictionary training is enabled, the configured codec has codec ID 0x03, and Tier 1 (native) is available, the writer must buffer all uncompressed data blocks in memory. After all blocks are generated, if the block count is greater than or equal to the threshold, the writer must train a dictionary from the buffered blocks, create a dictionary-bound ZSTD codec, and compress all buffered blocks with it. The trained dictionary must be stored as a meta-block in the SSTable file.

R11a. The writer must pass all buffered blocks as training samples to the dictionary trainer. If the total buffered data exceeds a practical training limit (implementation-defined), the writer may use uniform random sampling to select a representative subset, but must pass at least `threshold` samples. The minimum sample count and sampling strategy must be documented.

R12. When dictionary training is enabled but the block count after buffering is less than the threshold, the writer must compress all buffered blocks with the configured codec without a dictionary. No dictionary meta-block must be stored.

R13. When dictionary training is enabled but native libzstd is unavailable (Tier 2 or 3), the writer must skip dictionary training entirely and compress blocks as they arrive using the fallback codec path. The writer must not fail at construction time due to missing native library.

R13a. When dictionary training is skipped due to missing native library, training failure (R27), or buffer limit exceeded (R14), the writer must record this event in a way observable to the caller — either via an accessor on the writer's result metadata or a callback provided to the builder. Silent degradation without any notification path is not permitted.

R14. The writer's block buffering for dictionary training must be bounded by a configurable maximum byte count (set via the writer builder). If buffered data exceeds this maximum before all blocks are generated, the writer must abandon dictionary training, immediately compress all currently buffered blocks with the configured codec (without a dictionary), and continue streaming subsequent blocks without further buffering. The writer must not fail the SSTable write solely because dictionary training buffering was exceeded. No dictionary meta-block must be stored when buffering is abandoned.

### ZstdDictionaryTrainer

R15. A dictionary trainer utility must provide: `addSample(MemorySegment)` to collect training samples, `train(int maxDictBytes)` to produce a trained dictionary as a MemorySegment, and a static `isAvailable()` method that returns true only when native libzstd is detected (Tier 1). Calling `train()` when native libzstd is unavailable must throw an IllegalStateException.

R15a. The trainer must concatenate all samples into a contiguous memory region and maintain a parallel array of sample sizes, as required by the `ZDICT_trainFromBuffer` native API.

R16. The `train()` method must invoke the native `ZDICT_trainFromBuffer` function via Panama FFM. The maximum dictionary size parameter must be validated: minimum 256 bytes, maximum 1 MiB (1,048,576 bytes). Values outside this range must produce an IllegalArgumentException.

R17. The trainer must collect at least one sample before `train()` is called. Calling `train()` with zero samples must throw an IllegalStateException. Training from fewer samples than would fill the requested dictionary size may produce a suboptimal dictionary but must not fail.

### CDict/DDict native resource lifecycle

R17a. When Tier 1 is active and a dictionary is provided at codec construction, the codec must create CDict (for compression) and DDict (for decompression) from the dictionary bytes via Panama FFM downcall handles. These native resources must be allocated in a dedicated Arena whose lifetime is tied to the codec instance. The codec must implement AutoCloseable, and `close()` must close the Arena, freeing all native CDict/DDict resources.

R17b. A closed codec must throw an IllegalStateException on any `compress()` or `decompress()` call. The writer and reader must not use a codec after closing it.

R17c. Codecs without a dictionary (plain ZSTD) must allocate and free CCtx/DCtx within each `compress()`/`decompress()` call, consistent with the per-call resource pattern established by F02.R6 for Deflate. No long-lived native resources are needed for non-dictionary codecs.

### SSTable dictionary meta-block

R18. The dictionary must be stored as a contiguous byte region in the SSTable file, written after all data blocks and the compression map, but before the key index.

R19. The SSTable format version for files containing a dictionary meta-block must be v4 (magic number 0x4A4C534D53535404). The v4 footer must be 88 bytes with the following big-endian layout:

```
v4 Footer (big-endian, 88 bytes):
  [long mapOffset     ]  8 bytes  -- offset 0
  [long mapLength     ]  8 bytes  -- offset 8
  [long dictOffset    ]  8 bytes  -- offset 16
  [long dictLength    ]  8 bytes  -- offset 24
  [long idxOffset     ]  8 bytes  -- offset 32
  [long idxLength     ]  8 bytes  -- offset 40
  [long fltOffset     ]  8 bytes  -- offset 48
  [long fltLength     ]  8 bytes  -- offset 56
  [long entryCount    ]  8 bytes  -- offset 64
  [long blockSize     ]  8 bytes  -- offset 72
  [long magic         ]  8 bytes  -- offset 80 = MAGIC_V4
```

When no dictionary is present, `dictOffset` and `dictLength` must both be 0.

R19a. A v4-capable reader must also read v1, v2, and v3 files. Detection uses the magic number from the final 8 bytes of the file, consistent with F02.R15/F16.R17.

R19b. The v4 section ordering invariant must be: `mapOffset + mapLength <= dictOffset` (when dictionary is present, i.e., `dictLength > 0`), `dictOffset + dictLength <= idxOffset`, `idxOffset + idxLength <= fltOffset`, `fltOffset + fltLength <= fileSize - 88`. When no dictionary is present (`dictLength == 0`), `mapOffset + mapLength <= idxOffset`. The reader must validate this ordering and throw an IOException on violation.

R19c. The v4 compression map must use v3-style 21-byte entries (with per-block CRC32C checksums). The v4 format inherits all v3 compression map semantics — only the footer and dictionary meta-block differ.

R20. When reading a v4 SSTable with `dictLength > 0`, the reader must load the dictionary bytes from the dictionary meta-block before decompressing any data block. The reader must create a dictionary-bound codec from the loaded dictionary bytes and use it for all blocks with codec ID 0x03 in that file.

R20a. When opening a v4 SSTable, the reader must replace any caller-provided codec for ID 0x03 with a dictionary-bound codec constructed from the file's dictionary meta-block. When opening a v3 or earlier SSTable, the reader must use a plain (no-dictionary) ZSTD codec for ID 0x03, regardless of what the caller provided. The file's on-disk metadata determines the ZSTD decompression configuration, not the caller-provided codec instances. This overrides the general F02.R18 pattern for codec ID 0x03 only — all other codec IDs continue to use the caller-provided map.

R21. When reading a v3 or earlier SSTable (no dictionary meta-block), the reader must decompress ZSTD-compressed blocks (codec ID 0x03) without a dictionary.

R22. A reader that does not support v4 format must fail with a descriptive IOException when encountering the v4 magic number, not with silent data corruption.

### Per-level compression policy

R23. The tree builder must provide a `compressionPolicy(Function<Level, CompressionCodec>)` method that accepts a function returning the codec to use for a given level. This is a new builder method (specified by F02.R39 but not yet implemented). This function is evaluated once per writer creation (at flush or compaction time), not once per block. The returned codec instance must be used for all blocks in that SSTable.

R24. When both `compression(codec)` and `compressionPolicy(fn)` are set on the builder, `compressionPolicy` must take precedence. Calling `compression(codec)` must be equivalent to `compressionPolicy(_ -> codec)`.

R25. The tree builder must resolve the effective compression policy in the following precedence order: (1) `compressionPolicy` if set, (2) `compression` if set (equivalent to `_ -> compression`), (3) the caller-provided `sstableWriterFactory` and `sstableReaderFactory` if either is set — the builder must defer to those factories as-is without wrapping them, (4) the default `_ -> CompressionCodec.none()` only when none of the above are supplied. This preserves backward compatibility for both codec-policy-driven callers and callers who supply custom writer/reader factories.

### Error handling

R26. If Tier 1 detection succeeds (native library found) but a downcall invocation fails at runtime (e.g., ABI mismatch, symbol returns error code), the codec must throw an UncheckedIOException. The codec must not silently fall back to a different tier after initial detection succeeds — tier selection is a one-time class-load decision. Dictionary training failures (R27) are not tier fallbacks and are not affected by this requirement — R27 governs the writer's response to training failure within Tier 1.

R27. If dictionary training fails (`ZDICT_trainFromBuffer` returns an error code), the writer must fall back to compressing all buffered blocks with plain ZSTD (no dictionary). The writer must not fail the entire SSTable write due to a training failure. No dictionary meta-block must be stored in this case. The training failure must be reported per R13a.

R28. ZSTD decompression errors from native calls must produce an UncheckedIOException with the ZSTD error name (from `ZSTD_getErrorName`) included in the message. The raw error code must not be the only diagnostic information.

## Cross-References

- ADR: .decisions/codec-dictionary-support/adr.md
- ADR: .decisions/compaction-recompression/adr.md
- ADR: .decisions/compression-codec-api-design/adr.md
- KB: .kb/algorithms/compression/zstd-dictionary-compression.md
- KB: .kb/algorithms/compression/block-compression-algorithms.md
- Spec: F02 — Block-Level SSTable Compression (R38, R39, R39a retained)
- Spec: F17 — WAL Compression with MemorySegment Codec API (R41, R42 retained)

---

## Design Narrative

### Intent

Add ZSTD compression with adaptive per-SSTable dictionary training to the
SSTable compression pipeline, and a per-level codec policy API so consumers
can configure different compression strategies at different LSM levels
(e.g., DEFLATE for hot L0, ZSTD+dictionary for cold archival levels).

### Why this approach

The codec-dictionary-support ADR established that the codec remains stateless —
dictionary bytes are constructor-time configuration, and the dictionary training
lifecycle lives in the SSTable writer and a ZstdDictionaryTrainer utility. A
tiered Panama FFM detection pattern (matching the existing TierDetector for JSON
SIMD) provides native ZSTD when available with pure-Java decompressor fallback.
The compaction-recompression ADR established writer-factory injection with a
per-level codec policy function, unifying flush and compaction writer creation.

The adaptive dictionary threshold (train only when block count >= threshold) was
a scoping-phase refinement: dictionary training is wasteful on small SSTables
(L0 flushes with a few dozen blocks), but highly effective on large compaction
outputs (L2+ with thousands of blocks). The threshold makes this data-driven
rather than requiring explicit per-level dictionary configuration.

### What was ruled out

- **Per-level shared dictionaries:** Cross-SSTable dictionary sharing adds
  significant lifecycle complexity (versioning, staleness, external dependency).
  Per-SSTable dictionaries are self-contained and adapt to each file's data
  distribution.
- **Dictionary-aware codec subtype:** Adding `addTrainingSample()` to the codec
  breaks F02.R7 (stateless contract). Training lifecycle belongs in the writer.
- **Codec factory pattern:** Only ZSTD needs dictionary support. A factory adds
  indirection without benefit today.
- **Bundled native binaries:** The library detects system-provided libzstd at
  runtime. No platform-specific packaging.

### Invalidated requirements from F02

F02.R39b through F02.R39h are replaced by R4-R14 above with tightened,
falsifiable requirements. The original stubs were ADR-sourced placeholders
that lacked concrete format specifications (v4 footer layout), error handling
semantics (training failure fallback), adaptive threshold behavior, codec ID
resolution for Tier 2/3 fallback, and native resource lifecycle.

### Adversarial falsification findings applied

Pass 2 identified 10 confirmed gaps. All were accepted and incorporated:

| Finding | Severity | Requirement |
|---------|----------|-------------|
| Tier 2/3 codec ID mismatch | Critical | R7a — codecId() returns effective ID based on active tier |
| Per-file dictionary vs caller codec map | High | R20a — reader overrides caller codec for ID 0x03 based on file metadata |
| v4 footer layout unspecified | High | R19 — explicit byte-level layout with field offsets |
| Buffer limit = total write failure | High | R14 — graceful degradation, abandon training, continue write |
| CDict/DDict lifecycle unspecified | High | R17a-R17c — AutoCloseable, Arena-bound lifecycle |
| v4 section ordering unvalidated | Medium | R19b — ordering invariant including dictionary section |
| maxCompressedLength overflow | Medium | R3a — overflow guard |
| Level range justification wrong | Low | R2 — corrected justification text |
| Sampling strategy undefined | Low | R11a — all blocks passed, random subset when exceeding limit |
| R26/R27 wording ambiguity | Low | R26 — explicit carve-out for dictionary training failures |
| Tier detection invisible | Medium | R4a — static tier query method |
| Dictionary skip silent | Medium | R13a — observable notification path |

---

## Verification Notes

### Verified: v2 — 2026-04-17

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `modules/jlsm-core/src/main/java/jlsm/core/compression/CompressionCodec.java:156-196` (four `zstd(...)` factory methods) |
| R2 | SATISFIED | `ZstdCodec.java:93-96` (level validated 1..22 at construction) |
| R3 | SATISFIED | `ZstdCodec.java:200-230` (native `ZSTD_compressBound` on Tier 1, fallback formula on Tier 2/3) |
| R3a | SATISFIED | `ZstdCodec.java:210-214, 222-228` (overflow guards throw `IllegalArgumentException`) |
| R4 | SATISFIED | `ZstdNativeBindings.java:89-217` (class-load detection via `Linker.nativeLinker()` + `SymbolLookup.libraryLookup()`, cached in `ACTIVE_TIER`, catches all `Throwable`) |
| R4a | SATISFIED | `ZstdNativeBindings.java:277-279` (`activeTier()` returns `Tier` enum) |
| R5 | SATISFIED | `ZstdNativeBindings.java:119-188` (all 16 required ZSTD symbols bound; `ZSTD_CCtx_setParameter` additionally bound for level control) |
| R6 | SATISFIED | `PureJavaZstdDecompressor.java:34-1352` (plain + dictionary frame decompression; compression not provided) |
| R7 | SATISFIED | `ZstdCodec.java:98, 166-167` (Deflate fallback codec used when native unavailable) |
| R7a | SATISFIED | `ZstdCodec.java:68-71, 139-141` (`ACTIVE_CODEC_ID` fixed at class-load: 0x03 native, 0x02 fallback) |
| R8 | SATISFIED | `PureJavaZstdDecompressorTest.java` (cross-tier round-trip: Tier 1 compress → Tier 2 decompress, plain and dict) |
| R9 | SATISFIED | `ZstdCodec.java:98, 166-167, 191-194` (Tier 2/3 compression uses Deflate and returns codec ID 0x02) |
| R10 | SATISFIED | `TrieSSTableWriter.java:745-764` (`Builder.dictionaryTraining(boolean)`, `dictionaryBlockThreshold(int)`, default threshold 64) |
| R11 | SATISFIED | `TrieSSTableWriter.java:576-647` (`finishWithDictionaryTraining`: buffer + train + compress-with-dict + v4 meta-block) |
| R11a | SATISFIED | `TrieSSTableWriter.java:594-597` (all buffered blocks passed as training samples; minimum threshold enforced by R12) |
| R12 | SATISFIED | `TrieSSTableWriter.java:581-589` (below-threshold: plain codec, v3 layout, no dictionary) |
| R13 | SATISFIED | `TrieSSTableWriter.java:209-214` (dictionary training only eligible with native; no construction failure) |
| R13a | SATISFIED | `TrieSSTableWriter.java:406-412, 604-605, 869-871` (`dictionaryTrainingResult()` accessor records skip/failure) |
| R14 | SATISFIED | `TrieSSTableWriter.java:283-295` (buffer-limit exceeded: abandon + stream; no SSTable-write failure; no dict meta-block) |
| R15 | SATISFIED | `ZstdDictionaryTrainer.java:74-185` (`addSample`, `train`, static `isAvailable`; ISE on Tier 2/3 `train`) |
| R15a | SATISFIED | `ZstdDictionaryTrainer.java:132-145` (contiguous sample buffer + parallel size array) |
| R16 | SATISFIED | `ZstdDictionaryTrainer.java:113-116, 152-153` (`trainFromBuffer` invoked; `[256, 1048576]` range validated) |
| R17 | SATISFIED | `ZstdDictionaryTrainer.java:117-119` (`train()` with zero samples throws ISE) |
| R17a | SATISFIED | `ZstdCodec.java:52, 104-137, 233-255` (Arena-scoped CDict/DDict, AutoCloseable, `close()` frees resources) |
| R17b | SATISFIED | `ZstdCodec.java:149, 181, 257-261` (`requireOpen()` throws ISE after close) |
| R17c | SATISFIED | `ZstdCodec.java:282-295, 326-329` (plain codec allocates/frees CCtx/DCtx per call; no long-lived resources) |
| R18 | SATISFIED | `TrieSSTableWriter.java:623-631` (dictionary written after compression map, before key index) |
| R19 | SATISFIED | `SSTableFormat.java:112-135`, `TrieSSTableWriter.java:527-543` (MAGIC_V4 `0x4A4C534D53535404`, 88-byte footer in specified order) |
| R19a | SATISFIED | `TrieSSTableReader.java:914-1031` (magic-based dispatch handles v1/v2/v3/v4) |
| R19b | SATISFIED | `TrieSSTableReader.java:943-958, 778-866` (v4 section-ordering invariant validated) |
| R19c | SATISFIED | `TrieSSTableReader.java:249-251`, `TrieSSTableWriter.java:625` (v4 uses v3-style 21-byte entries) |
| R20 | SATISFIED | `TrieSSTableReader.java:256-258, 328-330, 668-685` (v4 + dict loads dictionary and creates dict-bound codec) |
| R20a | **REPAIRED** | Was VIOLATED: reader did not override caller-provided codec for ID 0x03 on v3 files. Fix: `TrieSSTableReader.java:687-704` `overrideWithPlainZstdCodec` always replaces ID 0x03 with plain ZSTD on v3 or earlier. Regression tests: `DictionaryCompressionReaderTest.readerInjectsPlainZstdCodecForV3File`, `lazyReaderInjectsPlainZstdCodecForV3File`. |
| R21 | **REPAIRED** | Same root cause as R20a; same fix. |
| R22 | SATISFIED | `TrieSSTableReader.java:885-902` (v1-only `readFooterV1` throws IOException naming detected version) |
| R23 | SATISFIED | `StandardLsmTree.java:460-470`, `566-579` (builder `compressionPolicy`, evaluated once per writer) |
| R24 | SATISFIED | `StandardLsmTree.java:555-563` (`compressionPolicy` wins over `compression`, regardless of set order) |
| R25 | **AMENDED + REPAIRED** | Amendment: requirement now encodes precedence (policy > codec > factories > default `none()`). Code: `StandardLsmTree.java:555-567` — default `_ -> CompressionCodec.none()` fires only when neither factories nor compression are supplied. Regression test: `CompressionPolicyTest.treeWithNoCompressionAndNoFactoriesDefaultsToNone`. |
| R26 | SATISFIED | `ZstdCodec.java:303-306, 340-346` (downcall runtime failure wrapped in `UncheckedIOException`; no silent tier fallback) |
| R27 | SATISFIED | `TrieSSTableWriter.java:593-608` (training failure: plain ZSTD, `DictionaryTrainingResult` records reason, no SSTable-write failure) |
| R28 | SATISFIED | `ZstdCodec.java:389-402` (`ZSTD_getErrorName` included in UncheckedIOException message) |

**Overall: PASS_WITH_REPAIRS**

- SATISFIED: 26
- REPAIRED inline: 2 (R20a, R21) — one shared fix
- AMENDED + REPAIRED: 1 (R25) — spec text tightened to encode precedence; code gained default-none fallback
- PARTIAL: 0
- VIOLATED remaining: 0

**Amendments:**
- R25: rewrote to encode four-level precedence (policy > codec > factories > default `none()`) and explicitly require builder to defer to caller factories. Old wording's literal default `_ -> CompressionCodec.none()` would have silently overridden user-supplied writer/reader factories — not the intended backward-compatibility behavior.

**Code fixes applied:**
1. `TrieSSTableReader.open()`/`openLazy()` (v3 or earlier, or v4 with `dictLength==0`): inject plain `CompressionCodec.zstd()` as the codec for ID 0x03, overriding whatever caller supplied. New helper: `overrideWithPlainZstdCodec`.
2. `StandardLsmTree.Builder.resolveCompressionPolicy()`: when neither `compression`/`compressionPolicy` nor any user `sstableWriterFactory`/`sstableReaderFactory` is set, return `_ -> CompressionCodec.none()` so the tree can build with a default no-compression configuration.

**Regression tests added:**
- `DictionaryCompressionReaderTest.readerInjectsPlainZstdCodecForV3File`
- `DictionaryCompressionReaderTest.lazyReaderInjectsPlainZstdCodecForV3File`
- `CompressionPolicyTest.treeWithNoCompressionAndNoFactoriesDefaultsToNone`

**Dead code removed:** `modules/jlsm-core/src/main/java/jlsm/sstable/DictionaryTrainingResult.java` — unused package-level stub record superseded by the nested `TrieSSTableWriter.DictionaryTrainingResult`.

**Annotation coverage:** 62 `@spec F18.*` annotations across implementation and test files. The `spec-trace.sh` regex matches only numeric requirement IDs (`R\d+`), so suffix-style requirements (R3a, R4a, R7a, R11a, R13a, R15a, R17a-c, R19a-c, R20a) are not counted in the trace table but are present in the source. Traced (numeric-only): R1-R8, R10-R20, R22-R28 (26/26 numeric requirements).

**Open obligation carried forward:** Remove F02 R39b-R39h stubs — this is an F02 cleanup task, not an F18 verification concern.

**Obligations deferred:** none.

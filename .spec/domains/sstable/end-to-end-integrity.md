---
{
  "id": "sstable.end-to-end-integrity",
  "version": 5,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "sstable"
  ],
  "requires": [
    "sstable.v3-format-upgrade"
  ],
  "invalidates": [
    "sstable.v3-format-upgrade.R16",
    "sstable.v3-format-upgrade.R17"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "sstable-end-to-end-integrity",
    "per-block-checksums",
    "corruption-repair-recovery"
  ],
  "kb_refs": [
    "systems/database-engines/corruption-detection-repair"
  ],
  "open_obligations": [
    "OB-sstable-end-to-end-integrity-01: Writer must transition to FAILED state on any IOException from append, flushCurrentBlock, compressAndWriteBlock, finish, commitFromPartial, finishV5Layout, or close. Audit finding F-R1.shared_state.02.01..08 pinned by test_TrieSSTableWriter_finish_IOException_leaves_state_open_allows_corrupt_retry. Implementation is fixed but the spec R3/R22 extension to every write-site must be ratified without creating a spec-code conflict with existing atomicity wording.",
    "OB-sstable-end-to-end-integrity-02: Writer-internal invariant — counter-buffer pairs (e.g., dictBufferedBytes vs dictBufferedBlocks) must be updated as a unit across every abandon-branch, per audit finding F-R1.data_transformation.1.6. Codify as writer-internal invariant comment; may be promoted to a full requirement if a future refactor surfaces a downstream read of the counter."
  ],
  "_migrated_from": [
    "F26"
  ]
}
---
# sstable.end-to-end-integrity — SSTable End-to-End Integrity

## Requirements

### VarInt-prefixed self-describing blocks

R1. TrieSSTableWriter must write a VarInt-encoded (LEB128 unsigned) byte count before each data block, representing the number of bytes actually written to disk for that block (the compressed payload length, or the raw payload length for uncompressed blocks).

R2. The VarInt encoding must use 7 bits per byte with the MSB as a continuation flag (LEB128 unsigned).

R3. The writer must write the VarInt prefix first, then record `writePosition` (the byte immediately after the VarInt) as the `blockOffset` in the compression map entry. The VarInt write, blockOffset record, block payload write, and compression-map entry append must all complete as a unit; partial completion must transition the writer to FAILED state.

R4. The reader's normal block-read path must not attempt to read or skip the VarInt prefix — it must use the stored blockOffset directly, which already points past the prefix. Offsets produced by a recovery scan that rebuilds the compression map must conform to this convention (point past the VarInt).

R5. A VarInt must encode values in [1, SSTableFormat.MAX_BLOCK_SIZE] (32 MiB) using at most 4 bytes in canonical (minimal) LEB128 unsigned form. Non-canonical encodings — a trailing continuation byte whose payload bits are all zero — must be rejected.

R6. The VarInt reader must reject: (a) a 5th continuation byte, (b) a decoded value that exceeds SSTableFormat.MAX_BLOCK_SIZE, (c) a decoded value of zero (zero-length blocks are illegal). Rejection must throw CorruptSectionException identifying the data section and the file byte offset at which decoding failed.

R46. The writer's producer-side guard must reject any single `append` whose encoded bytes, combined with the current block's already-accumulated bytes, would produce a data block larger than `SSTableFormat.MAX_BLOCK_SIZE`. The rejection must surface at the earliest point where the projected oversize is known (before VarInt encoding begins) as an `IOException` whose message identifies the encoded entry size, the projected block size, and the `MAX_BLOCK_SIZE` constant. This is the writer-side producer-boundary contract symmetric to R6's reader-side rejection.

### Recovery scan

R7. TrieSSTableReader must expose a recovery scan capability that walks the data section sequentially: read VarInt → read that many bytes → next VarInt, without requiring the compression map.

R8. The recovery scan must use the `blockCount` field from the footer as its loop bound, terminating after exactly `blockCount` blocks have been read. After termination, the reader must validate that the next byte position equals `mapOffset` from the footer; a mismatch must throw CorruptSectionException with section name `"data"`, including `expectedEndOffset` and `actualEndOffset` in the diagnostic.

R9. If the recovery scan encounters fewer blocks than `blockCount` before reaching the compression map offset, or if a VarInt declares a length that would require reading beyond `mapOffset`, it must throw CorruptSectionException with section name `"data"`, including block index, declared length, and remaining bytes.

R10. During a recovery scan, per-block CRC32C verification failures must throw CorruptBlockException (from sstable.v3-format-upgrade), not CorruptSectionException — the corruption is at the block level, not the section level.

R56. The recovery-scan block-read path must not silently fall through when a block's expected per-block CRC metadata (a compression-map entry that is present but whose `checksum` field is absent, null, or otherwise unpopulated) is missing. An inconsistent "compression-map entry present, crc absent" state must surface as an explicit `CorruptSectionException` with section name `"compression-map"` before the block is decoded. R10's per-block CRC defense must be non-bypassable by any internal state the recovery-scan code can observe.

### v5 footer layout

R11. The v5 SSTable footer must be 112 bytes. All multi-byte fields are big-endian. Layout:

| Offset | Field | Size |
|--------|-------|------|
| 0 | mapOffset | 8 bytes (long) |
| 8 | mapLength | 8 bytes (long) |
| 16 | dictOffset | 8 bytes (long) |
| 24 | dictLength | 8 bytes (long) |
| 32 | idxOffset | 8 bytes (long) |
| 40 | idxLength | 8 bytes (long) |
| 48 | fltOffset | 8 bytes (long) |
| 56 | fltLength | 8 bytes (long) |
| 64 | entryCount | 8 bytes (long) |
| 72 | blockSize | 8 bytes (long) |
| 80 | blockCount | 4 bytes (int) |
| 84 | mapChecksum | 4 bytes (int) |
| 88 | dictChecksum | 4 bytes (int) |
| 92 | idxChecksum | 4 bytes (int) |
| 96 | fltChecksum | 4 bytes (int) |
| 100 | footerChecksum | 4 bytes (int) |
| 104 | magic | 8 bytes (long) |

R12. The v5 footer magic number must be 0x4A4C534D53535405. When the 8 bytes at footer offsets [104..112) are read in file order, they spell 'J','L','S','M','S','S','T',0x05.

R52. The reader's footer-magic dispatcher must detect a v5 file whose magic discriminant has been corrupted to a legacy (v1/v2/v3/v4) magic value before the legacy-branch dispatch can reinterpret the bytes. For any non-v5 magic on a file at least `FOOTER_SIZE_V5` bytes long, the reader must compute a speculative v5 footer self-checksum by hypothesis-substituting `MAGIC_V5` into the checksum scope (R16), recomputing CRC32C over the 104 in-scope bytes, and comparing the result against the stored `footerChecksum`. A match proves the file is a v5 file with a corrupted discriminant and must surface as `CorruptSectionException(SECTION_FOOTER)` — the commit-marker integrity check (R16) must fire before any legacy-branch reinterpretation.

### Per-section CRC32C checksums

R13. TrieSSTableWriter must compute CRC32C for each metadata section before writing the footer, using java.util.zip.CRC32C (per sstable.v3-format-upgrade). The checksum stored in the footer is the low 32 bits of `CRC32C.getValue()` cast via `(int) crc.getValue()`.

R14. Each section checksum must be computed over the raw bytes as written to disk for that section.

R15. If dictLength is 0, dictOffset must also be 0 and dictChecksum must be 0. The reader must validate all three are 0 together; any inconsistent combination (e.g., dictLength = 0 but dictOffset != 0) must produce CorruptSectionException with section name `"footer"`.

R16. The footerChecksum must be computed as CRC32C over the 104 footer bytes at offsets [0..100) ∪ [104..112) — i.e., all footer fields except the footerChecksum field itself. The magic value is included in the checksum scope so that any corruption of the commit marker is detectable.

R47. The v5 write path must compute a per-block CRC32C for every data block unconditionally. The CRC computation must not be gated on any legacy-format feature flag (e.g., a `v3`/`v4` boolean). Coupling the CRC branch to a legacy flag creates a coverage gap: a future refactor that clears the legacy flag on a v5 writer would silently disable integrity coverage while still recording `checksum=0` into the v5 compression-map entry. The CRC obligation must be expressed as an invariant of the v5 block-write path itself, decoupled from any version flag.

R49. For a v5 SSTable the bloom-filter section must have `fltLength > 0`. A zero-length bloom section is always a structural corruption, not a legitimate state — the sentinel reasoning of R15 (dictionary length=0 ⇒ offset=0 ⇒ checksum=0) does not apply to the bloom filter, because a zero-length payload with a nonce-zero checksum passes the CRC gate vacuously and would permit a bloom-bypass attack. The reader must reject `fltLength == 0` for any v5 file as `CorruptSectionException(SECTION_FOOTER)` before any section verification runs.

### blockCount and footer validation

R17. The writer must store the total number of data blocks written as `blockCount` in the footer. An empty SSTable (blockCount = 0) is not permitted in v5 format; v5 SSTables must have at least one data block.

R18. At SSTable open time, the reader must first validate `blockCount >= 1` and `mapLength >= 1`; if either fails, throw CorruptSectionException with section name `"footer"`. Only then compare the compression map's entry count against `blockCount`; on mismatch throw CorruptSectionException with section name `"compression-map"`, including both the footer-declared blockCount and the map-derived entry count.

R55. The writer's `writeFooterV5` entry point (and any equivalent v5-footer producer) must enforce producer-side invariant guards on `blockCount >= 1`, `mapLength >= 1`, and `blockSize` (must be a positive power of two within the SSTable format's declared bounds) before any `V5Footer` construction or byte encoding begins. A contract violation must surface as an `IOException` (or typed subclass) at the caller's boundary — not deep inside the encoder. This is the symmetric producer-side guard for the reader's R17/R18 invariant.

### fsync discipline

R19. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing all data blocks and before writing the compression map (the first metadata section).

R20. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing the last metadata section (the bloom filter) and before writing the footer.

R21. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing the footer.

R22. If any force(true) call throws IOException, the writer must transition to FAILED state and propagate the exception; no retry is permitted. If the IOException is a `ClosedByInterruptException`, the writer must preserve the interrupt flag via `Thread.currentThread().interrupt()` before propagating. The same interrupt-flag preservation rule applies symmetrically to the reader: any `ClosedByInterruptException` caught during verification (R25, R27, R28, R7) must preserve the interrupt flag before propagating.

R23. When the output channel is not a FileChannel (remote/NIO providers), the writer must skip all force() calls — the remote provider is assumed to commit atomically at channel close. The writer must accept an optional `FsyncSkipListener` (single-method functional interface: `void onFsyncSkip(Path path, Class<? extends Channel> channelClass, String reason)`) registered on the Builder; when fsync is skipped for a non-FileChannel output, the listener must be invoked exactly once per skipped fsync site. Absence of a registered listener is not an error; the reliability of remote durability is the caller's responsibility to audit.

R24. (Dropped in v3; former wording mandated Java `instanceof` pattern syntax. Implementations may use any conditional dispatch form consistent with R19–R23.)

### Atomic commit and partial-file handling

R39. The writer must write the v5 SSTable to a per-writer-unique temporary path and commit to the final path only after R21's footer fsync succeeds.

  (a) The temporary path must be unique per writer instance — the scheme `<final>.partial.<writerId>` (where `<writerId>` is an opaque string guaranteed unique across concurrent writers in the same process, e.g., UUID or monotonic counter + pid) is required. Opening the temporary path must use `StandardOpenOption.CREATE_NEW` semantics; a pre-existing file at the temporary path must cause the writer to fail-fast before any bytes are written.

  (b) Commit from temporary path to final path is backend-conditional. Before any move branch runs, the writer must check whether the final output path already exists and, if so, must fail fast with `FileAlreadyExistsException`. This pre-check is mandatory regardless of whether the underlying filesystem supports `ATOMIC_MOVE`; the behavior must be uniform across POSIX and remote (S3/NIO-provider) filesystems so the POSIX `rename(2)` silent-overwrite semantics never reach an already-committed final file.

  - When the final path resolves to a `FileChannel` on a POSIX filesystem where the temporary and final paths share a filesystem, the writer must use `Files.move(partial, final, StandardCopyOption.ATOMIC_MOVE)`. If the atomic move throws `AtomicMoveNotSupportedException` (cross-filesystem), the writer must fall back to content-addressed commit (below) and emit an `FsyncSkipListener` event (R23) with reason `"atomic-rename-unsupported"` if a listener is registered.
  - When the channel is not a FileChannel (remote/NIO providers — S3, GCS), or atomic rename is unsupported, the writer must use content-addressed commit: the final path itself must incorporate a content-unique or sequence-unique suffix so the absence of atomic rename cannot produce a half-published file visible at a caller-observable final path. The writer must use `StandardOpenOption.CREATE_NEW` on the final-path channel after temporary-path finalize.

  (c) On FAILED state, the writer must attempt to delete its own temporary file (identified by the per-writer-unique path from clause (a)). Delete failure must be logged but must not mask the originating IOException. The writer must never delete a file it did not create; orphan-partial cleanup across process crashes is out of scope for this spec and delegated to `partitioning.corruption-repair-recovery`.

R40. On open, a file whose trailing 8 bytes do not contain a recognized SSTable magic, OR whose total size is smaller than the version-specific footer size after a valid magic has been identified, must be treated as incomplete (not corrupt) and reported via `IncompleteSSTableException` (an IOException subclass distinct from `CorruptSectionException`), allowing operators to distinguish partial writes from mid-file corruption. The diagnostic must include the detected magic (or "no magic") and expected-vs-actual file size where applicable. R40's detection is trailing-magic- and file-size-based only — it cannot detect corruption confined to the interior of an otherwise-well-framed file; that is the job of the per-section CRC32C checks (R25–R29).

### Verification at open

R25. TrieSSTableReader must read the trailing 8 bytes of the file first to identify the magic. If the file is shorter than 8 bytes, throw IncompleteSSTableException (R40). After magic dispatch, the reader must read the version-specific footer bytes from `fileSize - footerSize`; if `fileSize < footerSize`, throw IncompleteSSTableException (R40). Only after the footer bytes are fully read must the reader verify footerChecksum (R16). Footer verification must complete before the factory method returns, regardless of whether the reader is in eager or lazy mode.

R26. If the footerChecksum fails, the reader must throw CorruptSectionException with section name `"footer"`, the stored footerChecksum as expected value, and the computed CRC32C as actual value.

R27. For eager mode: the reader must verify all section checksums (compression map, dictionary if present, key index, bloom filter) during open, before returning.

R28. For lazy mode: each section's CRC32C must be verified before any byte of that section is returned to the caller. Verification must be the first operation on first-load for each section, and the section must be marked verified-or-failed atomically. The footer checksum (R25) is always verified eagerly regardless of mode.

R29. On any section checksum mismatch, the reader must throw CorruptSectionException identifying which section failed and including the expected vs actual checksum values.

R30. If dictLength is 0, the reader must skip dictionary checksum verification (not compute CRC32C over zero bytes). Validation per R15 must still apply.

R48. On every lazy-mode and eager-mode read, the reader must validate all attacker-controllable length fields in the v5 footer (`mapOffset`, `mapLength`, `idxLength`, `fltLength`, `dictLength`) against `Integer.MAX_VALUE` as an upper bound and `0` as a lower bound (treating lengths as unsigned within the long representation) before any downstream `(int)` narrowing cast. Any length that would narrow to a negative or nonsensical `int` (e.g., `mapLength = 2^31` narrowing to `Integer.MIN_VALUE`) must surface as `CorruptSectionException(SECTION_FOOTER)` rather than propagating as `IllegalArgumentException` from a downstream `ByteBuffer.allocate` or array allocation. The pre-v5 paths obtain this guard implicitly via `Footer.validate(fileSize)`; the v5 path must apply an equivalent inline guard.

R50. Any raw-bytes channel read in the reader must enforce a bounded limit on consecutive zero-progress returns. A single `read == 0` return must still be tolerated (NIO permits it), but after a configured maximum of consecutive zero-byte reads (without error, without EOF) — `SSTableFormat.MAX_CONSECUTIVE_ZERO_READS` or equivalent — the reader must throw a descriptive `IOException` identifying the file offset and the stall count. EOF semantics (`read < 0`) are unchanged. A reset of the stall counter must occur on any positive-progress return.

R51. Any raw-bytes channel read in the reader must reject a negative length at the method boundary with a descriptive `IOException` (or `CorruptSectionException` when the length came from a length field) before any allocation. A negative length must not propagate as `IllegalArgumentException` from `ByteBuffer.allocate(-n)` or equivalent past the factory's declared exception vocabulary (R31, R40).

### Exception-safe open and reader lifecycle

R41. If any verification step (R25, R27, R28-first-load) throws, the reader must release every off-heap resource acquired between entering the verification site and the failure. Specifically:

  (a) For pooled buffers obtained from `ArenaBufferPool.acquire()`, the reader must call `ArenaBufferPool.release(segment)`. Pool releases must be idempotent — the reader must track released segments to avoid double-release if multiple failure paths converge.
  (b) For confined `Arena` instances opened during verification, the reader must close each arena in reverse acquisition order.
  (c) The underlying channel must be closed if the failure occurred inside the open factory. If the failure occurred in a post-open lazy first-load, channel ownership follows R43.
  (d) Secondary close/release exceptions must be attached via `Throwable.addSuppressed` to the originating exception.

R43. Verification failure during a post-open lazy first-load (R28) OR any post-open corruption detected on the normal block-read path (e.g., compression-map lookup failure, per-block CRC mismatch, block decompression failure, VarInt-decode failure surfaced under R6) must transition the reader to FAILED state before the originating `CorruptSectionException` (or `CorruptBlockException`) is propagated to the caller. The write of `failureSection` must happen-before the write of `failureCause` so any concurrent observer that sees a non-null cause is guaranteed to see a non-null section name. A reader in FAILED state must:

  (a) Surface the originating `CorruptSectionException` (or `CorruptBlockException`) to every currently-blocked thread waiting on the same section's first-load, exactly once per blocked thread (not via silent lock-release).
  (b) Reject all subsequent `get`, `scan`, `recoveryScan`, or section-load calls with `IllegalStateException("reader failed: " + sectionName)`, wrapping the originating exception via `getCause()`. If `failureCause` is observed non-null while `failureSection` is still null (a torn intermediate state the R43 publish-order prevents in-process but which a reflective observer may still encounter), the reader's diagnostic rendering must substitute a stable non-null sentinel (e.g., `"<unknown>"`) for the section name and must always preserve the cause chain. The diagnostic must never surface the literal string `"null"` for the section.
  (c) Permit `close()` to run to completion, releasing the underlying channel and any pooled off-heap buffers per R41's rules. `close()` must be idempotent on a FAILED reader — repeated calls must not throw.

### CorruptSectionException

R31. CorruptSectionException must extend IOException.

R32. CorruptSectionException must include the section name (String), expected checksum (int), and actual checksum (int). The message rendering must format both checksums as `0x%08X` (zero-padded 8-digit hex) so diagnostic output is unsigned and stable across implementations.

R33. CorruptSectionException must live in package `jlsm.sstable` (peer to `CorruptBlockException`), which is exported by the `jlsm.core` module in its module-info.

### Section-name vocabulary

R42. The `sectionName` field on CorruptSectionException (R32) is drawn from a per-format-version vocabulary. For v5, the vocabulary is: `"footer"`, `"compression-map"`, `"dictionary"`, `"key-index"`, `"bloom-filter"`, `"data"`. These six values must be exposed as public string constants on CorruptSectionException (e.g., `CorruptSectionException.SECTION_FOOTER`). A v5 implementation producing a section name outside this vocabulary is a defect. Future format versions may extend this vocabulary by amending this spec; callers must not assume the vocabulary is closed across versions.

### Concurrency contract

R38. `TrieSSTableReader` instances must be safe for concurrent reads across threads. In lazy mode, the first thread to request a section performs verification; concurrent threads must block until verification completes, and each section must be verified at most once per reader lifetime. `TrieSSTableWriter` is not thread-safe — `append` and `finish` must be called from a single thread.

Recovery scans (R7) must not run concurrently with normal reads on the same reader instance. The reader must enforce this exclusion: either (a) `recoveryScan()` acquires an exclusive lock on the reader, blocking concurrent `get`/`scan` for its duration and throwing `IllegalStateException("recovery-scan in progress")` if reentered from another thread, OR (b) `recoveryScan()` throws `IllegalStateException("reads in progress")` if any concurrent `get`/`scan` is active on the same reader. Implementations must pick exactly one; a no-op declaration ("callers must not") is not sufficient. The check-and-modify pair on both sides (the reader-slot acquire's `recoveryInProgress` read + `activeReaderOps` increment, and the recovery-scan entry's `activeReaderOps == 0` check + `recoveryInProgress = true` set) must be serialized under a single mutex so neither side can observe a straddle window between the other side's check and modify. An unlocked volatile read of the counterpart's predicate before a modify is forbidden.

R44. Every reader iterator that acquires a shared coordination resource (recovery lock, reader-slot, channel position) must implement `AutoCloseable`. Invoking `close()` on such an iterator must release every coordination resource the iterator holds, be idempotent across repeated calls, and cause any subsequent `hasNext()` invocation to report no further elements without performing any I/O. A caller that abandons the iterator mid-stream (exception during consumption, early `break` from a for-each, reference dropped without `close()`) must not be able to starve a future acquisition of the same coordination resource for the reader's lifetime. `RecoveryScanIterator` (R7) is bound by this requirement.

R45. Constructor-phase failures along a paired acquire/release coordination sequence (e.g., `recoveryScan()` ctor acquiring `recoveryLock` before a later allocation or I/O step fails) must not mask the originating `IOException` with an `IllegalMonitorStateException` from the unwind path. Every lock-release in an unwind must be guarded (e.g., "release only if previously acquired by this thread") so that the originating cause propagates unchanged to the caller. This rule applies symmetrically to every paired acquire/release in the reader.

### Version compatibility

R34. The v5-capable reader must support magic values for v1, v2, v3, v4, and v5. The v1/v2/v3 magics are defined by sstable.v3-format-upgrade.R17; the v4 magic is defined by SSTableFormat; v5 is defined by R12 of this spec. An unrecognized trailing magic must be treated as an incomplete file per R40 (IncompleteSSTableException), not as a corrupt file. (Supersedes sstable.v3-format-upgrade.R17.)

R35. For v4 or earlier files, the reader must not attempt VarInt prefix decoding, section checksum verification, blockCount validation, or recovery scan. The reader must determine footer size from the magic version and must not overread past the version-specific footer size. If the file size is smaller than the version-specific footer size, the reader must throw IncompleteSSTableException (R40).

R36. The writer must produce v5 format SSTables when a compression codec is configured. v5 supersedes v3 and v4 as the compressed output format. (Supersedes sstable.v3-format-upgrade.R16.)

R53. Legacy-branch (v1/v2/v3/v4) footer-structural failures — non-power-of-two `blockSize`, overread past the version-specific footer size, pairwise internal inconsistency in the legacy footer layout — must surface as `CorruptSectionException` with a v5-vocabulary (R42) section name, not as an opaque `IOException`. Callers depend on the typed-exception vocabulary (R31, R42) to distinguish corruption from transient I/O; the v5-capable reader must apply that discipline to every legacy branch it still supports under R34.

R54. The reader factory (`TrieSSTableReader.open` / `openLazy`) must expose an optional `expectedVersion` parameter so callers holding external authority (manifest, catalog, level metadata, sibling references) may assert the file's expected format version. `expectedVersion` must be validated as an integer in `[1, 5]`; a disagreement between the caller-supplied `expectedVersion` and the magic-derived version must surface as `CorruptSectionException(SECTION_FOOTER)`. Callers that do not supply an `expectedVersion` pay nothing — the R34 auto-detect semantics are preserved for the opt-out path.

### Section ordering

R37. The v5 writer must arrange sections in file order: `data` → `compression-map` → `dictionary` (if present) → `key-index` → `bloom-filter` → `footer`. Each present section's `offset + length` must equal the next present section's offset (tight packing; no gaps, no overlap). Zero-length sections (dictionary when absent) must have `offset = 0` and `length = 0` (sentinel per R15), and the tight-packing check must skip absent sections rather than treat their offset as the previous section's end. The first (lowest-offset) present section must have `offset > 0` — a `mapOffset == 0` (or first-section-offset of zero) is a structural corruption because a non-empty data region must precede any metadata section. The reader must validate the file-order, first-section-positive-offset, and tight-packing invariants at open by sorting present sections by offset and walking the sorted list; any violation must throw CorruptSectionException with section name `"footer"`.

---

## Design Narrative

### Intent

Three-layer integrity extends per-block checksums (v3) to cover the entire
SSTable. VarInt-prefixed blocks make the data section self-describing, enabling
recovery when the compression map is corrupt. fsync discipline + backend-
conditional atomic commit (R39) prevents the most common local corruption vector
(partial writes with OS write reordering) on POSIX filesystems and preserves
correctness on remote backends that lack atomic rename. Per-section CRC32C
detects corruption in all metadata sections at open time. The footer magic is
integrity-protected, so a single bit flip cannot silently downgrade a v5 file
to an older format.

### Why this approach

The three fsyncs in the write path (after data, after metadata, after footer)
combined with atomic commit (R39) ensure that the footer magic number at the
final path is a reliable commit marker: if the file exists at its final path,
all preceding data and metadata were fsynced before the footer, and the footer
itself was fsynced before commit. On remote backends without atomic rename
(S3, GCS), content-addressed commit achieves the same semantics by making the
final path itself unique — a half-published file cannot appear at a
caller-observable final path because the final path only materializes after
the complete bytes exist.

Recovery scans use VarInt block length prefixes to walk the data section
without the compression map, and `blockCount` from the footer as a termination
condition — cross-checked against `mapOffset` to detect truncation (R8).
Per-block CRC32C (from the compression map or recomputed during scan)
validates each recovered block independently.

The `int` CRC32C storage (R11) is the low 32 bits of `CRC32C.getValue()` cast
via `(int)`. Comparison between stored and computed values is via `int`
equality (R13); diagnostic messages render as unsigned hex (`0x%08X`) so
operators and log analysis tools see stable, canonical values (R32).

### What was ruled out

- **Per-block CRC only (v3 only).** Does not detect metadata-section corruption
  or partial writes of the footer. Keeping v3 would leave the SSTable's
  "index" layer undefended.
- **Whole-file CRC.** Single point of failure; requires reading the entire
  file before opening can begin. Rejected for startup latency reasons.
- **Coupling corruption response to the reader.** Repair/quarantine policy is
  owned by `partitioning.corruption-repair-recovery` (F48), not this spec. This
  spec only mandates *detection* (exception types, section-name vocabulary,
  reader FAILED state) so the repair layer has a reliable contract to
  dispatch on.
- **Ambiguous observability in R23.** Pass 2 added an observability hook but
  used "metric, callback, or DEBUG log" — itself an ambiguous alternative.
  v4 resolves this with a specific `FsyncSkipListener` callback; metrics and
  logs are caller-side concerns wired into the callback.

### Concurrency, resource discipline, and reader FAILED state

R38 declares the thread-safety model explicitly. Lazy mode (R28) is the
motivating case: without R38, two threads requesting the same section
concurrently either perform redundant CRC work or race on the underlying
channel. R41 covers the exception-safe open case — verification that fails
inside the factory must not leak the channel or any pooled off-heap buffers
acquired during open, since the caller never receives a reader instance to
close.

R43 covers what R41 cannot: failures during post-open lazy first-load. A
reader instance is already in the caller's hands; R41's "close the channel"
rule would yank it from under concurrent threads. Instead, R43 transitions
the reader to FAILED state: blocked threads wake with the originating
exception, subsequent calls throw a clear `IllegalStateException` wrapping
the cause, and `close()` remains idempotent and non-throwing. This gives the
repair layer (F48) a stable handle to reason about: a failed-but-closable
reader instance rather than a half-poisoned object.

### Relationship to F48 (corruption-repair-recovery)

On checksum failure, readers throw `CorruptSectionException` (metadata) or
`CorruptBlockException` (block-level). The quarantine / scrubbing / repair
policies are owned by `partitioning.corruption-repair-recovery`. This spec
mandates the detection contract only — exception types, section-name
vocabulary (R42), incomplete-vs-corrupt distinction (R40, R34), and reader
FAILED state (R43). Orphan-partial cleanup across process crashes is also
F48's domain.

### Known limitations of R40's detection

R40 distinguishes "incomplete" from "corrupt" using trailing magic and file
size only. A file whose interior is corrupt but whose trailing bytes still
form a valid magic is NOT classified as incomplete — per-section CRC32C
checks (R25–R29) catch that. Conversely, a file with a single-bit flip in
the trailing magic is currently reported as incomplete rather than corrupt.
This is a deliberate tradeoff: incomplete files are auto-skippable by the
repair layer, and the space of bit-flips landing inside the 8-byte magic is
small relative to bit-flips elsewhere. Implementations concerned with
adversarial or hostile corruption should layer a higher-level authenticator
(signed manifests, etc.) above this spec.

## Adversarial Review Notes

### v5 (current)

v4 had 43 active requirements (R24 dropped). The `implement-sstable-enhancements--wd-03`
audit (2026-04-22, round 1) produced 24 confirmed-and-fixed findings across
eight files; the impl fixes had already landed when reconciliation ran. v5
formalizes the contracts those fixes now satisfy. Key changes:

- **R37** — refined: the first (lowest-offset) present section must have
  `offset > 0`. Closes audit F-R1.contract_boundaries.01.02: the pairwise
  tight-packing walk assumed the first section had a positive offset, so a
  `mapOffset == 0` slipped through.
- **R38** — refined: single-mutex spell-out for the reader-slot vs recovery-scan
  check-and-modify pair. Closes audit F-R1.concurrency.1.1 (check-then-act
  across paired acquire/release paths).
- **R39** — refined: uniform pre-existing-path pre-check with
  `FileAlreadyExistsException` before any move branch runs. Closes audit
  F-R1.data_transformation.1.1 (POSIX `ATOMIC_MOVE` silent overwrite vs
  non-atomic fallback `FileAlreadyExistsException` divergence).
- **R43** — extended: FAILED-state transition now covers every post-open
  corruption-detection site (normal block-read path, not just lazy first-load),
  and mandates ordered publish (`failureSection` before `failureCause`) and a
  non-null sentinel rendering when diagnostics observe torn intermediate state.
  Closes audit F-R1.concurrency.1.4 (torn-volatile rendering "null") and
  F-R1.concurrency.1.6 (missing FAILED-state write site for `get()` corruption).
- **R44** *(new)* — reader iterators holding shared coordination must be
  `AutoCloseable` and release resources idempotently. Closes audit
  F-R1.concurrency.1.3 (`RecoveryScanIterator` had no `close()`; abandoned
  iterator held `recoveryLock` forever).
- **R45** *(new)* — constructor-phase unwind along paired acquire/release
  must not mask originating `IOException` with `IllegalMonitorStateException`.
  Closes audit F-R1.concurrency.1.7 (`recoveryScan` ctor failure triggered
  `recoveryLock.unlock()` in a finally that itself threw IMSE).
- **R46** *(new)* — writer-side producer-boundary guard for
  single-entry-exceeds-MAX_BLOCK_SIZE. Closes audit
  F-R1.data_transformation.1.5 (oversize entry surfaced deep inside VarInt
  encode with non-descriptive diagnostic).
- **R47** *(new)* — v5 per-block CRC32C computation is unconditional;
  decoupled from any legacy-format feature flag. Closes audit
  F-R1.data_transformation.1.4 (CRC branch gated on `if (v3)` only).
- **R48** *(new)* — `Integer.MAX_VALUE` cap on all attacker-controllable v5
  footer length fields before any `(int)` narrowing cast. Closes audit
  F-R1.data_transformation.C2.01 (v5 footer path bypassed `Footer.validate`).
- **R49** *(new)* — `fltLength == 0` is a structural corruption for v5 files
  (sentinel reasoning does not apply to bloom filter). Closes audit
  F-R1.data_transformation.C2.02 (`fltLength=0, fltChecksum=0` satisfied v5
  CRC gate vacuously, enabling a bloom-bypass attack).
- **R50** *(new)* — bounded limit on consecutive zero-progress channel reads.
  Closes audit F-R1.data_transformation.C2.03 (`readBytes` spun indefinitely
  on a channel returning `0` repeatedly).
- **R51** *(new)* — negative length rejection at channel-read method
  boundary. Closes audit F-R1.data_transformation.C2.04.
- **R52** *(new)* — reader's footer-magic dispatcher must detect a v5 file
  whose discriminant has been corrupted to a legacy magic, via speculative
  v5-hypothesis CRC recomputation before legacy-branch dispatch. Closes audit
  F-R1.dispatch_routing.1.1 (V5→V4 LSB flip bypass) and .1.2 (V5→V1 LSB+bit-2
  bypass; already-fixed by .1.1).
- **R53** *(new)* — legacy-branch structural failures must surface as
  `CorruptSectionException`, not opaque `IOException`. Closes audit
  F-R1.dispatch_routing.1.3 (V2↔V3 / V1↔V3 single-bit flips produced opaque
  `IOException`).
- **R54** *(new)* — optional `expectedVersion` opt-in on factory entry points
  for external-authority version cross-check. Closes audit
  F-R1.dispatch_routing.1.6 (a legacy file whose manifest claimed v5 was
  opened silently as the wrong version).
- **R55** *(new)* — producer-side invariant guards on `blockCount`,
  `mapLength`, and `blockSize` before `V5Footer` construction. Closes audit
  F-R1.contract_boundaries.01.01 (`writeFooterV5` had no producer-side guard).
- **R56** *(new)* — recovery-scan block-read path must not silently fall
  through when compression-map entry is present but per-block CRC is absent.
  Closes audit F-R1.contract_boundaries.03.01.
- **open_obligation OB-01** — writer FAILED-state transition on `append`,
  `flushCurrentBlock`, `compressAndWriteBlock`, `finish`, `commitFromPartial`,
  `finishV5Layout`, `close` IOException. Audit F-R1.shared_state.02.01..08
  (7 findings) pinned by
  `test_TrieSSTableWriter_finish_IOException_leaves_state_open_allows_corrupt_retry`.
  Implementation fix landed with the audit; spec ratification deferred to
  avoid creating a spec-code conflict with the existing R3/R22 atomicity
  wording — open obligation tracks the ratification task.
- **open_obligation OB-02** — writer-internal counter-buffer pair invariant
  (e.g., `dictBufferedBytes` must track `dictBufferedBlocks` across every
  abandon branch). Audit F-R1.data_transformation.1.6. Codified as internal
  invariant comment; may be promoted to a full requirement if a future
  refactor surfaces a downstream read of the counter.

### v4

v3 had 46 requirements (R1–R42 renumbered with R38, R39, R40, R41, R42 added
by Pass 2). Pass 3 adversarial review produced 11 findings (2 critical, 5
high, 4 medium) — all consequences of Pass 2's accepted fixes. v4 accepts all
of them. Key changes:

- **R22** — reader interrupt-flag preservation added (formerly writer-only)
- **R23** — replaced ambiguous "metric, callback, or DEBUG log" with specific
  `FsyncSkipListener` callback interface (formerly ambiguous alternative)
- **R37** — explicit file order enumerated; tight-packing validation walks
  sorted present sections (formerly undefined section file order)
- **R38** — recovery/read exclusion enforcement mechanism mandated
  (formerly "must not run concurrently" with no mechanism)
- **R39** — substantially rewritten: per-writer-unique partial path with
  `CREATE_NEW`, backend-conditional commit (POSIX atomic rename vs
  content-addressed for remote), own-file-only cleanup on FAILED, orphan
  cleanup delegated to F48 (formerly generic "e.g., `<final>.partial`" +
  assumed atomic rename)
- **R40** — extended to cover "valid magic but file shorter than footer
  size"; narrative documents magic-only detection limitation (formerly
  short-file-with-valid-magic fell through to bare IOException)
- **R41** — explicit disposal scope: `ArenaBufferPool.release` for pooled
  buffers, `Arena.close` in reverse order for confined arenas, idempotent
  release tracking, suppressed-exception chaining (formerly vague "release
  pooled off-heap buffers")
- **R42** — open enumeration anchored at v5; "defect" language scoped to v5
  violations, explicit allowance for future version extension (formerly
  closed enumeration blocking v6)
- **R43** *(new)* — reader FAILED state on post-open first-load failure;
  blocked threads wake with originating exception, subsequent calls throw
  `IllegalStateException`, `close()` idempotent (formerly no owner for
  post-open verification failures — Pass 3 C9)

### v3

v2 had 37 requirements. Pass 2 adversarial review produced 32 findings
(7 critical, 11 high, 10 medium, 4 low, 4 uncertain). v3 accepted critical +
high + medium findings. Key changes:

- **R5/R6** — canonical LEB128 required; reject non-canonical, 5-byte, and
  zero-value VarInts (formerly silent acceptance / integer-overflow path)
- **R8/R9** — recovery scan post-condition (`currentPos == mapOffset`) and
  VarInt-over-section-end check added (formerly silent truncation on corrupt
  footer)
- **R13** — CRC32C low-32-bits cast and comparison semantics explicit
  (formerly silent sign-extension risk)
- **R15** — dictionary sentinel consistency: length + offset + checksum all 0
  together (formerly partial sentinel)
- **R16** — magic value included in footer checksum scope (formerly bit-flip
  of magic undetectable by integrity layer; circular checksum/size dependency
  resolved in R25)
- **R17/R18** — empty SSTable prohibited; blockCount/mapLength non-negative
  check precedes mismatch check; cross-cutting failures disambiguated by
  section-name
- **R22** — interrupt flag preservation on `ClosedByInterruptException`
- **R23** — observability hook on fsync-skip for remote providers
- **R24** — dropped (mandated Java syntax)
- **R25** — magic read first, then size check, then checksum (formerly
  circular dependency)
- **R27/R28** — first-load verification atomic + before-first-byte ordering
- **R37** — tight packing invariant replaces loose monotonicity (formerly
  zero-length section boundary ambiguity)
- **R38** *(new)* — concurrency contract declared (reader thread-safe,
  writer single-thread, lazy first-load atomic)
- **R39** *(new)* — atomic rename-on-commit prevents zombie partial files
- **R40** *(new)* — `IncompleteSSTableException` distinguishes partial
  writes from mid-file corruption
- **R41** *(new)* — exception-safe open releases channel + pooled buffers
- **R42** *(new)* — enumerated section-name vocabulary exposed as public
  constants

### v2

v1 had 21 requirements with 14 failures. Key fixes in v2:
- R1 clarified: VarInt encodes on-disk byte count (compressed length), not
  uncompressed
- R3/R4 split: write-order dependency (VarInt first, then record offset) and
  reader double-skip prevention
- R6: malformed VarInt guard (5-byte max)
- R7-R10: recovery scan fully specified with blockCount termination, entry
  point, and CorruptBlockException for per-block failures
- R15: dictChecksum = 0 when no dictionary; R30: reader skips verification
- R16: footerChecksum scope explicit ([0..100) = 100 bytes)
- R19-R22: three fsyncs (data, metadata, footer) with IOException → FAILED
  state
- R25/R27/R28: eager vs lazy verification explicitly distinguished
- R31-R33: CorruptSectionException in exported package
- R34-R36: version compatibility supersedes F16.R16 and F16.R17
- R37: section ordering validation
- ADR 104→112 byte discrepancy flagged for correction

## Verification Notes

### Verified: v4 — 2026-04-22

Overall: **PASS**

- SATISFIED: 42 (R1, R2, R4–R17, R19–R22, R23, R25–R43)
- UNTESTABLE: 1 (R3 — internal atomicity invariant across VarInt-write → offset-record → block-write → map-append; reliable testing requires a failure-injection framework beyond the current test scope)
- VIOLATED: 0
- PARTIAL: 0

**Evidence:**
- 43 requirements spec-trace-annotated across implementation + test files
- WU-1: 74 tests (format primitives + exception types) — green
- WU-2: 63 tests (writer + reader v5 path, corruption detection, concurrency) — green; 4 intentionally `@Disabled` tests with TODOs (require in-memory NIO FS, channel-wrapper injection, or timing-flaky interrupt paths)
- Full `jlsm-core` suite: 1491 tests green, 0 failures
- `./gradlew :modules:jlsm-core:check` PASS (spotless + checkstyle)

**Amendments applied:** none (no stale-spec findings)
**Code fixes applied:** none (no code-bug findings)
**Regression tests added:** none
**Obligations deferred:** none
**Undocumented behavior:** none

**Annotation coverage:** all 43 requirements have at least one test-side `@spec` annotation; all except R3 have at least one implementation-side `@spec` annotation. R3 is documented as UNTESTABLE above.

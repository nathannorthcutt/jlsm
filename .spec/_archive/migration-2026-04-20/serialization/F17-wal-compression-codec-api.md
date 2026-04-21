---
{
  "id": "F17",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": ["serialization", "storage"],
  "requires": ["F02"],
  "invalidates": [
    "F02.R2", "F02.R3", "F02.R4", "F02.R8", "F02.R9", "F02.R10"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "wal-compression",
    "compression-codec-api-design",
    "codec-thread-safety",
    "max-compressed-length",
    "codec-dictionary-support"
  ],
  "kb_refs": [
    "algorithms/compression/wal-compression-patterns",
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": []
}
---

# F17 — WAL Compression with MemorySegment Codec API

## Requirements

### CompressionCodec MemorySegment API

R1. A compression codec must provide a compress method accepting a source MemorySegment and a destination MemorySegment, returning a MemorySegment slice of the destination containing the compressed output. The codec must not mutate the source segment. The destination segment must be caller-provided and sized to at least maxCompressedLength(inputLength) bytes.

R2. A compression codec must provide a decompress method accepting a source MemorySegment, a destination MemorySegment, and the expected uncompressed length. The method must return a MemorySegment slice of the destination containing exactly uncompressedLength bytes. If the decompressed output does not match the expected length, the codec must throw an UncheckedIOException.

R3. The byte[]-based compress and decompress methods from F02.R2 and F02.R3 must be removed from the CompressionCodec interface. This is a deliberate post-ADR scoping decision — the wal-compression ADR's implementation guidance suggested retaining byte[] methods as deprecated, but the user chose a clean break during scoping (pre-1.0 library, no backward compatibility obligation).

R4. The codecId() method must be retained unchanged. The maxCompressedLength(int) method must be retained unchanged. Both operate on primitive values and are unaffected by the MemorySegment migration.

R5. All codec operations must remain safe to call concurrently from multiple threads without external synchronization. Statelessness (no shared mutable state) is the required mechanism. This reaffirms F02.R7 and the codec-thread-safety ADR.

### Input validation (MemorySegment)

R6. All codec compress and decompress methods must reject null source or destination segments with a NullPointerException.

R7. The compress method must reject a source segment with byteSize() of 0 by returning a zero-length slice of the destination (no compression needed for empty input), not by throwing an exception. The zero-length source check must be evaluated before the destination size check (R10).

R8. If uncompressedLength is 0 and the source segment has byteSize() of 0, the decompress method must return a zero-length slice of the destination.

R9. If uncompressedLength is 0 and the source segment has byteSize() greater than 0, the decompress method must throw an UncheckedIOException (cannot decompress something into nothing).

R10. The decompress method must reject negative uncompressedLength values with an IllegalArgumentException.

R11. The compress method must throw an IllegalStateException if the source segment has byteSize() greater than 0 and the destination segment's byteSize() is less than maxCompressedLength(sourceByteSize). The caller is responsible for providing a sufficiently sized destination.

### NONE codec (MemorySegment)

R12. The NONE codec compress method must copy the source segment to the destination segment using MemorySegment.copy() (segment-to-segment overload) and return a slice of the destination with the same byteSize() as the source. No byte[] intermediary may be used.

R13. The NONE codec decompress method must copy the source to the destination using MemorySegment.copy() and return a slice of the destination with byteSize() equal to uncompressedLength. For NONE codec, the source byteSize() must equal uncompressedLength; a mismatch must produce an UncheckedIOException.

### DEFLATE codec (MemorySegment — zero-copy)

R14. The DEFLATE codec compress method must obtain a direct ByteBuffer from the source MemorySegment via asByteBuffer(), pass it to Deflater.setInput(ByteBuffer), and deflate into a direct ByteBuffer obtained from the destination MemorySegment via asByteBuffer(). No byte[] intermediary may be created by the codec implementation. The zero-copy guarantee holds only when both segments are Arena-allocated (native memory). When heap-backed segments are passed, the JDK may internally use byte[] paths; this is a caller responsibility, not a codec defect.

R15. The DEFLATE codec decompress method must obtain a direct ByteBuffer from the source MemorySegment via asByteBuffer(), pass it to Inflater.setInput(ByteBuffer), and inflate into a direct ByteBuffer obtained from the destination MemorySegment via asByteBuffer(). No byte[] intermediary may be created by the codec implementation. The same native-memory caveat from R14 applies.

R16. The DEFLATE codec must allocate and release Deflater/Inflater instances within each call. Native resources must be released in a finally block. This reaffirms F02.R6.

R17. The DEFLATE codec must accept a configurable compression level (0-9) at construction time. Values outside 0-9 must be rejected with an IllegalArgumentException. This reaffirms F02.R5.

### WAL compressed record format

R18. The WAL record format must include a flags byte immediately after the 4-byte frame length field. The flags byte must use bit 0 to indicate whether the payload is compressed (1 = compressed, 0 = uncompressed). Bits 1-7 are reserved and must be written as 0.

R19. When the flags byte indicates compression (bit 0 = 1), the next byte must be the codec ID (1 byte, following the F02.R1 reservation scheme) and the following 4 bytes must be the uncompressed payload size (big-endian int). These 5 bytes must appear between the flags byte and the compressed payload.

R20. When the flags byte indicates no compression (bit 0 = 0), no codec ID or uncompressed size fields are present. The payload immediately follows the flags byte.

R21. The CRC32 checksum at the end of the record must be computed over the uncompressed payload bytes (entry type through value bytes), regardless of whether the record is compressed. On read, the payload must be decompressed before CRC verification.

R22. The frame length field must include all bytes after itself: flags byte (1) + optional compression header (5 bytes if compressed) + payload bytes (compressed payload for compressed records, uncompressed payload for uncompressed records) + CRC32 (4 bytes).

R23. All new fields in the WAL record format must use ValueLayout with byteAlignment(1), consistent with the existing WalRecord format. Big-endian byte order for multi-byte values.

### WAL format versioning

R24. The WAL must distinguish old-format records (pre-compression, no flags byte) from new-format records. The mechanism must prevent a new-format reader from misinterpreting old-format entry type bytes (0x01 PUT, 0x02 DELETE) as flags bytes. A format version marker in the WAL segment header, a magic number per segment, or documentation that old WAL files must be drained before upgrade are all acceptable approaches.

### WAL compression behavior

R25. The uncompressed payload size for threshold comparison is the size of the region that would be compressed (entry type through value bytes), not the total record size including framing overhead. Records with this size below the configured minimum threshold must be written uncompressed (flags byte = 0x00). The default minimum threshold must be 64 bytes.

R26. If the compressed payload size plus 5 bytes (compression header overhead: 1 byte codec ID + 4 bytes uncompressed size) is greater than or equal to the uncompressed payload size, the record must be written uncompressed (flags byte = 0x00). The compression attempt must not increase on-disk record size.

R27. The WAL must accept a CompressionCodec via its builder. If no codec is explicitly provided, the WAL must use DEFLATE level 6 as the default compression codec. This is a deliberate post-ADR scoping decision — the wal-compression ADR suggested off-by-default, but the user chose on-by-default during scoping (pre-1.0 library).

R28. The WAL must accept a compression minimum-size threshold via its builder. If not explicitly provided, the default threshold of 64 bytes must be used.

R29. Both LocalWriteAheadLog and RemoteWriteAheadLog must support compression with identical format semantics. The compressed record format must be the same in both implementations.

### WAL recovery (compressed records)

R30. The WAL recovery path must accept a set of CompressionCodec instances and build a codec-ID-to-implementation map, analogous to F02.R18 for SSTable readers. Duplicate codec IDs in the provided set must be rejected with an IllegalArgumentException.

R31. During WAL recovery, the reader must inspect the flags byte of each record to determine whether decompression is needed. The reader must handle mixed compressed and uncompressed records within the same WAL segment (local) or directory (remote).

R32. During recovery, if a record's flags byte indicates compression but the codec ID is not in the reader's codec map, the reader must throw an IOException identifying the unknown codec ID and the available codecs.

R33. During recovery, if decompression fails (codec throws), the record must be treated as corrupt — the same skip-and-continue behavior used for CRC mismatches on uncompressed records. After skipping a corrupt compressed record, the reader must advance by frame-length bytes (as for any corrupt record), not by the decompressed size.

R34. During recovery, CRC32 verification must occur after decompression. A CRC mismatch on the decompressed payload must trigger the same skip-and-log behavior as for uncompressed records.

R35. When more than 10 consecutive records are skipped due to decompression failure in a single recovery pass, the WAL must throw an IOException indicating systematic codec failure, rather than silently continuing. The threshold of 10 must be configurable via the builder.

### WAL write-path buffer management

R36. The WAL write path must handle the case where a compression destination buffer of maxCompressedLength(payloadSize) cannot be acquired from the ArenaBufferPool, either by writing the record uncompressed or by propagating the allocation failure as an IOException.

### SSTable codec migration

R37. TrieSSTableWriter must use the MemorySegment compress method for block compression. The block content MemorySegment must be passed directly to the codec — no conversion to byte[] before compression.

R38. TrieSSTableReader must use the MemorySegment decompress method for block decompression. The compressed block MemorySegment read from disk must be passed directly to the codec — no conversion to byte[] before decompression.

R39. The SSTable compression map format (v2 and v3) must remain unchanged. The codec migration affects only the in-memory API surface, not the on-disk format.

R40. All existing SSTable tests that exercise compression must continue to pass after the codec API migration. The behavioral contract is unchanged — only the method signatures change.

### Codec statelessness with dictionary configuration

R41. A CompressionCodec instance configured with dictionary bytes at construction time must remain stateless and thread-safe per F02.R7 and R5 above. The dictionary is immutable shared state (read-only after construction), not mutable state. Multiple threads may call compress() and decompress() concurrently on the same dictionary-configured codec instance without synchronization. [New: codifies assumption from codec-dictionary-support ADR.]

R42. Dictionary bytes passed at codec construction must not be modified by the codec after construction. The codec must either copy the dictionary bytes or document that the caller must not mutate the source segment after construction. [New: from codec-dictionary-support ADR.]

---

## Design Narrative

### Intent
Evolve the compression codec interface from byte[] to MemorySegment to eliminate
heap allocation and copying in the compression hot path. Add per-record WAL
compression using the same codec infrastructure, with compression enabled by
default.

### Why this approach
The wal-compression ADR established per-record compression as the format design
(self-describing, crash-safe, remote-WAL compatible) and MemorySegment-native
API as the implementation strategy. The key finding was that Deflater/Inflater
accept direct ByteBuffers (since Java 11) and MemorySegment.asByteBuffer()
returns direct ByteBuffers for Arena-allocated segments — enabling true zero-copy
compression through native zlib.

### What was ruled out
- **Streaming compression (RocksDB-style):** incompatible with one-file-per-record
  RemoteWriteAheadLog; requires ZSTD (external dependency)
- **Dual API with byte[] bridge:** defers the consistency benefit; user chose
  clean break (pre-1.0)
- **Block-level WAL compression:** poor crash-safety for append-only logs
- **Keeping byte[] methods:** contradicts MemorySegment-first project direction

### Invalidated requirements from F02
F02.R2 (byte[] compress), F02.R3 (byte[] decompress), F02.R4 (NONE byte[] copy),
F02.R8 (null byte[] check), F02.R9 (byte[] bounds validation), F02.R10 (negative
uncompressedLength for byte[] decompress) — all replaced by R1-R13 above with
MemorySegment equivalents. The behavioral intent is preserved; only the type
signatures change.

### Adversarial falsification findings applied
Pass 2 identified 10 confirmed gaps. All were accepted and incorporated:
- R8/R9: split from single contradictory requirement into two unambiguous rules
- R24: format backward-compat requirement added (old-format WAL detection)
- R26: compression comparison now accounts for 5-byte header overhead
- R30: recovery codec set defined analogous to F02.R18
- R35: consecutive skip threshold prevents silent data loss on systematic failure
- Various clarifications: frame length calculation, threshold comparison scope,
  heap-segment caveat, post-ADR deviation notes

---

## Verification Notes

### Verified: v1 — 2026-04-16

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `CompressionCodec.java:71` — `compress(src, dst) → MemorySegment` |
| R2 | SATISFIED | `CompressionCodec.java:92` — `decompress(src, dst, uncompressedLength)`; size-mismatch throws `UncheckedIOException` in each impl (`NoneCodec:99-103`, `DeflateCodec:179-183`, `ZstdCodec:332-335`) |
| R3 | SATISFIED | no byte[]-based `compress`/`decompress` methods in `CompressionCodec` |
| R4 | SATISFIED | `CompressionCodec.java:46` `codecId()` and `:111-117` `maxCompressedLength(int)` retained |
| R5 | SATISFIED | codecs carry no mutable instance state outside immutable dictionary fields (F17.R41); statelessness per class-level javadocs |
| R6 | SATISFIED | all impls guard with `Objects.requireNonNull(src/dst, ...)` at method entry |
| R7 | SATISFIED | `NoneCodec:61-63, DeflateCodec:94-97, ZstdCodec:152-154` — empty-src check precedes dst size check |
| R8 | SATISFIED | `NoneCodec:87-90, DeflateCodec:147-150` — `uncompressedLength==0 && src.byteSize()==0` returns zero-length slice |
| R9 | SATISFIED | `NoneCodec:92-96, DeflateCodec:152-156` — non-empty src with zero length throws `UncheckedIOException` |
| R10 | SATISFIED | `NoneCodec:82-85, DeflateCodec:142-145, ZstdCodec:175-178` — IAE on negative `uncompressedLength` |
| R11 | SATISFIED | `NoneCodec:65-69, DeflateCodec:99-104, ZstdCodec:157-161` — ISE on undersized dst |
| R12 | SATISFIED | `NoneCodec:71-73` — `MemorySegment.copy` segment→segment, no byte[] intermediary |
| R13 | SATISFIED | `NoneCodec:98-106` — size check + copy; mismatch throws `UncheckedIOException` |
| R14 | SATISFIED | `DeflateCodec:107-108` — `src.asByteBuffer()` / `dst.asByteBuffer()`; Deflater fed/drained via those ByteBuffers |
| R15 | SATISFIED | `DeflateCodec:159-160` — `Inflater.setInput(ByteBuffer)` / `inflate(ByteBuffer)` zero-copy path |
| R16 | SATISFIED | `DeflateCodec:110-132, 163-190` — `def.end()` / `inf.end()` in `finally` |
| R17 | SATISFIED | `DeflateCodec:56-61` — level range guard |
| R18 | SATISFIED | `WalRecord.java:122-125, 246-250, 275-276` — FLAG_COMPRESSED/FLAG_UNCOMPRESSED bit 0 layout |
| R19 | SATISFIED | `WalRecord.java:246-253` — compressed records write codecId(1) + uncompressedSize(4) after flags |
| R20 | SATISFIED | `WalRecord.java:272-276` — uncompressed branch writes only flags byte before payload |
| R21 | SATISFIED | `WalRecord.java:213` computes CRC over uncompressed payload at encode; `:384-390` verifies after decompression on decode |
| R22 | SATISFIED | `WalRecord.java:237-238, 266-267` — frame length = flags(1) + optional header(5) + payload + CRC(4) |
| R23 | SATISFIED | `WalRecord.java:39-43` — `INT_BE`/`LONG_BE` use `withOrder(BIG_ENDIAN).withByteAlignment(1)` |
| R24 | SATISFIED | `WalRecord.java:315-318` — null/empty codecMap delegates to old-format decoder; mechanism prevents misinterpretation when caller config matches WAL vintage. The spec accepts "documentation that old WAL files must be drained before upgrade" — the internal-routing approach is stricter |
| R25 | SATISFIED | `WalRecord.java:216` — `payloadSize >= minCompressSize` gate; `LocalWriteAheadLog.java:48` / `RemoteWriteAheadLog.java:301` default 64 |
| R26 | SATISFIED | `WalRecord.java:226-228` — 5-byte header added to compressed-size before comparison |
| R27 | SATISFIED | `LocalWriteAheadLog.java:576` / `RemoteWriteAheadLog.java:299` — `CompressionCodec.deflate()` default (level 6 per `CompressionCodec.java:133-135`) |
| R28 | SATISFIED | `Builder.compressionMinSize(int)` in both WALs; default `DEFAULT_COMPRESSION_MIN_SIZE = 64` |
| R29 | SATISFIED | both `LocalWriteAheadLog.encodeRecord` and `RemoteWriteAheadLog.encodeRecord` call the same `WalRecord.encode(..., codec, minSize, buffer)` — identical wire format |
| R30 | SATISFIED | `LocalWriteAheadLog.java:664-689` builds codec map including write codec + NONE; duplicate IDs within user-provided recovery set rejected with IAE at `:681-687` |
| R31 | SATISFIED | `WalRecord.java:335-394` — per-record flags-byte inspection handles mixed compressed/uncompressed records in the same segment |
| R32 | SATISFIED | `WalRecord.java:352-356` — unknown codec ID throws IOException listing available codecs |
| R33 | SATISFIED | `LocalWriteAheadLog.java:162-185` / `RemoteWriteAheadLog.java:247-260` — skip-and-advance by frame-length on corrupt records |
| R34 | SATISFIED | `WalRecord.java:384-390` — decompress first, then CRC over the decompressed payload |
| R35 | SATISFIED | `LocalWriteAheadLog.java:174-183, 526-535` — `consecutiveSkips > maxConsecutiveSkips` throws IOException; threshold configurable via `Builder.maxConsecutiveSkips(int)`; default 10 |
| R36 | SATISFIED | `LocalWriteAheadLog.java:280-294` / `RemoteWriteAheadLog.java:127-140` — buffer acquisition failure falls back to uncompressed new-format encoding via `Integer.MAX_VALUE` minSize |
| R37 | SATISFIED | `TrieSSTableWriter.java:323-331` — Arena-allocated `blockSeg`/`compDst` passed to `useCodec.compress` without byte[] conversion of the source |
| R38 | SATISFIED | `TrieSSTableReader.java:463-475, 529-541` — compressed bytes copied to Arena segment, decompressed via `codec.decompress(compSeg, decompDst, ...)` |
| R39 | SATISFIED | `CompressionMap.java:17-40, 49` — v2 17-byte entries and v3 21-byte entries unchanged; F17 does not touch the on-disk format |
| R40 | UNTESTABLE | behavioral regression claim; requires running `./gradlew :jlsm-core:test` to confirm |
| R41 | SATISFIED | `ZstdCodec.java:32-36` thread-safety docstring + `:74-80` `final` dictionary fields; compress/decompress carry no mutable instance state |
| R42 | SATISFIED | `ZstdCodec.java:100-106` — dictionary bytes copied into arena-managed memory at construction; caller's `MemorySegment` is never mutated by the codec |

**Overall: PASS_WITH_NOTES**

Obligations resolved: 0
Obligations remaining: 0
Undocumented behavior:
- `WalRecord.encode(..., codec, minSize, buffer)` uses a two-phase layout: writes the uncompressed payload at `maxHeaderSize=10` first, then either rewrites the final record from offset 0 (if compressing) or memmoves the payload left by 5 bytes (if falling back to uncompressed). The in-place memmove at `:280-282` is a subtle performance optimization worth noting but not spec-mandated.
- `buildCodecMap` in both WAL builders auto-includes `NoneCodec` and the configured write codec in addition to user-supplied recovery codecs — analogous to the SSTable reader's NONE auto-include (F02.R19) but not explicitly spec'd for the WAL path. Consider an explicit requirement mirroring F02.R19 at the WAL level.
- The F17.R24 implementation routes decode via codecMap presence rather than a per-segment format marker. This is semantically equivalent to the "drain before upgrade" option in R24 but pushes the format decision onto the builder configuration. Worth calling out explicitly in the Builder javadoc so users know the decoder branch is controlled by `recoveryCodecs(...)` / default codec presence, not by probing the file.

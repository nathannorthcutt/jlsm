---
{
  "id": "F17",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
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
    "max-compressed-length"
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

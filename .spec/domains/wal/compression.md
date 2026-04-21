---
{
  "id": "wal.compression",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "wal",
    "compression"
  ],
  "requires": [
    "compression.codec-contract"
  ],
  "invalidates": [],
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
  "open_obligations": [],
  "_migrated_from": [
    "F17"
  ]
}
---
# wal.compression — Compression

## Requirements

### WAL compressed record format

R1. The WAL record format must include a flags byte immediately after the 4-byte frame length field. The flags byte must use bit 0 to indicate whether the payload is compressed (1 = compressed, 0 = uncompressed). Bits 1-7 are reserved and must be written as 0.

R2. When the flags byte indicates compression (bit 0 = 1), the next byte must be the codec ID (1 byte, following the F02.R1 reservation scheme) and the following 4 bytes must be the uncompressed payload size (big-endian int). These 5 bytes must appear between the flags byte and the compressed payload.

R3. When the flags byte indicates no compression (bit 0 = 0), no codec ID or uncompressed size fields are present. The payload immediately follows the flags byte.

R4. The CRC32 checksum at the end of the record must be computed over the uncompressed payload bytes (entry type through value bytes), regardless of whether the record is compressed. On read, the payload must be decompressed before CRC verification.

R5. The frame length field must include all bytes after itself: flags byte (1) + optional compression header (5 bytes if compressed) + payload bytes (compressed payload for compressed records, uncompressed payload for uncompressed records) + CRC32 (4 bytes).

R6. All new fields in the WAL record format must use ValueLayout with byteAlignment(1), consistent with the existing WalRecord format. Big-endian byte order for multi-byte values.

### WAL format versioning

R7. The WAL must distinguish old-format records (pre-compression, no flags byte) from new-format records. The mechanism must prevent a new-format reader from misinterpreting old-format entry type bytes (0x01 PUT, 0x02 DELETE) as flags bytes. A format version marker in the WAL segment header, a magic number per segment, or documentation that old WAL files must be drained before upgrade are all acceptable approaches.

### WAL compression behavior

R8. The uncompressed payload size for threshold comparison is the size of the region that would be compressed (entry type through value bytes), not the total record size including framing overhead. Records with this size below the configured minimum threshold must be written uncompressed (flags byte = 0x00). The default minimum threshold must be 64 bytes.

R9. If the compressed payload size plus 5 bytes (compression header overhead: 1 byte codec ID + 4 bytes uncompressed size) is greater than or equal to the uncompressed payload size, the record must be written uncompressed (flags byte = 0x00). The compression attempt must not increase on-disk record size.

R10. The WAL must accept a CompressionCodec via its builder. If no codec is explicitly provided, the WAL must use DEFLATE level 6 as the default compression codec. This is a deliberate post-ADR scoping decision — the wal-compression ADR suggested off-by-default, but the user chose on-by-default during scoping (pre-1.0 library).

R11. The WAL must accept a compression minimum-size threshold via its builder. If not explicitly provided, the default threshold of 64 bytes must be used.

R12. Both LocalWriteAheadLog and RemoteWriteAheadLog must support compression with identical format semantics. The compressed record format must be the same in both implementations.

### WAL recovery (compressed records)

R13. The WAL recovery path must accept a set of CompressionCodec instances and build a codec-ID-to-implementation map, analogous to F02.R18 for SSTable readers. Duplicate codec IDs in the provided set must be rejected with an IllegalArgumentException.

R14. During WAL recovery, the reader must inspect the flags byte of each record to determine whether decompression is needed. The reader must handle mixed compressed and uncompressed records within the same WAL segment (local) or directory (remote).

R15. During recovery, if a record's flags byte indicates compression but the codec ID is not in the reader's codec map, the reader must throw an IOException identifying the unknown codec ID and the available codecs.

R16. During recovery, if decompression fails (codec throws), the record must be treated as corrupt — the same skip-and-continue behavior used for CRC mismatches on uncompressed records. After skipping a corrupt compressed record, the reader must advance by frame-length bytes (as for any corrupt record), not by the decompressed size.

R17. During recovery, CRC32 verification must occur after decompression. A CRC mismatch on the decompressed payload must trigger the same skip-and-log behavior as for uncompressed records.

R18. When more than 10 consecutive records are skipped due to decompression failure in a single recovery pass, the WAL must throw an IOException indicating systematic codec failure, rather than silently continuing. The threshold of 10 must be configurable via the builder.

### WAL write-path buffer management

R19. The WAL write path must handle the case where a compression destination buffer of maxCompressedLength(payloadSize) cannot be acquired from the ArenaBufferPool, either by writing the record uncompressed or by propagating the allocation failure as an IOException.

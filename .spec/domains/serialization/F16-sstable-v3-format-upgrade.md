---
{
  "id": "F16",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["serialization", "storage"],
  "requires": ["F02"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "per-block-checksums",
    "backend-optimal-block-size",
    "sstable-block-compression-format"
  ],
  "kb_refs": [
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": [
    "Update backend-optimal-block-size ADR: max 1 MiB → 32 MiB, constants changed (LARGE_BLOCK_SIZE → HUGE_PAGE_BLOCK_SIZE 2 MiB, REMOTE_BLOCK_SIZE 64 KiB → 8 MiB)"
  ]
}
---

# F16 — SSTable v3 Format Upgrade

## Requirements

### Compression map entry format (v3)

R1. The v3 compression map entry must contain five fields totaling 21 bytes: block offset (8 bytes, big-endian long), compressed size (4 bytes, big-endian int), uncompressed size (4 bytes, big-endian int), codec ID (1 byte), and CRC32C checksum (4 bytes, big-endian int). The checksum field must follow the codec ID field.

R2. The v3 compression map serialization and deserialization must use long arithmetic for total size calculation (4L + blockCount * 21L) to prevent integer overflow. Deserialization must reject overflow or results exceeding Integer.MAX_VALUE with an IllegalArgumentException. Serialization must reject the same conditions with an IllegalStateException. This requirement governs v3 format only; v2 deserialization must continue to use 17-byte entry arithmetic per F02 R27.

R3. The compression map deserializer must determine the entry size from the SSTable version: 21 bytes for v3 (with checksum), 17 bytes for v2 (without checksum). The SSTable version must be provided by the caller based on footer detection, not inferred from the map data.

### CRC32C checksum computation (write path)

R4. The CRC32C checksum must be computed over the exact bytes that will be written to disk for each block, after the compression-fallback decision (F02 R23). For a compressed block, this is the compressed payload. For an uncompressed block or a NONE-fallback block (where compression did not reduce size), this is the raw uncompressed payload. The checksum must not cover block framing, entry count headers, or metadata outside the block payload.

R5. The CRC32C checksum must use java.util.zip.CRC32C. No external checksum library or custom implementation may be used.

### CRC32C checksum verification (read path)

R6. When reading a v3 SSTable, the reader must compute CRC32C over the raw bytes read from disk for each block and compare against the stored checksum. Verification must occur after reading from disk but before decompression.

R7. CRC32C verification must apply to all block read paths: point-get (with BlockCache), full-scan streaming (without BlockCache), and range-scan streaming (without BlockCache). Cache hits must not re-verify — verification occurs only when bytes are read from disk.

R8. When reading a v2 SSTable (no checksums in compression map), the reader must skip CRC32C verification entirely. No default, zero, or sentinel checksum may be assumed for v2 files.

### CorruptBlockException

R9. A checksum mismatch must produce a CorruptBlockException, which must extend IOException. The exception must carry three accessible values: the block index (int), the expected checksum (int), and the actual checksum (int). The exception message must include all three values in human-readable form.

### Block size parameterization

R10. The SSTable writer must accept a block size parameter at construction time via its builder. The default block size must be 4096 bytes (SSTableFormat.DEFAULT_BLOCK_SIZE).

R11. The block size must be validated at construction time: minimum 1024 bytes, maximum 33,554,432 bytes (32 MiB), and must be a power of 2. Invalid values must produce an IllegalArgumentException with a descriptive message identifying which constraint was violated.

R12. The writer must use the configured block size as the threshold for flushing data blocks. No hardcoded block size constant may be used in the block flush decision.

### Named block size constants

R13. The SSTable format must define three named block size constants: DEFAULT_BLOCK_SIZE (4,096 — local SSD page size, POSIX/ext4/xfs), HUGE_PAGE_BLOCK_SIZE (2,097,152 — Linux x86_64/ARM64 default huge page, 2 MiB), and REMOTE_BLOCK_SIZE (8,388,608 — S3/GCS default chunk size, 8 MiB). All three values must satisfy the block size validation constraints (power of 2, within 1024–33,554,432 range).

### Footer v3

R14. The v3 footer must contain nine 8-byte big-endian fields totaling 72 bytes: compression map offset, compression map length, key index offset, key index length, bloom filter offset, bloom filter length, entry count, block size, and magic number. The magic number must be 0x4A4C534D53535403 and must occupy the final 8 bytes of the file.

R15. The block size field in the v3 footer must store the block size used at write time as a long value. When reading, the reader must use this stored value, not any default or configured value.

### Writer version output

R16. The writer must produce v3 format SSTables when a compression codec is configured. The writer must not produce v2 format SSTables. Uncompressed writes (no codec) must continue to produce v1 format. If a non-default block size is configured but no compression codec is provided, the writer must throw IllegalArgumentException at construction time — non-default block sizes require v3 format (compression) to be recorded in the footer.

### Backward compatibility

R17. A v3-capable reader must detect the SSTable version by reading the magic number from the final 8 bytes of the file. It must support three magic values: v1 (0x4A4C534D53535401, 48-byte footer), v2 (0x4A4C534D53535402, 64-byte footer), and v3 (0x4A4C534D53535403, 72-byte footer). An unrecognized magic must produce an IOException.

R18. When reading a v2 SSTable, the v3-capable reader must use 17-byte compression map entries (no checksum), skip CRC32C verification, and use 4096 as the block size (the value hardcoded in v2 writers). This value must not be derived from the DEFAULT_BLOCK_SIZE constant, which may change independently.

R19. When reading a v1 SSTable, the v3-capable reader must fall back to v1 reading logic with no compression map, no decompression, and no CRC32C verification. v1 behavior must be identical to F02 R15.

### Footer validation

R20. The v3 footer must validate all offset and length fields as non-negative, consistent with F02 R29. The block size field must additionally be validated against the same constraints as writer construction: minimum 1024, maximum 33,554,432, power of 2. An invalid block size from on-disk data must produce an IOException, not an IllegalArgumentException (the source is untrusted disk data, not a caller argument).

R21. The v3 footer must validate section ordering: compression map must end before key index starts, key index must end before bloom filter starts, bloom filter must end before the footer. The footer size for ordering validation is 72 bytes for v3.

### Compression map entry validation

R22. Compression map entry construction must accept the full Java int range for checksum values, including negative values in signed interpretation. CRC32C produces values spanning 0x00000000–0xFFFFFFFF, which maps to the full signed int range in Java. No sign-based validation may be applied to the checksum field.

### Tree builder integration

R23. The tree builder must accept block size configuration and propagate it to writer factories, so that all SSTables produced by a tree use the configured block size. The default must be DEFAULT_BLOCK_SIZE (4096) for backward compatibility.

### Compaction integration

R24. SSTables produced by compaction must use the block size configured on the tree. Source SSTables may be any version (v1, v2, v3); the compactor must rely on the reader's version detection (R17) for each source SSTable and must not assume a version. Output SSTables must be v3 format when compression is configured.

---

## Design Narrative

### Intent

Extend the SSTable on-disk format from v2 to v3 to implement two accepted
architectural decisions: per-block CRC32C checksums for silent corruption
detection, and parameterized block size for remote-backend optimization.
Both changes modify the same file sections (CompressionMap, footer) and
share the version bump, making a combined implementation natural.

### Why this approach

CRC32C is the industry standard for storage block integrity (RocksDB,
Cassandra, LevelDB). Hardware acceleration on x86 (SSE 4.2) and AArch64
makes it effectively free. The checksum is computed over on-disk bytes
(compressed or raw) rather than decompressed content because: (a) it
detects corruption at the layer where corruption occurs (disk/network),
(b) it avoids redundant decompression on the verify path, and (c) it
matches the approach used by RocksDB and LevelDB.

Block size parameterization allows callers to optimize for their
deployment context without the library needing backend auto-detection.
The 4KB default preserves backward compatibility with local SSD workloads.
Named constants guide callers toward validated configurations for common
backends.

### What was ruled out

- **Decompressed-content checksums:** Would require decompression before
  verification, doubling the work on the read path. On-disk checksums
  catch the same corruption class (disk/network bit-rot) more efficiently.
- **Per-entry checksums:** Extremely high overhead (4 bytes per entry vs
  4 bytes per block). Block-level granularity matches the I/O unit.
- **Cryptographic hashes:** SHA-256 would detect malicious tampering but
  is orders of magnitude slower. The ADR scopes this as integrity, not
  security.
- **Auto-detecting backend type:** The library cannot reliably determine
  deployment context from the FileSystem provider. Caller configuration
  is simpler and more predictable.
- **Variable footer size with field count:** A self-describing footer
  would be more extensible but adds parsing complexity. Fixed-size
  footers with magic-based version detection is the established pattern.

### Known limitations

- **Cache-masked corruption:** CRC32C verification occurs only when
  bytes are read from disk. If a block is cached and the on-disk data
  is subsequently corrupted (bit-rot), the cached copy continues to be
  served correctly. After cache eviction, the next read will detect the
  corruption and throw CorruptBlockException. The "first read succeeds,
  later read throws" pattern is inherent to any verify-on-read scheme
  with caching.

### ADR deviations

This spec deviates from the `backend-optimal-block-size` ADR in three
ways, all confirmed by user during spec authoring:
- Max block size: 1 MiB → 32 MiB (to support cloud provider chunk sizes)
- REMOTE_BLOCK_SIZE: 65,536 → 8,388,608 (S3/GCS default chunk size)
- LARGE_BLOCK_SIZE (262,144) replaced by HUGE_PAGE_BLOCK_SIZE (2,097,152)
The ADR must be updated before implementation to maintain consistency.

### Out of scope

- End-to-end SSTable integrity (footer/index checksums)
- Corruption repair or recovery
- v1 format changes (v1 remains uncompressed, no compression map)
- Block cache sizing relative to block size

### Cross-references

- F02 (Block-Level SSTable Compression): prerequisite — defines the v2
  format, compression map, codec contract, and BlockCache integration.
- F08 (Streaming Block Decompression): CRC32C verification must apply
  to both cached and streaming read paths defined by this spec.
- ADR: per-block-checksums — CRC32C algorithm choice and entry format.
- ADR: backend-optimal-block-size — named constants and validation rules.
- ADR: sstable-block-compression-format — v2 format being extended.

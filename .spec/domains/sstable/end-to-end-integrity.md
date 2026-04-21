---
{
  "id": "sstable.end-to-end-integrity",
  "version": 2,
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
    "per-block-checksums"
  ],
  "kb_refs": [
    "systems/database-engines/corruption-detection-repair"
  ],
  "open_obligations": [],
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

R3. The writer must write the VarInt prefix first, then record `writePosition` (the byte immediately after the VarInt) as the `blockOffset` in the compression map entry.

R4. The reader's normal block-read path must not attempt to read or skip the VarInt prefix — it must use the stored blockOffset directly, which already points past the prefix.

R5. A VarInt must encode values up to at least SSTableFormat.MAX_BLOCK_SIZE (32 MiB). This requires at most 4 bytes in LEB128 unsigned encoding.

R6. If a VarInt read encounters a continuation bit still set after the 5th byte, the reader must throw CorruptSectionException identifying the data section.

### Recovery scan

R7. TrieSSTableReader must expose a recovery scan capability that walks the data section sequentially: read VarInt → read that many bytes → next VarInt, without requiring the compression map.

R8. The recovery scan must use the `blockCount` field from the footer as its loop bound, terminating after exactly `blockCount` blocks have been read.

R9. If the recovery scan encounters fewer blocks than `blockCount` before reaching the compression map offset, it must throw CorruptSectionException.

R10. During a recovery scan, per-block CRC32C verification must use CorruptBlockException (from F16.R9), not CorruptSectionException — the corruption is at the block level, not the section level.

### v5 footer layout

R11. The v5 SSTable footer must be 112 bytes with the following layout:

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

R12. The v5 footer magic number must be 0x4A4C534D53535405 (ASCII "JLSMSST\x05").

### Per-section CRC32C checksums

R13. TrieSSTableWriter must compute CRC32C for each metadata section at finish() time using java.util.zip.CRC32C (per F16.R5) and store the checksums in the footer.

R14. Each section checksum must be computed over the raw bytes as written to disk for that section.

R15. If dictLength is 0 (no dictionary section), dictChecksum must be written as 0.

R16. The footerChecksum must be computed as CRC32C over the 100 footer bytes at offsets [0..100), excluding the footerChecksum and magic fields.

### blockCount validation

R17. The writer must store the total number of data blocks written as `blockCount` in the footer.

R18. At SSTable open time, the reader must validate that the compression map contains exactly `blockCount` entries. On mismatch, the reader must throw CorruptSectionException.

### fsync discipline

R19. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing all data blocks and before writing any metadata section.

R20. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing all metadata sections and before writing the footer.

R21. When the output channel is a FileChannel, TrieSSTableWriter.finish() must call force(true) after writing the footer.

R22. If any force(true) call throws IOException, the writer must transition to FAILED state and propagate the exception. No retry is permitted.

R23. When the output channel is not a FileChannel (remote/NIO providers), the writer must skip all force() calls — the remote provider handles durability.

R24. The conditional dispatch must use instanceof pattern matching: `if (channel instanceof FileChannel fc)`.

### Verification at open

R25. TrieSSTableReader must verify the footerChecksum first upon opening a v5 SSTable, regardless of whether the reader is in eager or lazy mode. Footer verification must complete before the factory method returns.

R26. If the footerChecksum fails, the reader must throw CorruptSectionException with section name "footer", the stored footerChecksum as expected value, and the computed CRC32C as actual value.

R27. For eager mode: the reader must verify all section checksums (compression map, dictionary, key index, bloom filter) during open, before returning.

R28. For lazy mode: the reader must verify each section's CRC32C when that section is first loaded on demand. The footer checksum (R25) is always verified eagerly regardless of mode.

R29. On any section checksum mismatch, the reader must throw CorruptSectionException identifying which section failed and including the expected vs actual checksum values.

R30. If dictLength is 0, the reader must skip dictionary checksum verification (not compute CRC32C over zero bytes).

### CorruptSectionException

R31. CorruptSectionException must extend IOException.

R32. CorruptSectionException must include the section name (String), expected checksum (int), and actual checksum (int).

R33. CorruptSectionException must be in an exported package accessible to callers via JPMS.

### Version compatibility

R34. The v5-capable reader must support magic values for v1, v2, v3, v4, and v5. An unrecognized magic must produce an IOException. (Supersedes F16.R17.)

R35. For v4 or earlier files, the reader must not attempt VarInt prefix decoding, section checksum verification, blockCount validation, or recovery scan. The reader must determine footer size from the magic version and must not overread past the version-specific footer size.

R36. The writer must produce v5 format SSTables when a compression codec is configured. v5 supersedes v3 and v4 as the compressed output format. (Supersedes F16.R16.)

### Section ordering

R37. The v5 writer must write sections in this order: data blocks → compression map → dictionary (if any) → key index → bloom filter → footer. The reader must validate that section offsets are monotonically increasing and non-overlapping.

---

## Design Narrative

Three-layer integrity extends per-block checksums (v3) to cover the entire
SSTable. VarInt-prefixed blocks make the data section self-describing, enabling
recovery when the compression map is corrupt. fsync discipline prevents the most
common local corruption vector (partial writes with OS write reordering). Per-section
CRC32C detects corruption in all metadata sections at open time.

The three fsyncs in the write path (after data, after metadata, after footer) ensure
that the footer magic number is a reliable commit marker: if it exists on disk,
all preceding data and metadata were fsynced before it was written.

Recovery scans use VarInt block length prefixes to walk the data section without the
compression map, and blockCount from the footer as a termination condition. Per-block
CRC32C (from the compression map or recomputed during scan) validates each recovered
block independently.

See `.decisions/sstable-end-to-end-integrity/adr.md` for the full rationale.

## Adversarial Review Notes (v2)

v1 had 21 requirements with 14 failures. Key fixes in v2:
- R1 clarified: VarInt encodes on-disk byte count (compressed length), not uncompressed
- R3/R4 split: write-order dependency (VarInt first, then record offset) and reader double-skip prevention
- R6: malformed VarInt guard (5-byte max)
- R7-R10: recovery scan fully specified with blockCount termination, entry point, and CorruptBlockException for per-block failures
- R15: dictChecksum = 0 when no dictionary; R30: reader skips verification
- R16: footerChecksum scope explicit ([0..100) = 100 bytes)
- R19-R22: three fsyncs (data, metadata, footer) with IOException → FAILED state
- R25/R27/R28: eager vs lazy verification explicitly distinguished
- R31-R33: CorruptSectionException in exported package
- R34-R36: version compatibility supersedes F16.R16 and F16.R17
- R37: section ordering validation
- ADR 104→112 byte discrepancy flagged for correction

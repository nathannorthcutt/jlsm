---
{
  "id": "sstable.format-v2",
  "version": 2,
  "status": "DEPRECATED",
  "state": "APPROVED",
  "superseded_by": "sstable.end-to-end-integrity",
  "deprecated_at": "2026-04-24",
  "deprecation_reason": "Pre-GA SSTable v1-v4 collapse per pre-ga-format-deprecation-policy. v5 (sstable.end-to-end-integrity) is the only supported on-disk format.",
  "domains": [
    "sstable"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": "sstable.end-to-end-integrity",
  "decision_refs": [
    "sstable-block-compression-format",
    "compression-codec-api-design",
    "codec-dictionary-support",
    "compaction-recompression"
  ],
  "kb_refs": [
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F02"
  ]
}
---

# sstable.format-v2 — Format V2

## Requirements

### SSTable file format v2

R1. The v2 SSTable file layout must be: data blocks, compression map, key index, bloom filter, footer (64 bytes). The footer must be the last 64 bytes of the file.

R2. The v2 footer must contain eight 8-byte big-endian fields: compression map offset, compression map length, key index offset, key index length, bloom filter offset, bloom filter length, entry count, and magic number (0x4A4C534D53535402). All offset and length fields are long-width values.

R3. The compression map must consist of a 4-byte big-endian block count followed by one 17-byte entry per block. Each entry contains: block offset (8 bytes), compressed size (4 bytes), uncompressed size (4 bytes), and codec ID (1 byte). All multi-byte values are big-endian.

R4. Key index entries in v2 must reference blocks by index into the compression map and an intra-block byte offset within the decompressed block, not by absolute file offset. The entry format must be: key length (4 bytes), key bytes (variable), block index (4 bytes), intra-block offset (4 bytes).

### Backward compatibility

R5. A v2-capable reader must detect v1 SSTables by reading the magic number from the final 8 bytes of the file. If the magic matches v1 (0x4A4C534D53535401), the reader must fall back to v1 reading logic with no decompression. If the magic matches neither v1 nor v2, the reader must throw an IOException. After detecting v2 magic, the reader must verify the file is at least 64 bytes (v2 footer size) before attempting to read the full footer.

R6. A v1-only reader encountering a v2 file must fail with a descriptive IOException identifying the unknown magic, not with a silent data corruption or internal error.

### Self-describing format and codec resolution

R7. The reader must determine compression codec and block sizes entirely from on-disk metadata. No external configuration (tree config, environment variable, etc.) may be required to read a v2 file beyond providing the set of available codec implementations.

R8. The reader must build a codec ID-to-implementation map from the codec implementations provided at open time. Duplicate codec IDs must be rejected with an illegal argument exception. The check must be explicit (not reliant on silent map overwrite).

R9. The reader must auto-include the NONE codec (ID 0x00) in its codec map only if no codec with ID 0x00 is already present in the provided list. The writer may fall back to NONE for any block where compression does not reduce size, so every v2 file may contain NONE-coded blocks.

R10. If the compression map contains a codec ID not present in the reader's codec map, the reader must throw an IOException identifying the unknown codec ID. This must be a runtime check, not an assertion, because codec IDs come from untrusted on-disk data.

R11. Null elements in the reader's codec list must be rejected with a descriptive exception (including the element index), not a raw NullPointerException from internal iteration.

### Compression map validation

R12. Compression map entries must reject negative block offset, compressed size, and uncompressed size at construction time with an illegal argument exception.

R13. Compression map entries must reject impossible size combinations: compressed size of 0 with uncompressed size greater than 0 (cannot decompress nothing into something). For non-NONE codecs, uncompressed size of 0 with compressed size greater than 0 must also be rejected. Empty entries (both sizes 0, codec ID 0x00) are valid but the writer must not produce them; the reader must handle them gracefully (skip with no entries).

R14. Compression map serialization and deserialization must use long arithmetic for size calculations (4L + blockCount * 17L) to prevent integer overflow when block count is large. Deserialization must reject overflow or results exceeding Integer.MAX_VALUE with an illegal argument exception. Serialization must reject the same conditions with an illegal state exception (the map's own state is the problem, not a method argument).

R15. Compression map deserialization must reject negative block counts with an illegal argument exception, not silently return an empty map.

### Footer validation

R16. Footer construction from on-disk data must validate all offset and length fields are non-negative. Negative values from corrupt data must produce an IOException, not cascade to uninformative internal exceptions.

R17. Footer validation must guard against long-to-int truncation across all consumers of footer field values -- not just within the footer itself, but in every code path that uses footer offsets and lengths for I/O operations, buffer allocation, or array indexing.

### File offset and length width

R18. All file offsets and section lengths in the read path must be handled as long values. No file offset, section offset, or section length may be narrowed to int-width at any point in the read pipeline. Both eager and lazy reader modes must support SSTables up to Long.MAX_VALUE bytes.

R19. Intra-block offsets and block indices may use int-width values, since individual blocks are bounded by the configured block size (default 4 KiB). The maximum number of blocks per SSTable and the maximum uncompressed size of a single block must not exceed Integer.MAX_VALUE. This is the only permitted use of int-width values for positional data in the SSTable read/write path.

### Key index validation

R20. When reading a v2 key index, the reader must validate that each entry's block index is within [0, blockCount) and each intra-block offset is non-negative. Invalid values from corrupt data must produce an IOException with a descriptive message.

### Footer section ordering

R21. Footer validation must verify that file sections do not overlap: compression map must end before key index starts, key index must end before bloom filter starts, and bloom filter must end before the footer. Specifically: mapOffset + mapLength <= idxOffset, idxOffset + idxLength <= fltOffset, and fltOffset + fltLength <= fileSize - footerSize.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

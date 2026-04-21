---
{
  "id": "compression.none-codec",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
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
# compression.none-codec — None Codec

## Requirements

### NONE codec (MemorySegment)

R1. The NONE codec compress method must copy the source segment to the destination segment using MemorySegment.copy() (segment-to-segment overload) and return a slice of the destination with the same byteSize() as the source. No byte[] intermediary may be used.

R2. The NONE codec decompress method must copy the source to the destination using MemorySegment.copy() and return a slice of the destination with byteSize() equal to uncompressedLength. For NONE codec, the source byteSize() must equal uncompressedLength; a mismatch must produce an UncheckedIOException.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

---
{
  "id": "compression.deflate-codec",
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
# compression.deflate-codec — Deflate Codec

## Requirements

### DEFLATE codec (MemorySegment — zero-copy)

R1. The DEFLATE codec compress method must obtain a direct ByteBuffer from the source MemorySegment via asByteBuffer(), pass it to Deflater.setInput(ByteBuffer), and deflate into a direct ByteBuffer obtained from the destination MemorySegment via asByteBuffer(). No byte[] intermediary may be created by the codec implementation. The zero-copy guarantee holds only when both segments are Arena-allocated (native memory). When heap-backed segments are passed, the JDK may internally use byte[] paths; this is a caller responsibility, not a codec defect.

R2. The DEFLATE codec decompress method must obtain a direct ByteBuffer from the source MemorySegment via asByteBuffer(), pass it to Inflater.setInput(ByteBuffer), and inflate into a direct ByteBuffer obtained from the destination MemorySegment via asByteBuffer(). No byte[] intermediary may be created by the codec implementation. The same native-memory caveat from R14 applies.

R3. The DEFLATE codec must allocate and release Deflater/Inflater instances within each call. Native resources must be released in a finally block. This reaffirms F02.R6.

R4. The DEFLATE codec must accept a configurable compression level (0-9) at construction time. Values outside 0-9 must be rejected with an IllegalArgumentException. This reaffirms F02.R5.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

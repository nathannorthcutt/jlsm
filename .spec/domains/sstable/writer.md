---
{
  "id": "sstable.writer",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "sstable",
    "compression"
  ],
  "requires": [
    "compression.codec-contract"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "sstable-block-compression-format",
    "compression-codec-api-design",
    "codec-dictionary-support",
    "compaction-recompression",
    "wal-compression",
    "codec-thread-safety",
    "max-compressed-length"
  ],
  "kb_refs": [
    "algorithms/compression/block-compression-algorithms",
    "algorithms/compression/wal-compression-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F02",
    "F17"
  ]
}
---
# sstable.writer — Writer

## Requirements

### Writer behavior *(from F02)*

R1. The writer must accept a single compression codec for the entire file. Each data block must be compressed independently using that codec. The NONE fallback per R23 is the sole exception -- a single writer run may produce blocks with two codec IDs (the configured codec and NONE).

R2. If the compressed output for a block is greater than or equal to the uncompressed block size (including block header), the writer must store the block uncompressed and record codec ID 0x00 (NONE) in the compression map entry for that block. The writer must not store an expansion.

R3. The writer must build compression map entries during the write pass and serialize the complete map after all data blocks have been written, before the key index.

### Concurrency *(from F02)*

R4. Lazy reader instances sharing a file channel must synchronize the position-then-read sequence to prevent concurrent reads from interleaving and producing corrupt data or wrong-offset reads. Eager readers are inherently thread-safe for reads (final reference to pre-loaded data) and require no synchronization.

### Block cache integration *(from F02)*

R5. The block cache must store decompressed block content. Compression and decompression must occur below the cache layer: reads that hit the cache must return decompressed data without invoking the codec. Reads that miss the cache must decompress before caching.

### Tree builder integration *(from F02)*

R6. The tree builder must accept a compression codec configuration, defaulting to NONE (no compression). The builder must propagate the codec to both writer and reader factories so that all SSTables produced by the tree use the configured codec and the reader can decompress them.

### Error handling *(from F02)*

R7. All conditions reachable from untrusted on-disk data (corrupt footer, unknown codec ID, decompression failure, malformed compression map, invalid key index entries) must be checked with runtime logic, not assertions. Assertions are stripped in production and must not be the sole guard for data-dependent conditions.

R8. Corrupted compressed blocks must produce an IOException with a descriptive message, not crash the JVM or propagate an unhandled exception type.

### Silent failure documentation *(from F02)*

R9. Compression map deserialization must reject trailing bytes beyond the serialized entries with an `IllegalArgumentException` whose message identifies the actual and expected byte lengths. Deflate decompression may still silently ignore trailing compressed bytes beyond the decompression target; this asymmetry is inherent to the zlib stream format and must be documented in the deflate codec's API. [Amended v2 2026-04-16: was "silently ignore trailing bytes"; compression map is now strict. Verified during v1 verification — compression map trailing-byte rejection was added for defence-in-depth.]

R10. Iterator behavior after the underlying reader has been closed is undefined. Callers must not rely on hasNext() returning accurate results after close. This must be documented in the reader's public API.

### SSTable codec migration *(from F17)*

R11. TrieSSTableWriter must use the MemorySegment compress method for block compression. The block content MemorySegment must be passed directly to the codec — no conversion to byte[] before compression.

R12. TrieSSTableReader must use the MemorySegment decompress method for block decompression. The compressed block MemorySegment read from disk must be passed directly to the codec — no conversion to byte[] before decompression.

R13. The SSTable compression map format (v2 and v3) must remain unchanged. The codec migration affects only the in-memory API surface, not the on-disk format.

R14. All existing SSTable tests that exercise compression must continue to pass after the codec API migration. The behavioral contract is unchanged — only the method signatures change.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.

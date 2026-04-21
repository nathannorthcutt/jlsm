---
{
  "id": "compression.codec-contract",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "compression"
  ],
  "requires": [
    "F02"
  ],
  "invalidates": [
    "F02.R2",
    "F02.R3",
    "F02.R4",
    "F02.R8",
    "F02.R9",
    "F02.R10"
  ],
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

# compression.codec-contract — Codec Contract

## Requirements

### Compression codec contract *(from F02)*

R1. A compression codec must expose a unique byte identifier (codec ID) that is stored per-block in the on-disk metadata. Codec IDs 0x00-0x7F are reserved for library-provided codecs. Codec IDs 0x80-0xFF are available for consumer-provided implementations. No runtime enforcement prevents consumers from using reserved IDs; this is a convention-based reservation.

R2. The DEFLATE codec (ID 0x02) must use the JDK's java.util.zip compression with a configurable level (0-9). Level values outside 0-9 must be rejected at construction time with an illegal argument exception.

R3. DEFLATE codec operations must allocate and release native compression resources (Deflater/Inflater) within each call. Native resources must be released in a finally block, never deferred to garbage collection.

R4. All codec operations must be safe to call concurrently from multiple threads without external synchronization. Statelessness (no shared mutable state) is the required mechanism.

### CompressionCodec MemorySegment API *(from F17)*

R5. A compression codec must provide a compress method accepting a source MemorySegment and a destination MemorySegment, returning a MemorySegment slice of the destination containing the compressed output. The codec must not mutate the source segment. The destination segment must be caller-provided and sized to at least maxCompressedLength(inputLength) bytes.

R6. A compression codec must provide a decompress method accepting a source MemorySegment, a destination MemorySegment, and the expected uncompressed length. The method must return a MemorySegment slice of the destination containing exactly uncompressedLength bytes. If the decompressed output does not match the expected length, the codec must throw an UncheckedIOException.

R7. The byte[]-based compress and decompress methods from F02.R2 and F02.R3 must be removed from the CompressionCodec interface. This is a deliberate post-ADR scoping decision — the wal-compression ADR's implementation guidance suggested retaining byte[] methods as deprecated, but the user chose a clean break during scoping (pre-1.0 library, no backward compatibility obligation).

R8. The codecId() method must be retained unchanged. The maxCompressedLength(int) method must be retained unchanged. Both operate on primitive values and are unaffected by the MemorySegment migration.

R9. All codec operations must remain safe to call concurrently from multiple threads without external synchronization. Statelessness (no shared mutable state) is the required mechanism. This reaffirms F02.R7 and the codec-thread-safety ADR.

### Input validation (MemorySegment) *(from F17)*

R10. All codec compress and decompress methods must reject null source or destination segments with a NullPointerException.

R11. The compress method must reject a source segment with byteSize() of 0 by returning a zero-length slice of the destination (no compression needed for empty input), not by throwing an exception. The zero-length source check must be evaluated before the destination size check (R10).

R12. If uncompressedLength is 0 and the source segment has byteSize() of 0, the decompress method must return a zero-length slice of the destination.

R13. If uncompressedLength is 0 and the source segment has byteSize() greater than 0, the decompress method must throw an UncheckedIOException (cannot decompress something into nothing).

R14. The decompress method must reject negative uncompressedLength values with an IllegalArgumentException.

R15. The compress method must throw an IllegalStateException if the source segment has byteSize() greater than 0 and the destination segment's byteSize() is less than maxCompressedLength(sourceByteSize). The caller is responsible for providing a sufficiently sized destination.

### Codec statelessness with dictionary configuration *(from F17)*

R16. A CompressionCodec instance configured with dictionary bytes at construction time must remain stateless and thread-safe per F02.R7 and R5 above. The dictionary is immutable shared state (read-only after construction), not mutable state. Multiple threads may call compress() and decompress() concurrently on the same dictionary-configured codec instance without synchronization. [New: codifies assumption from codec-dictionary-support ADR.]

R17. Dictionary bytes passed at codec construction must not be modified by the codec after construction. The codec must either copy the dictionary bytes or document that the caller must not mutate the source segment after construction. [New: from codec-dictionary-support ADR.]

---

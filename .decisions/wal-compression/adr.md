---
problem: "wal-compression"
date: "2026-04-12"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/CompressionCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/DeflateCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/NoneCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/internal/WalRecord.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/local/LocalWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/remote/RemoteWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
---

# ADR — WAL Compression

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| WAL Compression Patterns | Chosen approach — per-record format design | [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md) |
| Block Compression Algorithms | Codec characteristics (speed/ratio tradeoffs) | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |
| Multi-Writer WAL Design | Future architecture context — per-partition WAL | [`.kb/distributed-systems/data-partitioning/multi-writer-wal.md`](../../.kb/distributed-systems/data-partitioning/multi-writer-wal.md) |

---

## Files Constrained by This Decision

- `CompressionCodec.java` — add MemorySegment compress/decompress default methods
- `DeflateCodec.java` — rewrite to use `Deflater(ByteBuffer)` / `Inflater(ByteBuffer)` via `MemorySegment.asByteBuffer()`
- `NoneCodec.java` — add MemorySegment overrides using `MemorySegment.copy()`
- `WalRecord.java` — add compressed record format (flags byte, codec ID, uncompressed size)
- `LocalWriteAheadLog.java` — integrate compression on write path, decompression on replay
- `RemoteWriteAheadLog.java` — same integration for one-file-per-record pattern
- `TrieSSTableWriter.java` — migrate compress call to MemorySegment API
- `TrieSSTableReader.java` — migrate decompress calls to MemorySegment API

## Problem

Compress WAL records to reduce storage cost and write amplification. Evolve
the `CompressionCodec` interface to use `MemorySegment` for zero-copy
compression throughout the library.

## Constraints That Drove This Decision

- **MemorySegment-first**: All binary format work must use MemorySegment, not
  byte[]. This is a project-wide direction, not just a WAL concern.
- **Per-record self-describing**: Each WAL record must indicate its compression
  state without external metadata. The remote WAL (one-file-per-record) cannot
  use a compression map.
- **Write-path latency**: Compression is on every write. Overhead must be
  negligible relative to fsync cost (~15μs compress vs ~100-1000μs fsync).

## Decision

**Chosen approach: Per-Record Compression with MemorySegment-Native Codec API**

Compress WAL records individually with a per-record flags byte, codec ID, and
uncompressed size in the header. Evolve `CompressionCodec` to support
`MemorySegment` input/output as default methods (backward compatible for
external implementors). Migrate all callers (SSTable writer/reader + WAL) to
the MemorySegment API.

### WAL Record Format (Compressed)

```
┌─────────────────────────────────────────────────────┐
│ frame length     4 bytes int (of everything below)  │
│ flags            1 byte  (bit 0: compressed)        │
│ [if compressed]  codec ID          1 byte           │
│ [if compressed]  uncompressed size 4 bytes int      │
│ payload (compressed or uncompressed):               │
│   entry type       1 byte (0x01=PUT, 0x02=DEL)     │
│   sequence number  8 bytes long                     │
│   key length       4 bytes int                      │
│   key bytes        keyLength bytes                  │
│   [PUT] value len  4 bytes int                      │
│   [PUT] value bytes valueLength bytes               │
│ CRC32 checksum   4 bytes int (over uncompressed)    │
└─────────────────────────────────────────────────────┘
```

- **Uncompressed record overhead:** 1 byte (flags=0x00)
- **Compressed record overhead:** 6 bytes (flags + codec ID + uncompressed size)
- **CRC32 covers the uncompressed payload** — catches both storage corruption
  and decompression bugs
- **Minimum size threshold:** records below a configurable minimum (default
  64 bytes) skip compression

### CompressionCodec API Evolution

```java
// New default methods on CompressionCodec (backward compatible)
default MemorySegment compress(MemorySegment src, MemorySegment dst) { ... }
default MemorySegment decompress(MemorySegment src, MemorySegment dst,
        int uncompressedLength) { ... }
```

- Added as **default methods** — external implementors continue working via
  the byte[] bridge in the defaults
- `DeflateCodec` overrides using `Deflater.setInput(ByteBuffer)` /
  `Deflater.deflate(ByteBuffer)` with `MemorySegment.asByteBuffer()` for
  **true zero-copy** (direct ByteBuffer → native zlib reads native memory)
- `NoneCodec` overrides using `MemorySegment.copy()` (zero-copy passthrough)
- Old byte[] methods retained but marked `@Deprecated(forRemoval = false)` to
  signal migration direction

### Key Finding: Zero-Copy Deflate via Direct ByteBuffer

`MemorySegment.asByteBuffer()` on Arena-allocated segments returns a **direct
ByteBuffer** (`isDirect() == true`). The `Deflater`/`Inflater` ByteBuffer
overloads (available since Java 11) pass the direct buffer's native address
directly to zlib. No `byte[]` intermediary at any level:

```
MemorySegment (native) → asByteBuffer() → direct ByteBuffer → zlib native
```

This eliminates the byte[] copy that the falsification identified as the
central weakness. ArenaBufferPool uses `Arena.ofShared()` (native memory),
so all pool-managed buffers produce direct ByteBuffers.

## Rationale

### Why Per-Record + MemorySegment-Native

- **Resources**: True zero-copy compression via direct ByteBuffer path.
  MemorySegment throughout the data path — no byte[] at any level.
- **Accuracy**: CRC32 over uncompressed payload catches storage corruption
  AND decompression bugs. Self-contained records — crash at any point is safe.
- **Operational**: Mixed compressed/uncompressed records via flags byte.
  Both local and remote WAL implementations supported without modification
  to their write patterns.
- **Fit**: Reuses CompressionCodec identity (codecId, thread-safety contract).
  Default methods preserve backward compatibility. Full caller migration
  delivers consistent MemorySegment API across the library.

### Why not Streaming Compression (RocksDB-style)

- **Hard disqualifier**: Incompatible with RemoteWriteAheadLog (one-file-per-
  record pattern breaks streaming context)
- **Hard disqualifier**: Requires ZSTD (JNI dependency or infeasible hand-roll)
- Better ratio (2-3x) but at the cost of recovery complexity and remote
  WAL incompatibility

### Why not Dual API Bridge (WAL-only migration)

- Same mechanism (default methods) but defers SSTable caller migration
- Only 1 point behind in scoring — rejected because the user explicitly
  chose to eat the refactoring cost for API consistency

### Why not No API Change

- Simplest implementation but contradicts the MemorySegment-first mandate
- Would leave byte[] copies in the hot path permanently

## Implementation Guidance

### CompressionCodec changes

Add MemorySegment methods as default methods:
```java
default MemorySegment compress(MemorySegment src, MemorySegment dst) {
    // Default bridge: src.toArray → compress(byte[]) → dst.copyFrom
    // Codecs override for zero-copy
}
```

DeflateCodec override using ByteBuffer path:
```java
@Override
public MemorySegment compress(MemorySegment src, MemorySegment dst) {
    Deflater def = new Deflater(level);
    try {
        def.setInput(src.asByteBuffer());
        def.finish();
        ByteBuffer out = dst.asByteBuffer();
        def.deflate(out, Deflater.SYNC_FLUSH);
        return dst.asSlice(0, out.position());
    } finally {
        def.end();
    }
}
```

### WalRecord changes

- Add flags byte at position 4 (after frame length)
- When flags bit 0 is set: next 5 bytes are codec ID + uncompressed size
- CRC32 is computed over the uncompressed payload and stored at the end
- `WalRecord.encode()` gains an optional `CompressionCodec` parameter
- `WalRecord.decode()` gains a `Map<Byte, CompressionCodec>` parameter

### WAL builder changes

Both `LocalWriteAheadLog.Builder` and `RemoteWriteAheadLog.Builder` gain:
```java
.compression(CompressionCodec codec)          // enable compression
.compressionMinSize(int minBytes)             // threshold (default 64)
```

Compression is **off by default** — no behavioral change for existing users.

### SSTable migration

- `TrieSSTableWriter`: migrate `codec.compress(blockBytes, 0, blockBytes.length)`
  to `codec.compress(blockSegment, tmpSegment)`
- `TrieSSTableReader`: migrate both `codec.decompress(...)` calls to
  MemorySegment overloads

## What This Decision Does NOT Solve

- **Dictionary-based cross-record compression** — streaming compression
  achieves better ratios (2-3x) by building a dictionary across records.
  Per-record compression cannot do this. Deferred as `codec-dictionary-support`.
- **Pure-Java LZ4 codec** — a MemorySegment-native LZ4 implementation (~200
  lines) would provide faster compression than Deflate for typical WAL
  workloads. Future work, not blocking.
- **WAL entry encryption** — separate concern, can layer on top of compressed
  records. Deferred as `wal-entry-encryption`.
- **Group commit optimization** — batching multiple writes into a single fsync
  is orthogonal to compression. Future optimization.

## Conditions for Revision

This ADR should be re-evaluated if:
- **Compression ratio is insufficient** — if per-record 1.5-2x is inadequate
  and streaming's 2-3x is needed, revisit the remote WAL compatibility
  constraint
- **A pure-Java LZ4 is added** — the codec API is ready, just needs the
  implementation. This would likely become the default WAL codec over Deflate
  (faster compression, comparable ratio on small records)
- **MemorySegment.asByteBuffer() behavior changes** — the zero-copy path
  depends on Arena-allocated segments producing direct ByteBuffers

---
*Confirmed by: user deliberation | Date: 2026-04-12*
*Full scoring: [evaluation.md](evaluation.md)*

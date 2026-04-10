---
problem: "per-block-checksums"
date: "2026-04-10"
version: 1
status: "accepted"
depends_on: []
---

# Per-Block Checksums

## Problem
No integrity verification exists at the SSTable data block level. If a block
is corrupted on disk, in transit (S3 bit-rot), or by a partial write, the only
signals are a decompression failure (for compressed blocks) or silently wrong
data (for uncompressed blocks). Silent corruption is the worst case — it
violates the correctness guarantee without any error.

## Decision
**Add a CRC32C checksum per block in the `CompressionMap`.**

### Format change
`CompressionMap.Entry` gains a 4-byte `int checksum` field:

```
blockOffset      — 8 bytes (long)
compressedSize   — 4 bytes (int)
uncompressedSize — 4 bytes (int)
codecId          — 1 byte
checksum         — 4 bytes (int, CRC32C)
```

Entry size: 17 → 21 bytes.

### Algorithm
**CRC32C** via `java.util.zip.CRC32C`:
- Hardware-accelerated on x86 (SSE 4.2) and AArch64 — near-zero overhead
- 4 bytes, same as RocksDB/Cassandra block checksums
- Available in JDK without external dependencies

### Write path
After compressing (or deciding to store raw), compute CRC32C over the bytes
that will be written to disk (the compressed or raw block bytes). Store the
checksum in the `CompressionMap.Entry`.

### Read path
After reading a block from disk, compute CRC32C over the raw bytes read and
compare against the stored checksum. On mismatch, throw
`CorruptBlockException extends IOException` with the block index, expected
checksum, and actual checksum.

### Version compatibility
This changes the `CompressionMap` binary format. The SSTable footer version
field (v2 → v3) signals the presence of checksums. v2 readers encountering
v3 files will fail at the footer; v3 readers encountering v2 files will skip
checksum verification (no checksum stored).

## Rationale
- CRC32C is the industry standard for storage block integrity (RocksDB, Cassandra,
  LevelDB all use it)
- Hardware acceleration makes it effectively free — sub-microsecond per 4KB block
- No external dependency — `java.util.zip.CRC32C` is in the JDK
- 4 bytes per block is negligible overhead (21 vs 17 bytes per entry in the map)

## Key Assumptions
- Block corruption is rare but must be detected, never silently propagated
- CRC32C hardware acceleration is available on all target platforms (x86 SSE 4.2,
  AArch64)

## Conditions for Revision
- If a stronger integrity guarantee is needed (e.g., malicious tampering detection),
  a cryptographic hash would be required — but that's a security concern, not an
  integrity concern

## Implementation Guidance
1. Add `int checksum` to `CompressionMap.Entry` record
2. Update `CompressionMap.serialize()` / `deserialize()` for the new 21-byte entry format
3. Add `CorruptBlockException extends IOException`
4. Compute CRC32C in `TrieSSTableWriter.flushCurrentBlock()` after compression
5. Verify CRC32C in `TrieSSTableReader` block read path
6. Bump SSTable footer version to v3
7. Handle v2 files gracefully (skip verification)

## What This Decision Does NOT Solve
- End-to-end integrity across the full SSTable (footer checksum, index checksum)
- Repair or recovery from detected corruption — that's a higher-level concern

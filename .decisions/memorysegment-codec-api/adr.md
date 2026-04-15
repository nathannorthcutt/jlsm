---
problem: "memorysegment-codec-api"
date: "2026-04-14"
version: 2
status: "closed"
---

# Full MemorySegment-Based Codec API — Closed

## Problem
The `CompressionCodec` interface uses `byte[]` for compress/decompress. A full
`MemorySegment`-based API would enable zero-copy compression of off-heap data.

## Decision
**Will not pursue.** This topic is explicitly ruled out — already resolved.

## Reason
Subsumed by the `wal-compression` ADR (confirmed 2026-04-12). That decision
added `MemorySegment compress/decompress` as default methods on `CompressionCodec`,
implemented zero-copy via `MemorySegment.asByteBuffer()` → direct ByteBuffer →
native zlib, and defined the migration path for SSTable writer/reader callers.

The full MemorySegment codec API that this decision describes is exactly what
wal-compression delivered.

## Context
- Parent: `max-compressed-length` ADR
- Resolved by: `wal-compression` ADR (CompressionCodec API Evolution section)
- See: `.decisions/wal-compression/adr.md` §CompressionCodec API Evolution

## Conditions for Reopening
None — the API evolution is complete. Future codecs (LZ4, ZSTD via Panama FFM)
implement the MemorySegment methods directly.

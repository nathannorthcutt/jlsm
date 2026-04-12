---
problem: "pure-java-lz4-codec"
date: "2026-04-12"
version: 1
status: "deferred"
---

# Pure-Java LZ4 Codec — Deferred

## Problem
A pure-Java LZ4 implementation operating directly on MemorySegment for zero-copy
compression. LZ4 is ~15x faster than Deflate at comparable ratios on small
records, making it better suited as the default WAL codec.

## Why Deferred
Scoped out during `wal-compression` decision. Deflate via direct ByteBuffer is
sufficient for initial implementation. LZ4 is an optimization, not a requirement.

## Resume When
When WAL compression is implemented and benchmarks show Deflate latency is
a measurable bottleneck, or when a pure-Java LZ4 implementation is needed
for SSTable compression performance.

## What Is Known So Far
See `.decisions/wal-compression/adr.md` for the MemorySegment codec API.
See `.kb/algorithms/compression/block-compression-algorithms.md` — LZ4 is
implementable in ~200 lines of pure Java. ~780 MB/s compress, ~4970 MB/s
decompress. Ratio ~2:1.

## Next Step
Run `/architect "pure-java-lz4-codec"` when ready to evaluate.

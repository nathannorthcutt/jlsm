---
problem: "pure-java-lz4-codec"
date: "2026-04-14"
version: 2
status: "deferred"
depends_on: ["wal-compression"]
---

# Pure-Java LZ4 Codec — Re-Deferred

## Problem
A pure-Java LZ4 implementation operating directly on MemorySegment for zero-copy
compression. LZ4 is ~15x faster than Deflate, making it better suited as the
default WAL and SSTable codec.

## Why Deferred
Performance-gated. WAL compression is specified (wal-compression ADR) but no
benchmarks exist yet to demonstrate that Deflate latency is a measurable
bottleneck. LZ4 is an optimization, not a requirement. Pure-Java LZ4 achieves
30-50% of native throughput — adequate for most workloads, but the need hasn't
been demonstrated.

## Resume When
When WAL compression is implemented and JMH benchmarks show Deflate compression
latency is a measurable bottleneck in the write path (not just theoretically
slower).

## What Is Known So Far
- KB: `.kb/algorithms/compression/pure-java-compression-codecs.md` — feasible in
  ~200 lines, 200-400 MB/s compress, 800-1500 MB/s decompress
- KB: `.kb/algorithms/compression/block-compression-algorithms.md` — LZ4 survey
- The MemorySegment codec API is ready (wal-compression ADR) — LZ4 would
  implement the `compress(MemorySegment, MemorySegment)` method directly
- Multiple reference implementations exist: Apache Kafka, Lucene, aircompressor

## Next Step
Run `/architect "pure-java-lz4-codec"` when benchmarks demonstrate the need.

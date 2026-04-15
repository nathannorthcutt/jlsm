---
problem: "pure-java-zstd-compressor"
date: "2026-04-14"
version: 2
status: "deferred"
depends_on: ["codec-dictionary-support"]
---

# Pure-Java ZSTD Compressor — Re-Deferred

## Problem
A pure-Java ZSTD compressor would eliminate the need for native libzstd on the
write path. Currently only decompression is feasible in pure Java; compression
requires the native library via Panama FFM.

## Why Deferred
The codec-dictionary-support ADR (confirmed 2026-04-12) handles this with tiered
Panama FFM detection: native libzstd at near-native speed via Panama FFM downcalls,
with graceful degradation when the native library is absent. A pure-Java compressor
(~3000+ lines, 30-40% slower than native) is unlikely to be needed unless the
Panama FFM path is also unavailable.

## Resume When
When the native library dependency on the write path AND Panama FFM downcalls are
both unacceptable for a target deployment, or when a pure-Java ZSTD compressor
implementation becomes available that performs within 2x of native.

## What Is Known So Far
- KB: `.kb/algorithms/compression/pure-java-compression-codecs.md` — aircompressor
  has pure-Java ZSTD (DoubleFast strategy only, 30-40% slower)
- The tiered detection pattern in codec-dictionary-support handles the native
  dependency gracefully: Panama FFM → pure-Java decompressor fallback
- No dictionary training in pure Java — dictionary features require native libzstd
- Full ZSTD compressor is ~5000+ lines of complex algorithm code (FSE, Huffman,
  match finder)

## Next Step
Run `/architect "pure-java-zstd-compressor"` if a zero-native-dependency
deployment requirement emerges.

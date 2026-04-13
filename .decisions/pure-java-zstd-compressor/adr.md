---
problem: "pure-java-zstd-compressor"
date: "2026-04-12"
version: 1
status: "deferred"
depends_on: ["codec-dictionary-support"]
---

# Pure-Java ZSTD Compressor — Deferred

## Problem
A pure-Java ZSTD compressor would eliminate the need for native libzstd on the write path. Currently only decompression is feasible in pure Java (~1500-2200 lines); compression requires the native library via Panama FFM.

## Why Deferred
Scoped out during `codec-dictionary-support` decision. A full ZSTD compressor in pure Java is ~3000+ lines (aircompressor only implements DoubleFast strategy, 30-40% slower than native). The tiered detection pattern handles the native dependency gracefully.

## Resume When
When the native library dependency on the write path is unacceptable for deployment, or when a pure-Java ZSTD compressor implementation becomes available that performs within 2x of native.

## What Is Known So Far
Aircompressor (airlift) has a pure-Java ZSTD compressor but only DoubleFast strategy (low compression levels). 30-40% slower than native zstd-jni. No dictionary training in pure Java. See `.kb/algorithms/compression/block-compression-algorithms.md`.

## Next Step
Run `/architect "pure-java-zstd-compressor"` when ready to evaluate.

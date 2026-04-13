---
problem: "How should the CompressionCodec API and SSTable writer support dictionary compression (ZSTD dictionaries, tiered native/pure-Java, per-SST lifecycle)?"
slug: "codec-dictionary-support"
captured: "2026-04-12"
status: "draft"
---

# Constraint Profile — codec-dictionary-support

## Problem Statement
Dictionary compression dramatically improves ratios on small blocks (2-2.5x on
structured data) by pre-seeding the compressor with patterns learned from
representative data. Supporting this requires: (1) a dictionary lifecycle in the
SSTable writer (buffer blocks → train → compress → store dictionary as meta-block),
(2) tiered native/pure-Java codec integration via Panama FFM with fallback, and
(3) API surface that fits the existing CompressionCodec contract without breaking
current consumers.

## Constraints

### Scale
SSTable blocks 4-64 KB. Dictionary benefit strongest on smaller blocks with
structured, repetitive data (JSON documents, protobuf records, log entries).
Per-SST dictionary means one dictionary per file — not shared across files.

### Resources
Pure Java library, no mandatory external dependencies. Native libraries (libzstd)
optional via Panama FFM at runtime. Must function without native lib present —
pure-Java decompressor provides read-path coverage.

### Complexity Budget
Unlimited. Team is comfortable with Panama FFM (already used for JSON SIMD
tier detection), ZSTD internals, and multi-tier fallback patterns.

### Accuracy / Correctness
Cross-platform readability is a hard requirement: SSTables written with native
ZSTD + dictionary must be readable by a pure-Java decompressor without the
native library. The on-disk format must be self-describing — dictionary bytes
stored in the SST file alongside data blocks.

### Operational Requirements
Write-path latency: dictionary training and block buffering happen during
compaction (background), so latency is not critical. Read-path latency:
decompression with pre-loaded DDict must not add measurable overhead beyond
plain decompression. Dictionary loading is amortized over the file's lifetime.

### Fit
Must extend the existing `CompressionCodec` interface (open, non-sealed) without
breaking current consumers. JPMS modular. The existing codec contract is: stateless,
thread-safe, constructor-time configuration only. Dictionary training is inherently
stateful — this tension is the core design question.

## Key Constraints (most narrowing)
1. **Stateless codec vs stateful training** — the current CompressionCodec contract
   is stateless and thread-safe. Dictionary training requires accumulating samples.
   This tension determines where the lifecycle lives.
2. **Cross-platform readability** — write with native ZSTD, read without native lib.
   The pure-Java decompressor must handle dictionary-compressed frames.
3. **No mandatory dependencies** — native libzstd must be optional. The library must
   compile, test, and run without it.

## Unknown / Not Specified
None — full profile captured.

---
problem: "VectorType serialization encoding — fixed-length vector field encoding in DocumentSerializer"
slug: "vector-type-serialization-encoding"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — vector-type-serialization-encoding

## Problem Statement
Design the binary encoding for the new `FieldType.VectorType` in `DocumentSerializer`. Vectors have
fixed dimensions (known from schema) and element type FLOAT16 or FLOAT32. The encoding must support
efficient deserialization from local and remote (S3/GCS) backends, minimize memory footprint, and
be lossless.

## Constraints

### Scale
Dimensions range from 32 to 4096. Document counts up to billions. At the high end a single
4096-dim FLOAT32 vector is 16 KiB — at billion-document scale, raw vector data alone is in the
petabyte range, so encoding overhead per vector matters.

### Resources
Memory is the most cost-prohibitive resource in cloud environments. Off-heap (`MemorySegment` /
`ArenaBufferPool`) is preferred for hot paths. Minimize transient allocations during
deserialization. Remote backends (S3, GCS) mean round-trip reduction is critical — avoid
encoding patterns that require multiple seeks or reads for a single vector.

### Complexity Budget
No constraints — complexity is acceptable since this is a critical data path. Sophisticated
encoding is fine if it pays for itself in the resource and operational dimensions.

### Accuracy / Correctness
Encoding must be lossless. FLOAT16 values must round-trip exactly (bit-identical). No
approximation or quantization at the serialization layer.

### Operational Requirements
Deserialization latency is higher priority than serialization — ranking algorithms read vectors
far more often than they are written. Serialization can be moderately slower if it enables
faster reads. Background rebuilds are not latency-sensitive.

### Fit
- Java 25, Panama FFM API (`MemorySegment`, `Arena`, `ValueLayout`)
- Existing `DocumentSerializer` uses `MemorySegment` bulk operations
- `ArenaBufferPool` for off-heap allocation in hot paths
- Remote NIO backends via `SeekableByteChannel` — no `mmap` on remote paths
- FLOAT16 encoding already exists in jlsm-core (half-precision ↔ float conversion)

## Key Constraints (most narrowing)
1. **Lossless + FLOAT16 support** — eliminates any lossy compression or quantization scheme
2. **Deserialization speed over serialization** — favours flat, aligned, zero-copy-friendly layouts
3. **Remote I/O with minimal round-trips** — favours contiguous, self-contained encoding (no external
   lookups or multi-block scatter)

## Unknown / Not Specified
None — full profile captured.

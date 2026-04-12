---
problem: "memorysegment-codec-api"
date: "2026-04-11"
version: 1
status: "deferred"
---

# ADR: Full MemorySegment-Based Codec API

**Status:** deferred
**Source:** out-of-scope from `max-compressed-length`

## Problem

The `CompressionCodec` interface uses `byte[]` for compress/decompress. A full
`MemorySegment`-based API would enable zero-copy compression of off-heap data
and eliminate array copies in the SSTable write/read hot path.

## Why Deferred

Scoped out during `max-compressed-length` decision. This is a larger API
evolution that affects all codec implementations and callers. The current
`byte[]` API is sufficient and well-tested.

## Resume When

When profiling shows that array copies between `MemorySegment` and `byte[]`
are a measurable bottleneck in the SSTable pipeline, or when adding a codec
that natively operates on `MemorySegment` (e.g., a Panama-based LZ4 binding).

## What Is Known So Far

See `.decisions/max-compressed-length/adr.md` and
`.decisions/compression-codec-api-design/adr.md` for the current codec contract.
See `.decisions/codec-thread-safety/adr.md` for the thread-safety requirements
any new API must preserve.

## Next Step

Run `/architect "memorysegment-codec-api"` when ready to evaluate.

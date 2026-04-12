---
problem: "max-compressed-length"
date: "2026-04-10"
version: 1
status: "accepted"
depends_on: []
---

# Max Compressed Length Pre-Allocation

## Problem
The current `CompressionCodec` API returns `byte[]` from `compress()`, meaning
the codec handles buffer allocation internally. There is no way for callers to
pre-allocate an output buffer of the correct size. This blocks future evolution
toward a zero-copy `MemorySegment`-based API where callers provide the output
buffer.

## Decision
**Add `int maxCompressedLength(int inputLength)` to `CompressionCodec`.**

Returns the worst-case compressed size for a given input length. Callers can
use this to pre-allocate output buffers. This is the first step toward a
buffer-oriented codec API that supports `MemorySegment` zero-copy patterns.

### Contract
- `maxCompressedLength(n)` MUST return a value `>= n` (compression can expand)
- `maxCompressedLength(n)` MUST be a pure function (deterministic, no side effects)
- The returned value MUST be tight enough to avoid excessive waste but MUST
  never underestimate — a too-small buffer causes corruption or exceptions

### Per-codec values
- **NoneCodec:** returns `inputLength` (no expansion possible)
- **DeflateCodec:** returns `inputLength + ((inputLength + 7) >> 3) + ((inputLength + 63) >> 6) + 11`
  (zlib `deflateBound` formula — accounts for block headers and stream overhead)

### Default method
Provide a conservative default implementation on the interface so that existing
custom codecs don't break:

```java
default int maxCompressedLength(int inputLength) {
    // Conservative: assume worst case is 2x input + header overhead
    return inputLength + inputLength + 64;
}
```

Custom codecs should override with tighter bounds.

## Rationale
- Enables future `MemorySegment`-based compress/decompress signatures where the
  caller provides a pre-sized output segment — essential for zero-copy I/O paths.
- Even with the current `byte[]` API, `DeflateCodec` could pre-size its internal
  `ByteArrayOutputStream` to avoid resizing during compression.
- Every major compression library (zlib, lz4, zstd) exposes an equivalent
  `compressBound` function — this is a standard pattern.

## Key Assumptions
- The worst-case bound is computable at codec construction time or is a pure
  function of input length (no data-dependent worst case).

## Conditions for Revision
- If a codec's worst-case bound depends on the data content (not just length),
  the API would need a different shape.

## Implementation Guidance
1. Add `default int maxCompressedLength(int inputLength)` to `CompressionCodec`
2. Override in `NoneCodec` (return `inputLength`)
3. Override in `DeflateCodec` (zlib `deflateBound` formula)
4. Add tests verifying the bound holds for various input sizes

## What This Decision Does NOT Solve
- Full `MemorySegment`-based codec API — that's a larger API evolution
- `codec-dictionary-support` — dictionary pre-loading is orthogonal

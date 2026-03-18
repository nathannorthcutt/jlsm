---
problem: "Compression codec API design — interface shape, registration, and tree builder integration"
slug: "compression-codec-api-design"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — compression-codec-api-design

## Problem Statement
What should the CompressionCodec interface look like, how does the reader resolve codec IDs
from the compression map back to implementations, and how does the tree builder expose
compression configuration to flow it through writer/reader factories?

## Constraints

### Scale
Codec is invoked once per block write and once per block read. Must not allocate per-invocation
beyond the output buffer. Number of distinct codecs is small (2–5).

### Resources
Pure Java 25, no external runtime deps. Codec implementations must work with `byte[]` since
`java.util.zip.Deflater`/`Inflater` operate on byte arrays. Future codecs (pure-Java LZ4)
will also operate on byte arrays.

### Complexity Budget
Performance > complexity. User explicitly deprioritized simplicity. However, the codec
interface itself should be minimal — complexity belongs in implementations, not the contract.

### Accuracy / Correctness
Codec ID stored in compression map must reliably resolve to the correct decompressor at read
time. Unknown codec IDs must produce a clear `IOException`, not silent corruption. Round-trip
correctness: `decompress(compress(data)) == data` for all codecs.

### Operational Requirements
- Writer: compression is on the flush path (not latency-critical, but throughput matters)
- Reader: decompression is on the hot read path (latency-critical)
- Codec instances should be reusable across blocks (no per-block allocation of Deflater/Inflater)
- Block cache stores decompressed data, so codec is below the cache layer

### Fit
- Existing API: `SSTableWriterFactory` and `SSTableReaderFactory` are `@FunctionalInterface`
- Builder pattern: `StandardLsmTree.Builder` is fluent; `TypedStandardLsmTree` delegates to it
- `TrieSSTableWriter` constructor: `(id, level, path)` or `(id, level, path, bloomFactory)`
- `TrieSSTableReader` factory: `open(path, bloomDeserializer, blockCache?)`
- Library philosophy: composable, consumers wire components together

## Key Constraints (most narrowing)
1. **Extensibility** — consumers may want to plug in JNI-backed codecs (ZSTD, hardware-accelerated)
   for performance; the interface must not be sealed to library-provided implementations only
2. **Reader codec resolution** — the reader must resolve codec IDs from the compression map
   without depending on global mutable state; codec availability must be explicit
3. **Builder integration** — compression config must flow cleanly through the existing factory
   pattern without breaking the `@FunctionalInterface` contract

## Unknown / Not Specified
None — full profile captured.

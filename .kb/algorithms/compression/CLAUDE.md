# Compression — Category Index
*Topic: algorithms*

Lossless compression algorithms used for SSTable data blocks and storage engine I/O reduction.
Covers speed/ratio tradeoffs, per-block encoding formats, and pure-Java implementation feasibility.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [block-compression-algorithms.md](block-compression-algorithms.md) | Block Compression Algorithms (LZ4, Deflate, Snappy, ZSTD) | mature | LZ4: 780 MB/s compress, 4970 MB/s decompress | SSTable block-level compression |
| [bounds-check-overflow.md](bounds-check-overflow.md) | Integer overflow in offset+length bounds check (adversarial) | active | data-integrity bug class | Any method accepting (input, offset, length) |
| [negative-size-unvalidated.md](negative-size-unvalidated.md) | Negative size parameter unvalidated (adversarial) | active | data-integrity bug class | Any method with output size parameter |
| [block-compression.md](block-compression.md) | block-compression (feature footprint) | stable | feature audit record | SSTable block compression overview |
| [streaming-block-decompression.md](streaming-block-decompression.md) | streaming-block-decompression (feature footprint) | stable | feature audit record | Lazy scan decompression overview |
| [integer-overflow-in-size-calc.md](integer-overflow-in-size-calc.md) | Integer overflow in header+count*entrySize (adversarial) | active | data-integrity bug class | Any serialize/deserialize with count*size calc |
| [lazy-channel-concurrent-read.md](lazy-channel-concurrent-read.md) | Lazy channel position-then-read race (adversarial) | active | concurrency bug class | Any lazy reader sharing SeekableByteChannel |
| [footer-field-validation.md](footer-field-validation.md) | Footer field validation missing (adversarial) | active | data-integrity bug class | Any binary footer/header parser reading from disk |

## Comparison Summary

LZ4 and Deflate are the two viable options for a pure-Java library with no external dependencies.
LZ4 is ~15x faster but achieves lower ratio (~2:1 vs ~3:1). Deflate is available via `java.util.zip`
(zero implementation effort). LZ4 is implementable in ~200 lines of pure Java. Snappy is strictly
dominated by LZ4. ZSTD is too complex for hand-rolling (~5000+ lines).

## Recommended Reading Order
1. Start: [block-compression-algorithms.md](block-compression-algorithms.md) — survey of all four algorithms

## Research Gaps
- Pure-Java LZ4 implementation performance benchmarks (JMH on jlsm workloads)
- Optimal block size for SSTable compression (4 KiB vs 8 KiB vs 16 KiB)

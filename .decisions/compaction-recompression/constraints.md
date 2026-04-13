---
problem: "How should the compactor support writing output SSTables with a different compression codec than the source SSTables?"
slug: "compaction-recompression"
captured: "2026-04-12"
status: "draft"
---

# Constraint Profile — compaction-recompression

## Problem Statement
Allow background compaction to re-compress output SSTables with a different codec
than the source SSTables. This enables upgrading compression strategy (e.g., from
Deflate to ZSTD, or from plain ZSTD to ZSTD+dictionary) during compaction without
rewriting the entire database.

## Constraints

### Scale
Compaction merges multiple SSTables. Re-compression is per-block on the streaming
output. Block count can be large but processing is already streaming — no additional
memory proportional to input size.

### Resources
Pure Java, no mandatory external dependencies. Same as codec-dictionary-support.

### Complexity Budget
Unlimited.

### Accuracy / Correctness
Source data must be preserved exactly through re-compression. The codec changes
the encoding, not the data. Decompressed output must be byte-identical to
decompressed input.

### Operational Requirements
Compaction is background work — throughput matters more than latency. Re-compression
adds CPU cost proportional to output size (decompress with source codec, re-compress
with target codec). This is acceptable for background work.

### Fit
Must integrate with existing SpookyCompactor (currently creates writers without
codec), TrieSSTableWriter (already accepts codec), and tree builder. Must compose
with codec-dictionary-support (dictionary training during compaction). F02.R38
currently mandates "same codec as tree" — will need amendment.

## Key Constraints (most narrowing)
1. **Composability with dictionary support** — the primary use case for re-compression
   is enabling ZSTD+dictionary on cold levels during compaction. This must work with
   the codec-dictionary-support writer-orchestrated design.
2. **Streaming processing** — re-compression must work within the compactor's existing
   streaming merge pattern (read entries, write entries). Cannot require buffering
   all input.
3. **Backward compatibility** — readers already handle any codec via the compression
   map. No reader changes needed.

## Unknown / Not Specified
None — full profile captured.

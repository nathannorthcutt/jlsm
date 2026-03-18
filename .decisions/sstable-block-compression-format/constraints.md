---
problem: "SSTable block compression format — how to encode per-block compression metadata in the SSTable on-disk format"
slug: "sstable-block-compression-format"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — sstable-block-compression-format

## Problem Statement
How should per-block compression metadata be encoded in the SSTable on-disk format
to support mixed compressed/uncompressed blocks, backward compatibility with existing
uncompressed v1 SSTables, and efficient I/O on both local and remote (S3/GCS) backends?

## Constraints

### Scale
SSTable files contain hundreds to thousands of data blocks. Block size is configurable
(4 KiB default, but larger blocks or multi-block reads are desirable for remote backends).
Files may be read locally (fast random access) or over network-backed filesystems (high
latency per I/O, prefer large sequential reads).

### Resources
Pure Java 25, no external runtime dependencies, JPMS modules. Existing `ArenaBufferPool`
for off-heap I/O. `SeekableByteChannel`-based I/O for remote compatibility.

### Complexity Budget
Performance matters more than format simplicity. Codebase complexity is acceptable if it
enables better I/O patterns. Diagnostic tooling can be built separately. Format does not
need to be hex-editor friendly.

### Accuracy / Correctness
Lossless compression. Format must be self-describing: readers determine codec from
block/file metadata, not from tree configuration. Mixed compressed/uncompressed blocks
within a single file must work. Existing v1 SSTables (magic `JLSMSST\x01`) must remain
readable by v2 readers.

### Operational Requirements
Decompression is on the read hot path — format overhead must not dominate. For remote
backends, sequential scans should be able to prefetch multiple blocks in a single I/O
operation. Lazy reader must decompress on-demand. Block cache stores decompressed data.

### Fit
Current format: 48-byte footer with magic, key index with absolute entry file offsets,
4-byte count block headers. Key index format change is accepted — expected consequence
of adding compression.

## Key Constraints (most narrowing)
1. **Remote-backend multi-block prefetch** — format must enable efficient batch reads of
   multiple compressed blocks without parsing blocks sequentially to discover boundaries
2. **Self-describing per-block compression** — readers must identify codec + sizes from
   the file format alone, enabling mixed compressed/uncompressed interop
3. **Backward compatibility** — v2 readers must handle v1 (uncompressed) SSTables

## Unknown / Not Specified
None — full profile captured.

---
problem: "WAL compression to reduce write amplification and storage cost"
slug: "wal-compression"
captured: "2026-04-12"
status: "draft"
---

# Constraint Profile — wal-compression

## Problem Statement
Compress WAL records to reduce storage cost and write amplification. Must work
with both LocalWriteAheadLog (mmap'd segments) and RemoteWriteAheadLog
(one-file-per-record). Must preserve CRC32 integrity verification and recovery
correctness.

## Constraints

### Scale
Individual WAL records range from ~100 bytes (small deletes) to ~100 KB (large
value payloads). Write throughput tracks the LSM tree's write rate — up to
thousands of records per second at peak. Total WAL storage is bounded by segment
count and retention policy.

### Resources
Zero additional heap allocation on the write path beyond ArenaBufferPool.
All buffer management must go through the existing pool. MemorySegment-based
I/O throughout — avoid byte[] in the data path. The existing CompressionCodec
interface uses byte[], creating a design tension (see Fit).

### Complexity Budget
Reuse existing CompressionCodec infrastructure where possible. Compression must
be optional (off by default) and configurable via builder. The WAL format change
must be backward-compatible: readers handle both compressed and uncompressed
records in the same log without external metadata.

### Accuracy / Correctness
Lossless compression only. CRC32 integrity must be preserved — the checksum
covers the uncompressed payload so corruption is detected regardless of
compression. Records below a configurable minimum size should skip compression
(overhead exceeds savings for tiny records). Recovery must tolerate partial
writes of compressed records (same crash-safety guarantees as uncompressed).

### Operational Requirements
Compression must not increase write-path latency by more than ~10% at p99.
Recovery (replay) must handle mixed compressed/uncompressed records. Format
must be self-describing per-record (no external compression map — unlike
SSTable blocks, WAL records are read individually during replay).

### Fit
Java 25, JPMS. Both Local and Remote WAL implementations must support
compression. WalRecord binary format is the integration point. ArenaBufferPool
for buffer management.

**Critical tension:** The project direction is MemorySegment-first for all
binary formats. WalRecord already uses MemorySegment. But CompressionCodec
uses byte[]. Options:
1. Accept byte[] round-trip for now (pragmatic but inconsistent)
2. Evolve codec API to MemorySegment as part of this work (broader scope)
3. WAL-specific compression path separate from CompressionCodec

This tension is the core design question for this decision.

## Key Constraints (most narrowing)
1. **MemorySegment-first** — binary format work must use MemorySegment, not byte[];
   this conflicts with the existing byte[]-based CompressionCodec interface
2. **Per-record self-describing** — each record must indicate its compression
   state without external metadata (unlike SSTable's compression map)
3. **Write-path latency** — compression is on every write; must be negligible

## Unknown / Not Specified
None — full profile captured. The codec API tension (byte[] vs MemorySegment)
is a known open question, not an unknown.

## Constraint Falsification — 2026-04-12

Checked: .decisions/compression-codec-api-design/adr.md,
.decisions/codec-thread-safety/adr.md, .decisions/memorysegment-codec-api/adr.md,
.decisions/sstable-block-compression-format/adr.md

Implied constraints found:
- **Fit:** codec-thread-safety ADR mandates stateless, thread-safe codecs. Any
  WAL compression codec must satisfy this contract.
- **Fit:** memorysegment-codec-api is deferred — the MemorySegment evolution is
  acknowledged but not yet designed. WAL compression should not block on it but
  should be designed to benefit from it when it arrives.
- **Accuracy:** sstable-block-compression-format stores uncompressed size in
  the compression map. WAL must store uncompressed size per-record for the same
  reason (decompression needs a pre-allocated output buffer).

All implied constraints added to profile above.

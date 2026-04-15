---
problem: "End-to-end SSTable integrity across all sections"
slug: "sstable-end-to-end-integrity"
captured: "2026-04-14"
status: "final"
---

# Constraint Profile — sstable-end-to-end-integrity

## Problem Statement
Per-block CRC32C checksums (v3) cover data blocks but not metadata sections
(footer, index, bloom filter, compression map). A corrupt metadata section is
undetected. More critically, the data section has no inline structure — if the
compression map is lost, block boundaries are unknown and all data is
unreachable even if the blocks themselves are intact.

## Constraints

### Scale
SSTable files range from small (few MiB, local flush) to large (multi-GiB,
compaction output). Block sizes from 4 KiB to 32 MiB. Remote backends (S3/GCS)
have atomic object writes; local filesystems have partial-write risk.

### Resources
Pure Java 25. CRC32C via java.util.zip.CRC32C (hardware-accelerated). VarInt
encoding is trivial to implement. No external dependencies.

### Complexity Budget
Must not require a full file format redesign. Extend existing v4 format. The
writer and reader already handle version-specific code paths.

### Accuracy / Correctness
Every persistence boundary must be verifiable. The data section must be
recoverable by sequential scan even without the compression map. No silent
corruption propagation.

### Operational Requirements
Detection at SSTable open time (eager). Recovery must not require external
tools — the reader should be able to self-heal from a corrupt compression map
by scanning VarInt-prefixed blocks.

### Fit
Extends per-block-checksums (v3) and codec-dictionary-support (v4). Same
CRC32C algorithm. VarInt encoding aligns with the project's constrained-memory
philosophy.

## Key Constraints (most narrowing)
1. **Data section recoverability** — block boundaries must be discoverable
   without the compression map
2. **Minimal per-block overhead** — VarInt encoding (2 bytes common case)
   vs fixed 4-byte prefix
3. **Process guarantee** — fsync discipline prevents the partial-write
   corruption scenario entirely on local filesystems

## Unknown / Not Specified
None — full profile captured.

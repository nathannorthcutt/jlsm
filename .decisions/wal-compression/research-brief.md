---
problem: "wal-compression"
requested: "2026-04-12"
status: "pending"
---

# Research Brief — wal-compression

## Context
The Architect is evaluating options for: WAL compression with MemorySegment-first
codec API evolution.

Binding constraints for this evaluation:
- MemorySegment-first: all binary format work must use MemorySegment, not byte[]
- Per-record self-describing format: no external compression map
- Write-path latency: compression on every write, must be negligible
- CompressionCodec API evolution: byte[] → MemorySegment as part of this work

## Subjects Needed

### WAL Compression Patterns in LSM Implementations
- Requested path: `.kb/algorithms/compression/wal-compression-patterns.md`
- Why needed: understand how production LSM-tree implementations (RocksDB,
  LevelDB, Cassandra, ScyllaDB) handle WAL compression — format design,
  per-record vs per-block, metadata encoding, recovery implications
- Sections most important for this decision:
  - Record-level vs block-level compression in WAL context
  - How compressed/uncompressed records coexist in the same log
  - Compression metadata overhead per record
  - Impact on write latency and recovery time
  - Whether WAL compression is per-record or batched

## Commands to run
/research "WAL compression patterns in LSM implementations" context: "architect decision: wal-compression"

---
title: "Compressed Block Storage"
slug: compressed-blocks
domain: data-management
status: active
type: core
tags: ["compression", "storage", "sstable", "blocks", "codecs", "wal", "memorysegment", "zero-copy"]
features:
  - slug: block-compression
    role: core
    description: "Pluggable compression codec API, per-block metadata format, writer/reader integration for SSTables"
  - slug: streaming-block-decompression
    role: quality
    description: "Lazy per-block decompression during scans — recovers read performance lost to eager decompression"
  - slug: sstable-v3-format-upgrade
    role: extends
    description: "v3 format: per-block CRC32C integrity checksums and configurable block size for remote-backend optimization"
  - slug: wal-compression-codec-api
    role: extends
    description: "MemorySegment-native codec API (zero-copy via direct ByteBuffer), per-record WAL compression on by default"
  - slug: zstd-dictionary-compression-per-level-codec-policy
    role: extends
    description: "ZSTD codec via Panama FFM with pure-Java fallback, adaptive per-SSTable dictionary training, per-level codec policy, SSTable v4 format"
composes: []
spec_refs: ["F02", "F08", "F16", "F17", "F18"]
decision_refs: ["sstable-block-compression-format", "compression-codec-api-design", "per-block-checksums", "backend-optimal-block-size", "wal-compression", "codec-thread-safety", "max-compressed-length", "codec-dictionary-support", "compaction-recompression"]
kb_refs: ["algorithms/compression"]
depends_on: ["data-management/schema-and-documents"]
enables: []
---

# Compressed Block Storage

Configurable per-tree block-level compression for SSTables. Each LSM tree
can use a different compression codec, and compressed and uncompressed
files interoperate transparently during reads and compaction.

## What it does

SSTable blocks are compressed individually using a pluggable codec API.
The compression offset map stores per-block metadata (compressed offset,
original size, codec ID) enabling random access without decompressing the
entire file. Scans decompress blocks lazily on access rather than eagerly
on open.

## Features

**Core:**
- **block-compression** — pluggable compression codec API, per-block metadata format, writer/reader integration

**Quality:**
- **streaming-block-decompression** — lazy per-block decompression during scans for read performance recovery

**Extends:**
- **sstable-v3-format-upgrade** — per-block CRC32C integrity checksums, configurable block size (Builder API), v3 footer with blockSize field
- **wal-compression-codec-api** — MemorySegment-native codec API (byte[] removed), zero-copy Deflate via direct ByteBuffer, per-record WAL compression on by default

## Key behaviors

- Compression is configured per-tree, not per-block — all blocks in an SSTable use the same codec
- The compression offset map enables random access to individual compressed blocks
- Uncompressed and compressed SSTables interoperate transparently during reads
- Codecs are pluggable via the CompressionCodec interface
- Decompression is lazy during scans — blocks are decompressed on access, not on file open
- Compaction preserves the source tree's compression setting

## Related

- **Specs:** F02 (block compression), F08 (streaming block decompression)
- **Decisions:** sstable-block-compression-format (offset map approach), compression-codec-api-design (pluggable codec interface)
- **KB:** algorithms/compression (zstd, lz4, snappy research)
- **Depends on:** data-management/schema-and-documents (serializer produces blocks)
- **Deferred work:** compaction-recompression, codec-dictionary-support, pure-java-lz4-codec, wal-group-commit, memorysegment-codec-api

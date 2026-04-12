# Serialization — Spec Index

> Shard index for the serialization domain.
> Split this file when it exceeds ~50 entries.

## Feature Registry

| ID | Title | Status | Amends | Decision Refs |
|----|-------|--------|--------|---------------|
| F02 | Block-Level SSTable Compression | ACTIVE | — | sstable-block-compression-format, compression-codec-api-design |
| F15 | JSON-Only SIMD On-Demand Parser with JSONL Streaming | ACTIVE | invalidates F14.R48, F14.R49 | — |
| F16 | SSTable v3 Format Upgrade | ACTIVE | — | per-block-checksums, backend-optimal-block-size, sstable-block-compression-format |
| F17 | WAL Compression with MemorySegment Codec API | ACTIVE | invalidates F02.R2-R4, R8-R10 | wal-compression, compression-codec-api-design, codec-thread-safety, max-compressed-length |

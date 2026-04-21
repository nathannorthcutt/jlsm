---
title: "block-compression"
type: feature-footprint
domains: ["compression", "sstable-format"]
constructs: ["CompressionCodec", "DeflateCodec", "NoneCodec", "CompressionMap", "SSTableFormat"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/compression/*"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/*"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/CompressionMap.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
research_status: stable
last_researched: "2026-03-26"
related:
  - "systems/database-engines/pool-aware-sstable-block-sizing.md"
---

# block-compression

## What it built

Per-block compression for SSTable data blocks via a pluggable `CompressionCodec`
interface. SSTable v2 format self-describes compression per block in a compression
map, enabling transparent interop between compressed and uncompressed SSTables.
Writer falls back to NoneCodec for blocks that don't compress well.

## Key constructs

- `CompressionCodec` — public interface with `compress`/`decompress` + static factories
- `DeflateCodec` — Deflater/Inflater per-call, level 0-9
- `NoneCodec` — passthrough singleton, codec ID 0x00
- `CompressionMap` — per-block metadata (offset, sizes, codecId), binary serializable
- `SSTableFormat` — v1/v2 magic numbers and footer sizes
- `TrieSSTableWriter` — v2 path: compress blocks, write compression map + v2 footer
- `TrieSSTableReader` — v2 path: detect format, decompress on read, auto-include NoneCodec

## Adversarial findings

- bounds-check-overflow: `offset + length` int overflow in codec methods → [KB entry](bounds-check-overflow.md)
- negative-size-unvalidated: negative `uncompressedLength` not validated → [KB entry](negative-size-unvalidated.md)
- record-result-missing-validation: `CompressionMap.Entry` lacked compact constructor → [KB entry](../../systems/lsm-index-patterns/record-result-missing-validation.md)
- integer-overflow-in-size-calc: `blockCount * ENTRY_SIZE` int overflow in CompressionMap → [KB entry](integer-overflow-in-size-calc.md)
- lazy-channel-concurrent-read: position-then-read race on lazy reader channel → [KB entry](lazy-channel-concurrent-read.md)
- footer-field-validation: corrupt footer fields cascade to uninformative exceptions → [KB entry](footer-field-validation.md)
- assert-only-public-validation: assert-only codec null guard in reader → [KB entry](../../systems/database-engines/assert-only-public-validation.md)

## Cross-references

- ADR: .decisions/compression-codec-api-design/adr.md
- ADR: .decisions/sstable-block-compression-format/adr.md
- KB: .kb/algorithms/compression/block-compression-algorithms.md
- Related features: streaming-block-decompression (extends this)

---
title: "streaming-block-decompression"
type: feature-footprint
domains: ["compression", "sstable-iteration"]
constructs: ["CompressedBlockIterator", "readAndDecompressBlockNoCache"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
research_status: stable
last_researched: "2026-03-25"
---

# streaming-block-decompression

## What it built

Replaced upfront `decompressAllBlocks()` in `TrieSSTableReader.scan()` with a lazy
`CompressedBlockIterator` that decompresses one block at a time. Added block caching
to `IndexRangeIterator` so consecutive entries in the same block reuse a single
decompressed buffer. Both iterators bypass the shared `BlockCache` to prevent scan
pollution.

## Key constructs

- `CompressedBlockIterator` — non-static inner class; iterates v2 compressed blocks
  lazily, decompressing one block at a time via `readAndDecompressBlockNoCache`
- `readAndDecompressBlockNoCache(int blockIndex)` — same as `readAndDecompressBlock`
  but bypasses BlockCache; used by scan iterators
- `IndexRangeIterator.cachedBlockIndex/cachedBlock` — iterator-local block cache
  for consecutive entries in the same block during range scans

## Adversarial findings

- iterator-use-after-close: iterators did not check reader closed state during
  advance → [KB entry](../../systems/lsm-index-patterns/iterator-use-after-close.md)
- READINT-DUPLICATION (tendency): three identical `readInt` copies across outer class
  and two inner iterator classes

## Cross-references

- ADR: .decisions/sstable-block-compression-format/adr.md
- ADR: .decisions/compression-codec-api-design/adr.md
- Related features: block-compression (prerequisite)

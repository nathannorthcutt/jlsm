---
type: adversarial-finding
domain: concurrency
severity: confirmed
tags: [concurrency, close, cache, lifecycle]
applies_to: ["modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"]
related:
  - "systems/lsm-index-patterns/close-atomicity-cas.md"
  - "systems/lsm-index-patterns/iterator-use-after-close.md"
sources:
  - streaming-block-decompression audit R2, 2026-04-03
---

# Cache Mutation After Close

## Pattern

A component writes to a shared cache after being logically closed. The cache
entry may reference data from a file about to be deleted (e.g., during
compaction), causing stale reads or use-after-free on memory-mapped regions.

## Why It Happens

The cache-put is in the read path, not the close path. Developers guard
`close()` itself but forget that in-progress reads racing with close can reach
the cache-put after the closed flag transitions.

## Fix

Guard cache writes with `if (cache != null && !closed)`. The volatile read of
`closed` is cheap and prevents mutation after logical close.

## Test Guidance

Use a poisoned component (e.g., bloom filter returning `mightContain=true`) to
trigger a read. Set `closed = true` between the pre-read check and the cache-put
via a spy. Use a spy cache to detect puts while closed.

## Found In

- streaming-block-decompression (audit R2, 2026-04-03):
  `TrieSSTableReader.readAndDecompressBlock()` — cache.put guarded with `!closed`

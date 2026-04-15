---
problem: "Block cache capacity relative to variable block sizes"
slug: "block-cache-block-size-interaction"
captured: "2026-04-14"
status: "final"
---

# Constraint Profile — block-cache-block-size-interaction

## Problem Statement
Block cache capacity is entry-count-based (LinkedHashMap). When block size varies
across SSTables (4 KiB local vs 8 MiB remote, or mixed after compaction/transfer),
a fixed entry count leads to unpredictable memory usage. Capacity 1000 could mean
4 MiB or 8 GB depending on block sizes.

## Constraints

### Scale
Cache serves all SSTable reads across the tree. Block sizes vary from 4 KiB
(local SSD) to 8 MiB (remote/object storage). A single cache instance may hold
blocks from SSTables written at different block sizes after compaction or
cross-node transfer.

### Resources
Entry-count capacity on LinkedHashMap with ReentrantLock serialization.
MemorySegment values (decompressed blocks). MemorySegment.byteSize() is O(1).

### Complexity Budget
Cache is well-tested with LinkedHashMap + removeEldestEntry pattern. Changes
should preserve the LinkedHashMap structure where possible. ReentrantLock
serializes all access.

### Accuracy / Correctness
Memory usage must be predictable from configuration. A byte budget must be
respected even when blocks have different sizes.

### Operational Requirements
Operators must reason about cache memory in bytes, not entries. The API should
accept a byte budget, not require manual entry-count derivation.

### Fit
Must work with existing StripedBlockCache → LruBlockCache → LinkedHashMap stack.
Per-stripe capacity division must still work.

## Key Constraints (most narrowing)
1. **Mixed block sizes** — a single cache holds blocks from SSTables with different
   block sizes; entry-count derivation from a single blockSize is wrong
2. **Byte-budget API** — operators reason about bytes, not entries
3. **Minimal internal change** — preserve LinkedHashMap structure; change eviction
   trigger, not data structure

## Unknown / Not Specified
None — full profile captured.

## Constraint Falsification — 2026-04-14
Checked: `.decisions/backend-optimal-block-size/adr.md`, `.decisions/automatic-backend-detection/adr.md`,
`.decisions/cross-stripe-eviction/adr.md`, `LruBlockCache.java`, `StripedBlockCache.java`

Implied constraints found and added:
- Mixed block sizes in a single cache instance (from cross-node transfer, compaction)
- Pool buffer size ≠ cache entry size (write-path vs read-path decoupling)

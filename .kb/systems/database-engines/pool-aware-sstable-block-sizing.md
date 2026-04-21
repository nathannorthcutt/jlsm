---
title: "Pool-Aware and Arena-Aware SSTable Block Size Selection"
aliases: ["adaptive block size", "arena-aligned block size", "slab-aligned sstable block", "pool-granularity block sizing"]
topic: "systems"
category: "database-engines"
tags: ["sstable", "block-size", "arena", "panama-ffm", "alignment", "slab", "fragmentation", "memory-pool", "buffer-pool", "cache-line"]
complexity:
  time_build: "O(1) decision at writer construction; amortized over all block writes"
  time_query: "O(block_size / page_size) pages fetched per block read"
  space: "block_size * (open blocks) per writer; block_size * cache_capacity in block cache"
research_status: "active"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
related:
  - "systems/database-engines/blob-store-patterns.md"
  - "systems/lsm-index-patterns/index-scan-patterns.md"
  - "algorithms/compression/block-compression.md"
decision_refs:
  - "implement-sstable-enhancements/WD-02"
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/Setup-Options-and-Basic-Tuning"
    title: "RocksDB Setup Options and Basic Tuning"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://www.scylladb.com/2017/08/01/compression-chunk-sizes-scylla/"
    title: "Selecting Compression Chunk Sizes for ScyllaDB"
    accessed: "2026-04-20"
    type: "blog"
  - url: "https://engineering.fb.com/2011/01/03/core-infra/scalable-memory-allocation-using-jemalloc/"
    title: "Scalable memory allocation using jemalloc"
    accessed: "2026-04-20"
    type: "blog"
  - url: "https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/foreign/MemorySegment.html"
    title: "MemorySegment (Java SE 24 & JDK 24)"
    accessed: "2026-04-20"
    type: "docs"
# extended source list (RocksDB space-tuning, jemalloc-discuss, Oracle arenas,
# Parquet configs, pmem.io alignment) in the detail file
---

# Pool-Aware and Arena-Aware SSTable Block Size Selection

## summary

SSTable block size has historically been a standalone tuning knob
(RocksDB 4–16 KiB, LevelDB 4 KiB, Cassandra 64 KiB, Parquet 1 MiB pages
inside 128 MiB row groups). That framing breaks when blocks are
staged in a fixed-capacity arena-backed buffer pool: arbitrary block
sizes either waste pool slots (small values, large block) or fragment
across slots (large block, small slot). Pool-aware selection treats
block size as a function of three aligned quantities — pool slot size,
OS page size, average value size — rather than a free parameter. Goal:
block fits one pool slot, is a multiple of the filesystem page, and
keeps slot occupancy above a floor (e.g. 50%) on small-value workloads.

## how-it-works

The SSTable writer acquires a buffer from `ArenaBufferPool` to stage
block bytes before flushing. Three failure modes when slot and block
sizes diverge: (a) `block < slot` pins an unused slot tail (98% waste
at 1 KiB in 64 KiB); (b) `block > slot` cannot stage a block in one
acquire (writer either breaks the one-acquire contract or falls back
to on-heap); (c) `block == slot` but `avg_value << block` fills the
slot with few entries — high point-lookup read amp. Pool-aware
selection resolves all three: `block_size = slot_size`, and
`slot_size` is a power-of-2 multiple of the OS page sized for the
expected average entry.

```
ArenaBufferPool (Arena.ofShared): [slot0|slot1|slot2|slot3|...] each = block_size
writer.acquire() → stage block → flush → release()
```

### key-parameters

| Parameter | Typical | Impact |
|-----------|---------|--------|
| `pool.slotSize` | 4 KiB – 1 MiB | must equal `block_size`; defines budget |
| `block_size` | 4 KiB – 256 KiB | bigger → scan locality; smaller → point-read amp down |
| `page_size` | 4 KiB (Linux), 16 KiB (Apple) | must divide `block_size` |
| `cache_line` | 64 B (x86/ARM) | block base 64 B-aligned for SIMD |
| `avg_entry_size` | workload | `<< block_size` → consider multi-class |
| `min_fill_ratio` | 0.25–0.75 | below → split to smaller class |

## algorithm-steps

1. **Discover pool granularity** — read `ArenaBufferPool.slotSize()`
   (immutable upper bound on block size).
2. **Discover page size** — `FileStore.getBlockSize()` or default 4096
   (hard lower bound).
3. **Validate alignment** — require `slotSize % pageSize == 0` and
   `slotSize` a power of two; alignment = `max(pageSize, 64)` covers
   both page I/O and cache-line SIMD.
4. **Compute expected fill** — sample `avg_entry_size` from schema
   hints or bootstrap; `expected_entries = slot_size / avg_entry_size`.
5. **Single-class vs multi-class**:
   - If `expected_entries >= min_fill_ratio * target`: accept
     `block_size = slot_size`.
   - Else: escalate to multi-class (small + large slot pools). Never
     shrink `block_size` below `slot_size` in a single-class pool.
6. **Allocate** — `arena.allocate(slot_size, max(pageSize, 64))`.
7. **Stage / flush / release** — write until next entry would overflow
   `slot_size − footer_reserve`; optionally compress; append to
   channel; release.
8. **Record audit** — block index stores on-disk length so reader
   acquires a single full slot without probing.

## implementation-notes

### data-structure-requirements

- `ArenaBufferPool` must expose `slotSize()` — writers need the value,
  not just `acquire()/release()`.
- `BlockBuilder` needs a footer reserve (CRC + type + count) for the
  overflow check.
- Block index must record on-disk length explicitly (compressed blocks
  are smaller than `slot_size` but the reader still wants one full slot).

### edge-cases-and-gotchas

See [detail file](pool-aware-sstable-block-sizing-detail.md#edge-cases-and-gotchas)
— Panama `Arena.allocate` alignment, `MemorySegment.ofArray` pitfalls,
silent slab waste, cache-line false sharing, compression vs staging size.

## complexity-analysis

- **build-phase** — writer pays O(1) per acquire/release (pool uses
  `LinkedBlockingQueue`). Alignment decided once at construction.
- **query-phase** — one pool acquire + one `pread` + optional decompress
  per block. Point lookup = 1 block/SSTable probed. Range scan =
  ceil(range / entries_per_block) blocks.
- **memory-footprint** — `pool.capacity * slot_size`. Static ceiling
  independent of open SSTable count; the principal advantage.

## tradeoffs

### strengths

- Predictable, bounded off-heap usage independent of workload.
- No stranded-tail fragmentation when block fills slot.
- Cache-line-aligned starts enable SIMD scans without a copy.
- Page-aligned blocks match NVMe/SSD pages; minimal device read amp.
- Reader/writer share one alignment contract; misalignment → corruption signal.

### weaknesses

- Single-class pool cannot serve point-heavy and scan-heavy workloads
  optimally at once. Mitigation: multi-class pool per workload.
- Block-size change requires pool reconfiguration (cold start for
  `Arena.ofShared()`).
- Pool slots must be sized for the worst-case (uncompressed) block,
  leaving capacity unused after compression.

### compared-to-alternatives

Summary: fixed block sizes (LevelDB 4 KiB, RocksDB 16 KiB, Cassandra
64 KiB) all ignore the allocator's granularity. Parquet's page /
row-group hierarchy is the multi-class realisation of the same idea
documented here. See detail file for the per-system breakdown and the
ScyllaDB read-amplification measurement.

## current-research

See detail file: [pool-aware-sstable-block-sizing-detail.md](pool-aware-sstable-block-sizing-detail.md#current-research)
for key papers (WiscKey FAST '16, Evans on jemalloc) and active
research directions (adaptive pool sizing, per-column-family pool
classes, interaction with per-block checksums).

## practical-usage

**When to use**: SSTable writers with a bounded off-heap budget;
writers that acquire from a pool rather than `Arena.ofAuto()`/
`MemorySegment.ofArray`; Vector API block scans (64 B alignment);
remote-backend writers where block-per-PUT cost dominates.

**When not to use**: scan-only workloads with ample RAM; prototypes
that use `Arena.ofAuto()` throughout (no slot concept).

## reference-implementations

See detail file: [pool-aware-sstable-block-sizing-detail.md](pool-aware-sstable-block-sizing-detail.md#reference-implementations).

## code-skeleton

See detail file: [pool-aware-sstable-block-sizing-detail.md](pool-aware-sstable-block-sizing-detail.md#code-skeleton).

## sources

See detail file: [pool-aware-sstable-block-sizing-detail.md](pool-aware-sstable-block-sizing-detail.md#sources).

@./pool-aware-sstable-block-sizing-detail.md

---
*Researched: 2026-04-20 | Next review: 2026-10-17*

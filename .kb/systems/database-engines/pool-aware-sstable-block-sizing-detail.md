---
title: "Pool-Aware SSTable Block Sizing — Detail Appendix"
topic: "systems"
category: "database-engines"
parent: "pool-aware-sstable-block-sizing.md"
last_researched: "2026-04-20"
---

# Pool-Aware SSTable Block Sizing — Detail Appendix

Companion to [pool-aware-sstable-block-sizing.md](pool-aware-sstable-block-sizing.md).
This file holds content that overflows the 200-line cap on the main article:
edge cases, current research, extended trade-off comparisons, the
reference-implementation table, and the full code skeleton.

## edge-cases-and-gotchas

- **Panama `Arena.allocate` alignment**: `byteAlignment` must be a
  power of two. 4096 is fine. 3000 is not. A misconfigured pool will
  throw `IllegalArgumentException` at startup — surface the pool's
  alignment as part of the `ArenaBufferPool.Builder` validation.
- **`MemorySegment.ofArray(byte[])` is not a safe substitute**: heap
  byte arrays have JVM-internal alignment (typically 8 or 16 bytes,
  not the requested `max(pageSize, 64)`). The I/O internals rule
  already requires pool usage; this reaffirms the reason.
- **Slab waste on small values is silent**: a 64 KiB block holding 10
  entries of 20 bytes each wastes 63 KiB of block capacity. It shows
  up as inflated SSTable size and read amplification, not as an error.
  Monitor `entries_per_block` as an SSTable-level metric.
- **Cache-line false sharing**: if two writer threads share a pool
  but their buffers happen to land on adjacent cache lines, hot
  metadata fields in the builder (e.g., `writePosition`) can cause
  cross-core cache invalidations. Pad builder state to 64 B and align
  the buffer start address to 64 B — Panama honours the alignment
  requested at `allocate()` time.
- **Compression changes the on-disk size but not the staging size**:
  the pool slot is sized for the uncompressed block. The compressed
  block on disk is smaller; the reader acquires a full slot to
  decompress into, not to read into.

## current-research

### key-papers

- Lu, L. et al. (2016). *WiscKey: Separating Keys from Values in
  SSD-Conscious Storage*. FAST '16. Establishes that on SSDs the
  block-size tradeoff is dominated by read amplification, not seek
  cost — strengthens the case for small, page-aligned blocks for
  point lookups.
- Evans, J. (2006/2011). *Scalable memory allocation using jemalloc*.
  Meta Engineering. Establishes the 25%-internal-fragmentation bound
  for slab-sized allocators; the upper bound jlsm inherits when it
  follows the same approach.

### active-research-directions

- Adaptive pool sizing at runtime based on observed `entries_per_block`
  (shrink slot class when fill drops below threshold).
- Per-column-family pool classes (seen in RocksDB's partitioned
  cache, absent in most pure LSM engines).
- Interaction with per-block checksums (WD-01 neighbour): checksum
  granularity can be finer than block granularity without changing the
  slot layout, because the checksum lives in the footer reserve.

## Extended Comparison to Alternatives

- vs. **fixed 4 KiB blocks** (LevelDB, RocksDB default for older
  versions): fixed 4 KiB ignores pool granularity entirely. If the pool
  slot is 64 KiB, 15/16 of every slot is stranded during a block
  write. Pool-aware sizing fixes this by default.
- vs. **fixed 16 KiB blocks** (RocksDB recommended default for space
  tuning): same failure mode at smaller scale. Workable only if the
  pool happens to match.
- vs. **fixed 64 KiB chunks** (Cassandra default `chunk_length_in_kb`):
  ScyllaDB's own analysis shows 4× disk bandwidth reduction when
  chunks match small-value workloads instead of a fixed 4 KiB default
  — same argument in the other direction. Adaptive sizing subsumes
  both observations.
- vs. **page/row-group hierarchy** (Parquet: 1 MiB pages, 128 MiB row
  groups): Parquet's two-level hierarchy is the multi-class
  realisation of the same idea. jlsm does not need row-groups for
  LSM point/short-range workloads, but the decision principle is
  identical — pick the inner granularity to match the allocator's
  slot, pick the outer granularity to match the scan.

## Reference Implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| RocksDB `block_based_table_factory` | C++ | https://github.com/facebook/rocksdb/wiki/Setup-Options-and-Basic-Tuning | Active; default 16 KiB after tuning guide update |
| LevelDB `Options::block_size` | C++ | https://github.com/google/leveldb/blob/main/include/leveldb/options.h | Maintenance mode; default 4 KiB |
| Cassandra `chunk_length_in_kb` | Java | https://cassandra.apache.org/doc/3.11/cassandra/operating/compression.html | Active; default 64 KiB, ScyllaDB proposed 4 KiB |
| Apache Parquet `row.group.size` / `page.size` | Java/C++ | https://parquet.apache.org/docs/file-format/configurations/ | Active; 1 MiB page inside 128 MiB row group |
| jemalloc size classes | C | http://jemalloc.net/ | Active; reference for slab-class internal-fragmentation bounds |

## Code Skeleton

```java
// Writer side — pool-aware block sizing decision.
public final class PoolAwareBlockBuilder implements AutoCloseable {
    private final ArenaBufferPool pool;
    private final long blockSize;       // == pool.slotSize()
    private final int footerReserve;
    private MemorySegment buffer;       // one pool slot
    private long writePosition;

    public PoolAwareBlockBuilder(ArenaBufferPool pool, int footerReserve) {
        this.pool = Objects.requireNonNull(pool);
        long slot = pool.slotSize();
        long pageSize = 4096L; // or discover via FileStore.getBlockSize()
        if (slot % pageSize != 0) {
            throw new IllegalArgumentException(
                "pool slot " + slot + " must be a multiple of page " + pageSize);
        }
        if (Long.bitCount(slot) != 1) {
            throw new IllegalArgumentException(
                "pool slot " + slot + " must be a power of 2 for Arena.allocate");
        }
        this.blockSize = slot;
        this.footerReserve = footerReserve;
        this.buffer = pool.acquire();   // aligned to max(pageSize, 64)
        this.writePosition = 0L;
    }

    public boolean tryAppend(MemorySegment entry) {
        long need = entry.byteSize();
        if (writePosition + need + footerReserve > blockSize) return false;
        MemorySegment.copy(entry, 0, buffer, writePosition, need);
        writePosition += need;
        return true;
    }

    public long flushTo(SeekableByteChannel channel) throws IOException {
        long written = writePosition + footerReserve;
        // ... write footer bytes at buffer[writePosition..writePosition+footerReserve]
        channel.write(buffer.asSlice(0, written).asByteBuffer());
        return written;
    }

    @Override public void close() {
        pool.release(buffer);
        buffer = null;
    }
}
```

## Sources

1. [RocksDB Setup Options and Basic Tuning](https://github.com/facebook/rocksdb/wiki/Setup-Options-and-Basic-Tuning) — authoritative tuning defaults (`block_size = 16 * 1024`, block cache ~1/3 of memory).
2. [RocksDB Space Tuning](https://github.com/facebook/rocksdb/wiki/Space-Tuning) — bigger blocks improve compression but penalise point reads; the small-block dictionary-compression alternative.
3. [Selecting Compression Chunk Sizes for ScyllaDB](https://www.scylladb.com/2017/08/01/compression-chunk-sizes-scylla/) — measured 4× read-bandwidth reduction by matching chunk size to small-value partition size; the canonical small-value read-amplification evidence.
4. [Scalable memory allocation using jemalloc](https://engineering.fb.com/2011/01/03/core-infra/scalable-memory-allocation-using-jemalloc/) — ~25% internal-fragmentation bound for slab-class allocators; underpins the fill-ratio floor.
5. [jemalloc-discuss: aligned allocation overhead](http://jemalloc.net/mailman/jemalloc-discuss/2015-February/001054.html) — how aligned allocations larger than a size class become a full-page allocation; directly relevant to `Arena.allocate(size, alignment)`.
6. [MemorySegment — Java SE 24 & JDK 24](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/foreign/MemorySegment.html) — `byteAlignment` must be a power of two; `asSlice` preserves or strengthens alignment.
7. [Memory Segments and Arenas — Oracle](https://docs.oracle.com/en/java/javase/21/core/memory-segments-and-arenas.html) — arena-scoped allocation, `Arena.ofShared()` semantics, alignment-preserving slicing.
8. [Apache Parquet File Format Configurations](https://parquet.apache.org/docs/file-format/configurations/) — two-tier page/row-group hierarchy; the multi-class analogue.
9. [I/O Alignment Considerations — pmem.io](https://docs.pmem.io/persistent-memory/getting-started-guide/creating-development-environments/linux-environments/advanced-topics/i-o-alignment-considerations) — page cache works in 4 KiB chunks; misaligned small writes trigger read-modify-write and read amplification.

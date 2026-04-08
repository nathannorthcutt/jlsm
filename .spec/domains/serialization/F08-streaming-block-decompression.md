---
{
  "id": "F08",
  "version": 2,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["serialization", "storage"],
  "requires": ["F02"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["sstable-block-compression-format", "compression-codec-api-design"],
  "kb_refs": ["algorithms/compression/block-compression-algorithms", "systems/lsm-index-patterns/index-scan-patterns"],
  "open_obligations": []
}
---

# F08 — Streaming Block Decompression

## Requirements

### Lazy full-scan decompression

R1. The reader `scan()` method on a v2 (compressed) SSTable must not decompress all data blocks before returning the iterator. The iterator must decompress blocks incrementally as iteration advances.

R2. The full-scan iterator for v2 SSTables must hold at most one decompressed block in memory at any point during iteration. When the iterator advances past the last entry in a block, the reference to that block's decompressed data must be released before decompressing the next block.

R3. The full-scan iterator must decompress blocks in ascending block-index order, starting at block index 0 and ending at the last block in the compression map.

R4. The full-scan iterator must yield entries in the same order as the pre-existing `DataRegionIterator` for an equivalent uncompressed SSTable — sorted by key, then by sequence number within each block.

### Range-scan block caching

R5. The range-scan iterator (`scan(fromKey, toKey)`) on a v2 SSTable must cache the most recently decompressed block. When the next entry resides in the same block as the previous entry, the iterator must reuse the cached decompressed block without re-decompressing it.

R6. The range-scan iterator must replace its cached block when the next entry resides in a different block index than the cached one. The previous cached block reference must be released when the replacement occurs.

R7. The range-scan iterator must initialize its cached block index to a sentinel value that does not match any valid block index, so the first entry lookup always triggers a decompression.

### BlockCache bypass

R8. The full-scan iterator must not read from or write to the shared `BlockCache`. All block decompressions during a full scan must bypass the cache entirely.

R9. The range-scan iterator must not read from or write to the shared `BlockCache` for blocks decompressed during range iteration. All block decompressions during a range scan must bypass the cache entirely.

R10. The `readAndDecompressBlockNoCache` method must perform the same decompression logic as `readAndDecompressBlock` (codec lookup, compressed data read, decompression) but must omit all `BlockCache.get()` and `BlockCache.put()` calls.

### v1 behavior preservation

R11. The `scan()` method on a v1 (uncompressed) SSTable must continue to use the existing `DataRegionIterator` with the full data region. The streaming decompression path must not alter v1 scan behavior.

R12. The `scan(fromKey, toKey)` method on a v1 SSTable must continue to use absolute file offsets and the existing `readDataAtV1` method. The range-scan block-caching logic must only activate when a compression map is present.

### Point-get isolation

R13. The `get()` method must continue to use `readAndDecompressBlock` (with BlockCache integration). The streaming decompression path must not alter point-get behavior or its cache interaction.

### Entry decoding correctness

R14. The full-scan iterator must read the 4-byte big-endian entry count at the start of each decompressed block and decode exactly that many entries from the block before advancing to the next block.

R15. The range-scan iterator must decode a single entry at the intra-block offset specified by the key index for each key-index entry it processes. The intra-block offset must be applied to the decompressed block data, not to compressed on-disk data.

### Iterator contract

R16. The full-scan iterator `hasNext()` must return false after the last entry of the last block has been returned by `next()`. No further calls to `next()` may succeed after `hasNext()` returns false.

R17. Both scan iterators must throw `NoSuchElementException` from `next()` when no more entries are available.

R18. Both scan iterators must wrap `IOException` from block decompression or channel reads in `UncheckedIOException` and propagate it from the `next()` or `hasNext()` call that triggered the I/O.

### Reader lifecycle interaction

R19. If the reader is closed while a full-scan iterator has not been exhausted, the iterator must detect the closed state and throw `IllegalStateException` on the next `advance()` call. The iterator must not silently return stale data or return false from `hasNext()` without signaling the close.

R20. If the reader is closed while a range-scan iterator has not been exhausted, the iterator must detect the closed state. The `hasNext()` method must reflect the closed state, and `next()` must throw `IllegalStateException` if the reader is closed.

### Empty and single-block edge cases

R21. The full-scan iterator on a v2 SSTable with zero blocks in the compression map must immediately report `hasNext() == false` without attempting any decompression.

R22. The full-scan iterator on a v2 SSTable with exactly one block must decompress that single block, yield all its entries, and then report `hasNext() == false`.

R23. The range-scan iterator on a range that spans entries within a single block must decompress that block exactly once for the entire range traversal.

### Decompression failure handling

R24. If decompression of a block fails (corrupt compressed data, codec error), the full-scan iterator must propagate the failure as an `UncheckedIOException`. The iterator must not skip the corrupt block and continue to subsequent blocks.

R25. If decompression of a block fails during range-scan iteration, the range-scan iterator must propagate the failure as an `UncheckedIOException`. The cached block state must not be updated to the failed block index.

### Concurrent iteration

R26. Multiple independent iterators created from the same lazy reader instance must each maintain their own block-index position and cached-block state. One iterator advancing must not affect the block position or cached data of another iterator.

R27. For lazy readers, the underlying channel read used by `readAndDecompressBlockNoCache` must be synchronized on the channel, consistent with the existing `readBytes` synchronization contract (F02 R35).

### Dead code removal

R28. The `decompressAllBlocks` method (or any method whose sole purpose is bulk decompression of all blocks before iteration) must not exist in the reader after this feature is implemented. If such a method existed prior to this feature, it must be removed.

### v1/v2 close-behavior divergence

R29. The v2 full-scan iterator (`CompressedBlockIterator`) and the v1 full-scan iterator (`DataRegionIterator`) have intentionally different behavior when the reader is closed mid-iteration. The v1 iterator operates on a pre-loaded byte array snapshot and continues to yield entries after close. The v2 iterator performs live I/O per block and must throw `IllegalStateException` after close (per R19). This divergence is a direct consequence of the streaming decompression design: eager snapshot would reintroduce the O(total uncompressed data) memory pressure that this feature eliminates. Callers that require close-safe iteration must exhaust the iterator before closing the reader, or copy entries into a caller-owned collection before close.

---

## Design Narrative

### Intent

Replace the upfront full-data decompression in SSTable v2 scan paths with lazy per-block decompression. The current implementation decompresses every block into a single byte array before iteration begins, producing O(total uncompressed data) memory pressure and a measured ~37-39% throughput regression on compressed SSTables versus uncompressed. Streaming decompression reduces scan memory to O(single block) and eliminates the upfront decompression latency, recovering the throughput loss.

### Why this approach

Two iterator implementations — `CompressedBlockIterator` for full scans and augmented `IndexRangeIterator` for range scans — were chosen because the two scan paths have fundamentally different access patterns. Full scans are sequential (block 0, 1, 2, ...) and never revisit a block, so a simple advancing cursor with no caching is optimal. Range scans follow key-index entries that may reference consecutive entries within the same block, so a single-block cache eliminates redundant decompression without unbounded memory growth.

Both iterators bypass `BlockCache` deliberately. Scan workloads touch every block sequentially and would evict the working set of point-get queries from the cache. This is the standard "scan pollution" problem. The point-get path (`get()`) continues to use the cache.

The `readAndDecompressBlockNoCache` helper factors out the shared decompression logic (read compressed bytes, resolve codec, decompress) while omitting cache interaction, avoiding code duplication between the two iterator types.

### v1/v2 close divergence (R29)

Updated by audit finding F-R1.conc.2.7: the v1/v2 close-behavior asymmetry was flagged as a concurrency concern. Analysis confirmed the divergence is load-bearing — resolving it via eager snapshot would undo the streaming memory benefit (R1, R2). R29 was added to make this design choice explicit rather than leaving it implicit in the interaction between R11 and R19.

### What was ruled out

- **Sub-block streaming** (decompressing partial blocks): DEFLATE and most block codecs require the full compressed block to produce any output. Sub-block streaming would require a different on-disk format with independently decompressible chunks within each block — significant format complexity for marginal benefit.
- **BlockCache integration for scans** (cache-friendly scan): Populating the cache during sequential scans would evict point-get working set entries. A scan-specific cache tier was considered out of scope; the bypass approach is simpler and avoids the eviction problem entirely.
- **Prefetching next block during iteration**: Overlapping I/O and decompression with entry processing would improve throughput further but adds concurrency complexity (background thread or async I/O). Deferred to a future optimization pass.
- **Changes to `open()` / `openLazy()` factory methods**: The streaming change is internal to the iterator implementations. The reader's construction path and metadata loading are unchanged.

### Cross-references

- F02 (Block-Level SSTable Compression): prerequisite — defines the on-disk format, compression map, codec contract, and BlockCache integration that this feature builds on.
- ADR: sstable-block-compression-format — block layout and compression map format.
- ADR: compression-codec-api-design — codec interface and resolution mechanism.
- KB: algorithms/compression/block-compression-algorithms — codec characteristics and block-level decompression constraints.
- KB: systems/lsm-index-patterns/index-scan-patterns — scan access patterns and cache pollution analysis.

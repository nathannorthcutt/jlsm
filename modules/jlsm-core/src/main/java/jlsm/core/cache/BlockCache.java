package jlsm.core.cache;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An in-process cache for hot SSTable data blocks, reducing repeated disk I/O for frequently
 * accessed key ranges.
 *
 * <p>
 * <b>Pipeline position</b>: Sits between the SSTable reader and the underlying file system. When an
 * SSTable reader needs a block, it consults the cache first; on a miss it reads from disk and
 * inserts the block into the cache for future accesses.
 *
 * <p>
 * <b>Key contracts</b>:
 * <ul>
 * <li>Blocks are addressed by {@code (sstableId, blockOffset)} — a globally unique identifier
 * within a store instance.</li>
 * <li>{@link #evict} must be called after compaction removes an SSTable so that stale blocks are
 * not served to readers.</li>
 * <li>The replacement policy (e.g., LRU, CLOCK) is implementation-defined; {@link #size} and
 * {@link #capacity} allow monitoring of cache pressure.</li>
 * <li>Implementations must be safe for concurrent use by multiple SSTable readers.</li>
 * <li>{@link #close} releases any off-heap or native memory held by cached blocks.</li>
 * </ul>
 */
public interface BlockCache extends Closeable {

    /**
     * Returns the cached block for the given SSTable and offset, or {@link Optional#empty()} on a
     * cache miss.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @return an {@link Optional} containing the cached {@link MemorySegment}, or empty on a miss
     */
    Optional<MemorySegment> get(long sstableId, long blockOffset);

    /**
     * Inserts or replaces a block in the cache. The cache may evict other entries to make room.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param block the block data to cache; must not be null
     */
    void put(long sstableId, long blockOffset, MemorySegment block);

    /**
     * Returns the cached block for the given SSTable and offset, loading and caching it via
     * {@code loader} on a miss.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param loader called exactly once on a cache miss to supply the block; must not be null and
     *            must not return null
     * @return the cached or freshly loaded {@link MemorySegment}; never null
     */
    default MemorySegment getOrLoad(long sstableId, long blockOffset,
            Supplier<MemorySegment> loader) {
        return get(sstableId, blockOffset).orElseGet(() -> {
            MemorySegment block = loader.get();
            put(sstableId, blockOffset, block);
            return block;
        });
    }

    /**
     * Removes all cached blocks belonging to the given SSTable. Must be called after compaction
     * permanently removes the SSTable from the level manifest.
     *
     * @param sstableId the unique identifier of the SSTable whose blocks should be evicted
     */
    void evict(long sstableId);

    /**
     * Returns the number of blocks currently held in the cache.
     *
     * @return current block count; always non-negative
     */
    long size();

    /**
     * Returns the maximum number of blocks (or bytes, depending on the implementation's unit) that
     * this cache can hold before eviction begins.
     *
     * @return cache capacity; always positive
     */
    long capacity();

    /**
     * Releases all resources held by this cache, including any off-heap or native memory. After
     * this call, behavior of all other methods is undefined.
     */
    @Override
    void close();
}

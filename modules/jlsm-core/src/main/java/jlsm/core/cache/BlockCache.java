package jlsm.core.cache;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
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
     * <p>
     * <b>Atomicity note (sstable.byte-budget-block-cache v3 R6):</b> the default implementation is
     * non-atomic — it performs {@link #get} and {@link #put} as separate operations and
     * synchronises on {@code this} to prevent duplicate loader invocations. Implementations that
     * require atomic insertion (for example to funnel every insertion through a single
     * byte-tracking chokepoint) MUST override this method.
     *
     * <p>
     * <b>Monitor-collision warning:</b> the default implementation acquires this instance's
     * intrinsic monitor (the object's {@code synchronized(this)} lock). Callers MUST NOT invoke
     * {@code getOrLoad} while holding the cache instance's intrinsic monitor externally — doing so
     * risks unexpected contention and, in combination with other callers, can produce deadlock.
     * Third-party implementers that inherit this default MUST NOT guard their own internal state
     * with {@code synchronized(this)} on the cache instance; use a private lock object or override
     * this method. Implementations that override {@code getOrLoad} with a private lock (as both
     * in-tree implementations do) are not subject to this restriction.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param loader called exactly once on a cache miss to supply the block; must not be null and
     *            must not return null
     * @return the cached or freshly loaded {@link MemorySegment}; never null
     */
    default MemorySegment getOrLoad(long sstableId, long blockOffset,
            Supplier<MemorySegment> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        synchronized (this) {
            return get(sstableId, blockOffset).orElseGet(() -> {
                MemorySegment block = Objects.requireNonNull(loader.get(),
                        "loader must not return null");
                put(sstableId, blockOffset, block);
                return block;
            });
        }
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
     * Returns the byte budget of this cache — the maximum total bytes across all cached segments
     * (measured via {@link MemorySegment#byteSize()}) that the cache holds before eviction begins.
     *
     * <p>
     * <b>Unit (sstable.byte-budget-block-cache v3 R14):</b> bytes, not entries. Prior to that spec
     * (F09) the unit was entries; all current and future implementations of this interface MUST
     * report a byte budget. Implementations are free to cap the entry count internally as an
     * invariant guard (see e.g. R28a), but this method's contract is byte-valued.
     *
     * @return cache byte budget; always positive
     */
    long capacity();

    /**
     * Releases all resources held by this cache, including any off-heap or native memory.
     *
     * <p>
     * <b>Use-after-close contract (sstable.byte-budget-block-cache v3 R31):</b> after this call
     * returns, every subsequent invocation of {@link #get}, {@link #put}, {@link #getOrLoad},
     * {@link #evict}, {@link #size}, or {@link #capacity} on this instance MUST throw
     * {@link IllegalStateException}. Implementations must not serve cached segments from a released
     * arena, silently return {@link Optional#empty()}, or continue to mutate accounting state.
     * Callers that hold a reference through the {@link BlockCache} interface rely on this promotion
     * to detect incorrect lifecycle use portably, regardless of concrete implementation.
     *
     * <p>
     * Close itself is permitted (but not required) to be idempotent per {@link Closeable#close()}.
     */
    @Override
    void close();
}

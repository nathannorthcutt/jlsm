package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * An LRU block cache backed by a {@link LinkedHashMap} with access-order eviction, bounded by a
 * configured byte budget rather than an entry count (per sstable.byte-budget-block-cache v3).
 *
 * <p>
 * All operations are serialised through a single {@link ReentrantLock}. A {@code ReadWriteLock}
 * cannot be used here because {@link LinkedHashMap#get} with {@code accessOrder=true} modifies
 * internal ordering and is therefore a write-equivalent operation.
 *
 * <p>
 * Obtain instances via {@link #builder()}, or use the convenience factory methods
 * {@link #getMultiThreaded()} and {@link #getSingleThreaded()} for the recommended entry points.
 */
public final class LruBlockCache implements BlockCache {

    private record CacheKey(long sstableId, long blockOffset) {
    }

    // @spec sstable.byte-budget-block-cache.R5 — per-entry byte budget; currentBytes
    // tracks total cached bytes under the ReentrantLock
    private final long byteBudget;
    private long currentBytes;
    // @spec sstable.striped-block-cache.R34,R36 — each stripe holds its own independent lock;
    // concurrent operations on different stripes never contend on the same monitor
    private final ReentrantLock lock;
    private final LinkedHashMap<CacheKey, MemorySegment> map;
    private volatile boolean closed;

    private LruBlockCache(Builder builder) {
        // Runtime check, not assert: reflective callers that bypass Builder.build() still get a
        // clear IllegalArgumentException under -da. Documented by SharedStateAdversarialTest
        // finding F-R1.shared_state.2.4 (regression watch). Finding
        // F-R1.contract_boundaries.4.2: when byteBudget is still at the -1L sentinel (setter
        // never called), emit the same "not set" diagnostic that build() emits rather than
        // echoing the sentinel value into the error surface.
        if (builder.byteBudget == -1L) {
            throw new IllegalArgumentException(
                    "byteBudget not set — call .byteBudget(n) before .build()");
        }
        if (builder.byteBudget <= 0) {
            throw new IllegalArgumentException(
                    "byteBudget must be positive, got: " + builder.byteBudget);
        }
        this.byteBudget = builder.byteBudget;
        this.lock = new ReentrantLock();
        // @spec sstable.byte-budget-block-cache.R10,R11 — no removeEldestEntry override;
        // eviction is driven from the insertion chokepoint against the byte budget
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public Optional<MemorySegment> get(long sstableId, long blockOffset) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            return Optional.ofNullable(map.get(new CacheKey(sstableId, blockOffset)));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or replaces a block in the cache. The cache may evict other entries to honour the
     * configured byte budget.
     *
     * <p>
     * <b>MemorySegment slice contract (R30):</b> byte accounting uses
     * {@link MemorySegment#byteSize()} and does not reason about shared backing allocations.
     * Callers that insert slices of a larger mmap'd or off-heap region bound the sum of slice
     * {@code byteSize()} values, not the sum of distinct backing allocations. Callers wishing to
     * bound actual committed off-heap memory must ensure the segments they insert are backed by
     * distinct allocations.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param block the block data to cache; must not be null and must have positive byteSize (R9)
     * @throws IllegalArgumentException if {@code blockOffset < 0} or {@code block.byteSize() == 0}
     * @throws NullPointerException if {@code block} is null
     * @throws IllegalStateException if the cache is closed, the entry-count cap (R28a) is reached,
     *             or byte accounting would overflow {@code Long.MAX_VALUE} (R29)
     */
    @Override
    public void put(long sstableId, long blockOffset, MemorySegment block) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        Objects.requireNonNull(block, "block must not be null");
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            // @spec sstable.byte-budget-block-cache.R6 — single insertion chokepoint
            insertEntry(new CacheKey(sstableId, blockOffset), block);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the cached block for the given SSTable and offset, loading and caching it via
     * {@code loader} on a miss.
     *
     * <p>
     * <b>Loader ownership (R9a):</b> if the loader returns a zero-length segment, the cache rejects
     * it with {@link IllegalArgumentException} without committing it to the map. The loader retains
     * ownership of any external resources (FileChannel, Arena, socket, etc.) backing the returned
     * segment — the cache does NOT assume ownership of a discarded zero- length segment, so the
     * loader must either avoid allocating such resources for a zero-length payload or release them
     * before returning.
     *
     * <p>
     * <b>Loader exception (R32):</b> if the loader throws, no state mutation (no map insertion, no
     * byte accounting change, no eviction) occurs; the exception propagates to the caller.
     *
     * <p>
     * <b>MemorySegment slice contract (R30):</b> same as {@link #put(long, long, MemorySegment)}.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param loader called on a cache miss to supply the block; must not be null and must not
     *            return null or a zero-length segment
     * @return the cached or freshly loaded {@link MemorySegment}; never null
     */
    // @spec sstable.striped-block-cache.R37,R47 — release the stripe lock before calling
    // loader.get() (R37); double-checked locking ensures concurrent callers observe the same
    // cached reference (R47)
    @Override
    public MemorySegment getOrLoad(long sstableId, long blockOffset,
            Supplier<MemorySegment> loader) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        Objects.requireNonNull(loader, "loader must not be null");
        var key = new CacheKey(sstableId, blockOffset);

        // First check: look up under lock without calling the loader
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            var existing = map.get(key);
            if (existing != null) {
                return existing;
            }
        } finally {
            lock.unlock();
        }

        // Re-check closed after releasing the lock — prevents invoking the loader
        // (which may perform I/O) on a cache that was closed between the first lock
        // release and here.
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }

        // Load outside the lock — avoids blocking all cache operations during I/O.
        // @spec sstable.byte-budget-block-cache.R32 — loader exception propagates with no state
        // mutation (we have not yet touched map or currentBytes at this point)
        var block = loader.get();
        Objects.requireNonNull(block, "loader must not return null");
        // @spec sstable.byte-budget-block-cache.R9a — zero-length loader result rejected before
        // lock reacquisition; the loader-returned segment is discarded without being committed
        if (block.byteSize() == 0L) {
            throw new IllegalArgumentException(
                    "loader must not return a zero-length segment (byteSize == 0)");
        }

        // @spec sstable.byte-budget-block-cache.R47 — double-checked locking: if another thread
        // committed a value during our load, return it and discard ours; either way, all callers
        // observe the same cached reference
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            var existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            // @spec sstable.byte-budget-block-cache.R6 — single insertion chokepoint
            insertEntry(key, block);
            return block;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The single private chokepoint through which every map insertion must funnel. Enforces the
     * zero-length guard (R9/R9a), the entry-count cap (R28a), the overflow guard (R29), performs
     * the put-replace byte accounting atomically (R8), and runs the eviction loop (R10/R11) all
     * within the caller's critical section.
     *
     * <p>
     * Caller must hold {@link #lock}.
     */
    // @spec sstable.byte-budget-block-cache.R6 — single insertion chokepoint
    private void insertEntry(CacheKey key, MemorySegment block) {
        assert lock.isHeldByCurrentThread() : "insertEntry must be called under lock";

        // @spec sstable.byte-budget-block-cache.R9 — reject zero-length segments before any state
        // mutation. R9a's loader-result check runs before this call; the guard is duplicated here
        // to keep all insertion guards inside the chokepoint.
        final long newBytes = block.byteSize();
        if (newBytes == 0L) {
            throw new IllegalArgumentException(
                    "block must have positive byteSize, got byteSize() == 0");
        }

        final boolean replacing = map.containsKey(key);

        // @spec sstable.byte-budget-block-cache.R28a — new-key entry-count cap. Must reject BEFORE
        // map.put() runs so the map is never observed at size Integer.MAX_VALUE, even transiently.
        // Applies to all non-replacing inserts regardless of whether the new entry is oversized:
        // oversized inserts also grow map.size() by one prior to the R11 eviction loop draining
        // siblings, so the guard fires at the same threshold. Finding F-R1.shared_state.1.3.
        if (!replacing && map.size() >= Integer.MAX_VALUE - 1) {
            throw new IllegalStateException(
                    "cache entry count cap reached (Integer.MAX_VALUE - 1)");
        }

        // @spec sstable.byte-budget-block-cache.R29 — compute prospective currentBytes BEFORE any
        // mutation. Overflow detection must leave map and currentBytes untouched.
        final long prospectiveBytes;
        try {
            if (replacing) {
                final long replacedBytes = map.get(key).byteSize();
                prospectiveBytes = Math.addExact(Math.subtractExact(currentBytes, replacedBytes),
                        newBytes);
            } else {
                prospectiveBytes = Math.addExact(currentBytes, newBytes);
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                    "byte accounting would overflow Long.MAX_VALUE; insertion rejected with no "
                            + "state mutation",
                    overflow);
        }

        // @spec sstable.byte-budget-block-cache.R8,R11 — commit the new entry and update
        // currentBytes atomically under the lock, then run the eviction loop. R11 oversized-entry
        // case: when newBytes > byteBudget, the eviction loop drains every other entry, leaving
        // the just-committed oversized entry as the sole resident.
        currentBytes = prospectiveBytes;
        map.put(key, block);

        // @spec sstable.byte-budget-block-cache.R10,R11,R12,R13 — eviction loop: evict eldest
        // entries until currentBytes <= byteBudget, or only the just-inserted entry remains
        // (R11 oversized-entry exception). Runs in the same lock critical section as the put.
        if (currentBytes > byteBudget) {
            Iterator<Map.Entry<CacheKey, MemorySegment>> it = map.entrySet().iterator();
            while (currentBytes > byteBudget && map.size() > 1 && it.hasNext()) {
                Map.Entry<CacheKey, MemorySegment> eldest = it.next();
                if (eldest.getKey().equals(key)) {
                    // Never evict the entry we just inserted; skip it in the iteration order
                    continue;
                }
                it.remove();
                // @spec sstable.byte-budget-block-cache.R7 — byte accounting decrements via the
                // subtractBytes helper; iterator.remove() has already mutated the map, so we must
                // not double-remove via a keyed-removal path.
                subtractBytes(eldest.getValue());
            }
        }
    }

    /**
     * Subtracts a removed entry's bytes from {@link #currentBytes}. Invoked by every removal path
     * (in-iterator eviction inside {@link #insertEntry}, {@link #evict(long)}, and
     * {@link #close()}) after the map mutation has occurred; consolidating byte accounting here
     * keeps R7 enforcement in one place without requiring a separate keyed-removal chokepoint.
     *
     * <p>
     * Caller must hold {@link #lock}.
     */
    // @spec sstable.byte-budget-block-cache.R7 — per-entry byte subtraction helper; every map
    // removal (iterator.remove in insertEntry/evict/close) routes its byte decrement through here
    private void subtractBytes(MemorySegment removed) {
        assert lock.isHeldByCurrentThread() : "subtractBytes must be called under lock";
        final long removedBytes = removed.byteSize();
        assert removedBytes >= 0 : "MemorySegment.byteSize() must be non-negative";
        // Runtime check, not assert-only: the non-negative invariant on currentBytes is
        // safety-critical — if it ever drifts negative the eviction loop's `currentBytes >
        // byteBudget` check at insertEntry silently becomes permanently false, permitting
        // unbounded growth until the R28a entry-count cap. Assertions are disabled in
        // production (-da), so the spec's byte-budget invariant cannot rely on them alone
        // per coding-guidelines.md ("asserts must never be the sole mechanism satisfying
        // a spec requirement"). Compute-then-check-then-commit leaves state untouched on
        // violation so the failure surfaces cleanly rather than corrupting the accounting.
        final long projected = currentBytes - removedBytes;
        if (projected < 0L) {
            throw new IllegalStateException(
                    "currentBytes byte-accounting invariant violated: subtraction would yield "
                            + projected + " (currentBytes=" + currentBytes + ", removedBytes="
                            + removedBytes + "); refusing to commit negative accounting state");
        }
        currentBytes = projected;
    }

    @Override
    public void evict(long sstableId) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            // @spec sstable.byte-budget-block-cache.R7 — route removals through the chokepoint
            Iterator<Map.Entry<CacheKey, MemorySegment>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CacheKey, MemorySegment> entry = it.next();
                if (entry.getKey().sstableId() == sstableId) {
                    it.remove();
                    subtractBytes(entry.getValue());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long size() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the configured byte budget.
     *
     * <p>
     * <b>Unit change (R14):</b> prior to sstable.byte-budget-block-cache v3, this cache was sized
     * in entries (F09). Starting with v3 the unit is bytes: the returned value is the total byte
     * budget, and {@link #size()} (which returns entry count) is no longer directly comparable to
     * this value. The two methods use different units.
     *
     * @return the configured byte budget, always positive
     * @throws IllegalStateException if the cache is closed
     */
    // @spec sstable.byte-budget-block-cache.R14 — capacity() returns the byte budget in bytes
    @Override
    public long capacity() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return byteBudget;
    }

    /**
     * Returns the configured byte budget.
     *
     * <p>
     * Parallel accessor to {@link #capacity()}; added in v3 so callers can self-document the unit
     * they care about. Both methods return identical values.
     *
     * @return the configured byte budget, always positive
     * @throws IllegalStateException if the cache is closed (R31)
     */
    // @spec sstable.byte-budget-block-cache.R14,R31 — byteBudget accessor; ISE after close
    public long byteBudget() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return byteBudget;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            // @spec sstable.byte-budget-block-cache.R16 — atomic under lock: set closed, clear
            // via the R7 removal chokepoint so currentBytes decrements, then assert zero and
            // defensively reset. Double-close short-circuits above.
            closed = true;
            Iterator<Map.Entry<CacheKey, MemorySegment>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CacheKey, MemorySegment> entry = it.next();
                it.remove();
                subtractBytes(entry.getValue());
            }
            assert currentBytes == 0L
                    : "currentBytes must be zero after close drained the map via R7 chokepoint; "
                            + "non-zero indicates an R7 byte-tracking bug, got: " + currentBytes;
            // Defensive: production builds (without -ea) still leave the cache in a consistent
            // empty state.
            currentBytes = 0L;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a {@link StripedBlockCache.Builder} for constructing a striped (sharded) cache
     * optimized for multi-threaded access. Each stripe has its own lock, eliminating single-lock
     * contention under concurrent workloads.
     *
     * @return a new {@link StripedBlockCache.Builder}
     */
    // @spec sstable.striped-block-cache.R41 — getMultiThreaded returns StripedBlockCache.Builder
    public static StripedBlockCache.Builder getMultiThreaded() {
        return StripedBlockCache.builder();
    }

    /**
     * Returns a {@link Builder} for constructing a single-lock LRU cache suitable for
     * single-threaded or low-contention workloads. Equivalent to {@link #builder()}.
     *
     * @return a new {@link Builder}
     */
    // @spec sstable.striped-block-cache.R42 — getSingleThreaded returns LruBlockCache.Builder
    public static Builder getSingleThreaded() {
        return builder();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long byteBudget = -1L;

        private Builder() {
        }

        /**
         * Configures the byte budget for this cache (per sstable.byte-budget-block-cache v3 R1/R2).
         *
         * <p>
         * <b>Transactional setter (R2):</b> this setter validates {@code bytes} before mutating any
         * builder state. If validation fails the builder is left unchanged and a subsequent call
         * with a valid value succeeds as if the rejected call had not occurred.
         *
         * @param bytes the maximum total bytes the cache may hold; must be positive
         * @return this builder for fluent chaining
         * @throws IllegalArgumentException if {@code bytes <= 0}; on rejection the builder state is
         *             unchanged
         */
        // @spec sstable.byte-budget-block-cache.R1,R2 — byteBudget setter with transactional
        // semantics
        public Builder byteBudget(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("byteBudget must be positive, got: " + bytes);
            }
            this.byteBudget = bytes;
            return this;
        }

        /**
         * Builds a new {@link LruBlockCache} with the configured byte budget.
         *
         * @return a new {@code LruBlockCache} instance
         * @throws IllegalArgumentException if byteBudget was never set (R3)
         */
        // @spec sstable.byte-budget-block-cache.R3,R28 — require byteBudget to be set; unlike the
        // F09 entry-count form, byteBudget may exceed Integer.MAX_VALUE
        public LruBlockCache build() {
            if (byteBudget <= 0) {
                throw new IllegalArgumentException(
                        "byteBudget not set — call .byteBudget(n) before .build()");
            }
            return new LruBlockCache(this);
        }
    }
}

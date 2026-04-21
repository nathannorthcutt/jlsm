package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * An LRU block cache backed by a {@link LinkedHashMap} with access-order eviction.
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

    private final long capacity;
    // @spec sstable.striped-block-cache.R34,R36 — each stripe holds its own independent lock; concurrent operations on
    // different stripes never contend on the same monitor
    private final ReentrantLock lock;
    private final LinkedHashMap<CacheKey, MemorySegment> map;
    private volatile boolean closed;

    private LruBlockCache(Builder builder) {
        if (builder.capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive, got: " + builder.capacity);
        }
        this.capacity = builder.capacity;
        this.lock = new ReentrantLock();
        final long cap = this.capacity;
        // @spec sstable.striped-block-cache.R15 — each stripe's LRU eviction fires when per-stripe capacity is exceeded
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, MemorySegment> eldest) {
                return size() > cap;
            }
        };
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
            map.put(new CacheKey(sstableId, blockOffset), block);
        } finally {
            lock.unlock();
        }
    }

    // @spec sstable.striped-block-cache.R37,R47 — release the stripe lock before calling loader.get() (R37);
    // double-checked locking ensures concurrent callers observe the same
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

        // Load outside the lock — avoids blocking all cache operations during I/O
        var block = loader.get();
        Objects.requireNonNull(block, "loader must not return null");

        // @spec sstable.striped-block-cache.R47 — double-checked locking: if another thread committed a value during our
        // load, return it and discard ours; either way, all callers observe the
        // same cached reference
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            var existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            map.put(key, block);
            return block;
        } finally {
            lock.unlock();
        }
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
            map.keySet().removeIf(k -> k.sstableId() == sstableId);
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

    @Override
    public long capacity() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return capacity;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            map.clear();
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
        private long capacity = -1;

        private Builder() {
        }

        // @spec sstable.striped-block-cache.R44 — eager rejection of capacity <= 0 at the setter call
        public Builder capacity(long capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        // @spec sstable.striped-block-cache.R43 — reject capacity exceeding Integer.MAX_VALUE (LinkedHashMap int size())
        public LruBlockCache build() {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
            }
            if (capacity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("capacity must not exceed Integer.MAX_VALUE ("
                        + Integer.MAX_VALUE
                        + ") because the backing LinkedHashMap uses int size(); got: " + capacity);
            }
            return new LruBlockCache(this);
        }
    }
}

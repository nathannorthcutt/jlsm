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
    private final ReentrantLock lock;
    private final LinkedHashMap<CacheKey, MemorySegment> map;
    private volatile boolean closed;

    private LruBlockCache(Builder builder) {
        assert builder.capacity > 0 : "capacity must be positive";
        this.capacity = builder.capacity;
        this.lock = new ReentrantLock();
        final long cap = this.capacity;
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
            map.put(new CacheKey(sstableId, blockOffset), block);
        } finally {
            lock.unlock();
        }
    }

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
        lock.lock();
        try {
            var key = new CacheKey(sstableId, blockOffset);
            var existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            var block = loader.get();
            Objects.requireNonNull(block, "loader must not return null");
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
            map.keySet().removeIf(k -> k.sstableId() == sstableId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public void close() {
        closed = true;
        lock.lock();
        try {
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
    public static StripedBlockCache.Builder getMultiThreaded() {
        return StripedBlockCache.builder();
    }

    /**
     * Returns a {@link Builder} for constructing a single-lock LRU cache suitable for
     * single-threaded or low-contention workloads. Equivalent to {@link #builder()}.
     *
     * @return a new {@link Builder}
     */
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

        public Builder capacity(long capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

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

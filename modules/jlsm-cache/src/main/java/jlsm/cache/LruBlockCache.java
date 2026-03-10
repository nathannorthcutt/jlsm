package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An LRU block cache backed by a {@link LinkedHashMap} with access-order eviction.
 *
 * <p>All operations are serialised through a single {@link ReentrantLock}. A
 * {@code ReadWriteLock} cannot be used here because {@link LinkedHashMap#get} with
 * {@code accessOrder=true} modifies internal ordering and is therefore a write-equivalent
 * operation.
 *
 * <p>Obtain instances via {@link #builder()}.
 */
public final class LruBlockCache implements BlockCache {

    private record CacheKey(long sstableId, long blockOffset) {}

    private final long capacity;
    private final ReentrantLock lock;
    private final LinkedHashMap<CacheKey, MemorySegment> map;

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
        if (blockOffset < 0) {
            throw new IllegalArgumentException("blockOffset must be non-negative, got: " + blockOffset);
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
        if (blockOffset < 0) {
            throw new IllegalArgumentException("blockOffset must be non-negative, got: " + blockOffset);
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
    public void evict(long sstableId) {
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
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long capacity = -1;

        private Builder() {}

        public Builder capacity(long capacity) {
            this.capacity = capacity;
            return this;
        }

        public LruBlockCache build() {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
            }
            return new LruBlockCache(this);
        }
    }
}

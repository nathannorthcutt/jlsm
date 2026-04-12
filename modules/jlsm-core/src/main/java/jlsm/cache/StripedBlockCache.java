package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link BlockCache} implementation that partitions the key space across N independent
 * {@link LruBlockCache} stripes, each with its own lock, to eliminate single-lock contention under
 * concurrent access.
 *
 * <p>
 * Stripe selection uses the splitmix64 finalizer (Stafford variant 13) to hash
 * {@code (sstableId, blockOffset)} pairs to a stripe index with excellent avalanche properties. See
 * {@code .decisions/stripe-hash-function/adr.md}.
 *
 * <p>
 * Cross-stripe eviction iterates all stripes sequentially, calling {@code evict()} on each. See
 * {@code .decisions/cross-stripe-eviction/adr.md}.
 *
 * <p>
 * Obtain instances via {@link #builder()} or {@link LruBlockCache#getMultiThreaded()}.
 */
public final class StripedBlockCache implements BlockCache {

    static final int MAX_STRIPE_COUNT = 1024;

    private final LruBlockCache[] stripes;
    private final int stripeCount;
    private final int stripeMask;
    private final long capacity;
    private volatile boolean closed;

    private StripedBlockCache(Builder builder) {
        if (builder.stripeCount <= 0) {
            throw new IllegalArgumentException(
                    "stripeCount must be positive, got: " + builder.stripeCount);
        }

        // Round up to the nearest power of 2 for bitmask optimization
        final int effectiveStripeCount = Integer
                .highestOneBit(builder.stripeCount) == builder.stripeCount ? builder.stripeCount
                        : Integer.highestOneBit(builder.stripeCount) << 1;

        if (builder.capacity < effectiveStripeCount) {
            throw new IllegalArgumentException("capacity must be >= effective stripeCount ("
                    + effectiveStripeCount + "), got capacity=" + builder.capacity);
        }

        this.stripeCount = effectiveStripeCount;
        this.stripeMask = effectiveStripeCount - 1;
        this.stripes = new LruBlockCache[stripeCount];

        final long perStripeCapacity = builder.capacity / stripeCount;
        this.capacity = perStripeCapacity * stripeCount;
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = LruBlockCache.builder().capacity(perStripeCapacity).build();
        }
    }

    /**
     * Computes the stripe index for the given SSTable ID and block offset using the splitmix64
     * finalizer (Stafford variant 13).
     *
     * <p>
     * Constants are from {@code java.util.SplittableRandom}. The golden-ratio multiply naturally
     * combines both inputs, and the three-stage multiply-XOR-shift chain provides full avalanche —
     * every input bit affects every output bit.
     *
     * @param sstableId the SSTable identifier
     * @param blockOffset the byte offset within the SSTable
     * @param stripeCount the number of stripes; must be positive
     * @return a stripe index in {@code [0, stripeCount)}
     */
    /**
     * Computes the stripe index for the given SSTable ID and block offset using the splitmix64
     * finalizer (Stafford variant 13).
     *
     * <p>
     * {@code stripeCount} must be a positive power of 2. The result is computed via bitmask
     * ({@code hash & (stripeCount - 1)}) rather than modulo for zero-division-cost stripe
     * selection. See {@code .decisions/power-of-two-stripe-optimization/adr.md}.
     *
     * @param sstableId the SSTable identifier
     * @param blockOffset the byte offset within the SSTable
     * @param stripeCount the number of stripes; must be a positive power of 2
     * @return a stripe index in {@code [0, stripeCount)}
     */
    static int stripeIndex(long sstableId, long blockOffset, int stripeCount) {
        if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException(
                    "stripeCount must be a positive power of 2, got: " + stripeCount);
        }

        // Splitmix64 finalizer — constants from java.util.SplittableRandom
        long h = sstableId * 0x9E3779B97F4A7C15L + blockOffset;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (int) (h & (stripeCount - 1));
    }

    /**
     * Fast-path stripe index computation using the pre-computed {@link #stripeMask}. Skips the
     * power-of-2 validation since it was enforced at construction time.
     */
    private int stripeFor(long sstableId, long blockOffset) {
        long h = sstableId * 0x9E3779B97F4A7C15L + blockOffset;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (int) (h & stripeMask);
    }

    /**
     * Returns the cached block for the given SSTable and offset, delegating to the appropriate
     * stripe determined by {@link #stripeIndex}.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @return an {@link Optional} containing the cached {@link MemorySegment}, or empty on a miss
     * @throws IllegalArgumentException if {@code blockOffset < 0}
     */
    @Override
    public Optional<MemorySegment> get(long sstableId, long blockOffset) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        int idx = stripeFor(sstableId, blockOffset);
        assert idx >= 0 && idx < stripeCount
                : "stripeIndex out of range: " + idx + " for stripeCount=" + stripeCount;
        try {
            return stripes[idx].get(sstableId, blockOffset);
        } catch (IllegalStateException e) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw e;
        }
    }

    /**
     * Inserts or replaces a block in the cache, delegating to the appropriate stripe determined by
     * {@link #stripeIndex}.
     *
     * @param sstableId the unique identifier of the SSTable containing the block
     * @param blockOffset the byte offset of the block within the SSTable file; must be non-negative
     * @param block the block data to cache; must not be null
     * @throws IllegalArgumentException if {@code blockOffset < 0}
     * @throws NullPointerException if {@code block} is null
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
        int idx = stripeFor(sstableId, blockOffset);
        assert idx >= 0 && idx < stripeCount
                : "stripeIndex out of range: " + idx + " for stripeCount=" + stripeCount;
        try {
            stripes[idx].put(sstableId, blockOffset, block);
        } catch (IllegalStateException e) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw e;
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
        int idx = stripeFor(sstableId, blockOffset);
        assert idx >= 0 && idx < stripeCount
                : "stripeIndex out of range: " + idx + " for stripeCount=" + stripeCount;
        try {
            return stripes[idx].getOrLoad(sstableId, blockOffset, loader);
        } catch (IllegalStateException e) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw e;
        }
    }

    /**
     * Removes all cached blocks belonging to the given SSTable by iterating all stripes
     * sequentially. Each stripe acquires and releases its own lock independently.
     *
     * <p>
     * Governed by {@code .decisions/cross-stripe-eviction/adr.md}: sequential loop, one lock held
     * at a time, momentary inconsistency during the sweep is acceptable.
     *
     * @param sstableId the unique identifier of the SSTable whose blocks should be evicted
     */
    @Override
    public void evict(long sstableId) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        try {
            for (LruBlockCache stripe : stripes) {
                stripe.evict(sstableId);
            }
        } catch (IllegalStateException e) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw e;
        }
    }

    /**
     * Returns the total number of blocks currently held across all stripes.
     *
     * @return current block count; always non-negative
     */
    @Override
    public long size() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        long total = 0;
        for (LruBlockCache stripe : stripes) {
            total += stripe.size();
        }
        return total;
    }

    /**
     * Returns the total capacity across all stripes.
     *
     * @return cache capacity; always positive
     */
    @Override
    public long capacity() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return capacity;
    }

    /**
     * Closes all stripes, accumulating exceptions via the deferred exception pattern. After this
     * call, behavior of all other methods is undefined.
     */
    @Override
    public void close() {
        closed = true;
        Throwable deferred = null;
        for (LruBlockCache stripe : stripes) {
            try {
                stripe.close();
            } catch (Throwable e) {
                if (deferred == null) {
                    deferred = e;
                } else {
                    deferred.addSuppressed(e);
                }
            }
        }
        if (deferred != null) {
            switch (deferred) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                default -> throw new RuntimeException(deferred);
            }
        }
    }

    /**
     * Returns a new {@link Builder} for constructing a {@code StripedBlockCache}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link StripedBlockCache}.
     *
     * <p>
     * Default stripe count is {@code min(Runtime.getRuntime().availableProcessors(), 16)}. Capacity
     * must be set explicitly and must be at least {@code stripeCount} (each stripe needs at least 1
     * entry of capacity).
     */
    public static final class Builder {

        private int stripeCount = Math.min(Runtime.getRuntime().availableProcessors(), 16);
        private long capacity = -1;

        private Builder() {
        }

        /**
         * Sets the number of independent stripes (shards).
         *
         * <p>
         * If the value is not a power of 2, it is rounded up to the next power of 2 for bitmask
         * optimization. See {@code .decisions/power-of-two-stripe-optimization/adr.md}.
         *
         * @param stripeCount the number of stripes; must be positive
         * @return this builder
         * @throws IllegalArgumentException if {@code stripeCount <= 0} or exceeds
         *             {@link StripedBlockCache#MAX_STRIPE_COUNT}
         */
        public Builder stripeCount(int stripeCount) {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException(
                        "stripeCount must be positive, got: " + stripeCount);
            }
            if (stripeCount > MAX_STRIPE_COUNT) {
                throw new IllegalArgumentException("stripeCount must not exceed " + MAX_STRIPE_COUNT
                        + ", got: " + stripeCount);
            }
            this.stripeCount = stripeCount;
            return this;
        }

        /**
         * Sets the total capacity across all stripes. Each stripe receives
         * {@code capacity / stripeCount} entries of capacity.
         *
         * @param capacity the total capacity; must be at least {@code stripeCount}
         * @return this builder
         */
        public Builder capacity(long capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        /**
         * Builds a new {@link StripedBlockCache} with the configured parameters.
         *
         * @return a new {@code StripedBlockCache} instance
         * @throws IllegalArgumentException if capacity is not set, {@code stripeCount <= 0}, or
         *             {@code capacity < stripeCount}
         */
        public StripedBlockCache build() {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException(
                        "stripeCount must be positive, got: " + stripeCount);
            }
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "capacity not set — call .capacity(n) before .build()");
            }
            if (capacity < stripeCount) {
                throw new IllegalArgumentException("capacity must be at least stripeCount ("
                        + stripeCount + "), got: " + capacity);
            }
            long perStripeCapacity = capacity / stripeCount;
            if (perStripeCapacity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "per-stripe capacity must not exceed Integer.MAX_VALUE ("
                                + Integer.MAX_VALUE
                                + ") because the backing LinkedHashMap uses int size(); got: "
                                + perStripeCapacity);
            }
            return new StripedBlockCache(this);
        }
    }
}

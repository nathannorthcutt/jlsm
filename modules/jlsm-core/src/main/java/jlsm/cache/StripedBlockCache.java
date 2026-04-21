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
// @spec sstable.striped-block-cache.R1 — StripedBlockCache implements BlockCache
public final class StripedBlockCache implements BlockCache {

    static final int MAX_STRIPE_COUNT = 1024;

    // @spec sstable.striped-block-cache.R34,R35,R36 — one LruBlockCache per stripe, each with its
    // own ReentrantLock, so
    // concurrent operations on different stripes never contend
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

        // @spec sstable.striped-block-cache.R9 — capacity = (configuredCapacity /
        // effectiveStripeCount) *
        // effectiveStripeCount
        final long perStripeCapacity = builder.capacity / stripeCount;
        this.capacity = perStripeCapacity * stripeCount;
        // @spec sstable.striped-block-cache.R15,R16 — each stripe is an independent LruBlockCache
        // with its own LRU
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = LruBlockCache.builder().capacity(perStripeCapacity).build();
        }
    }

    /**
     * Computes the stripe index for the given SSTable ID and block offset using the splitmix64
     * finalizer (Stafford variant 13).
     *
     * <p>
     * {@code stripeCount} must be a positive power of 2. The result is computed via bitmask
     * ({@code hash & (stripeCount - 1)}) rather than modulo for zero-division-cost stripe
     * selection. Constants are from {@code java.util.SplittableRandom}; the golden-ratio multiply
     * combines both inputs, and the three-stage multiply-XOR-shift chain provides full avalanche.
     * See {@code .decisions/power-of-two-stripe-optimization/adr.md}.
     *
     * @param sstableId the SSTable identifier
     * @param blockOffset the byte offset within the SSTable
     * @param stripeCount the number of stripes; must be a positive power of 2
     * @return a stripe index in {@code [0, stripeCount)}
     * @throws IllegalArgumentException if {@code stripeCount} is not a positive power of 2
     */
    // @spec sstable.striped-block-cache.R10,R11,R12,R13,R14,R45 — splitmix64 stripe index
    // (distribution emerges from the
    // finalizer's avalanche); package-private static;
    // rejects non-power-of-2 stripeCount with IAE
    static int stripeIndex(long sstableId, long blockOffset, int stripeCount) {
        // @spec sstable.striped-block-cache.R45 — non-power-of-2 stripeCount rejected (R10
        // precondition enforcement)
        if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException(
                    "stripeCount must be a positive power of 2, got: " + stripeCount);
        }

        // @spec sstable.striped-block-cache.R11 — Splitmix64 (Stafford variant 13) with
        // golden-ratio combining
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
    // @spec sstable.striped-block-cache.R2,R3,R25,R28 — get delegates to stripe; IAE on negative
    // offset; ISE after close
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
    // @spec sstable.striped-block-cache.R4,R26,R27,R29 — put delegates to stripe; IAE on negative
    // offset; NPE on null;
    // ISE after close
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

    // @spec sstable.striped-block-cache.R37,R38,R39,R40,R47 — getOrLoad releases stripe lock during
    // loader (R37);
    // IAE on negative offset (R38); NPE on null loader (R39);
    // ISE after close (R40); concurrent callers observe the same
    // reference (R47, enforced by LruBlockCache double-checked
    // locking in the delegate)
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
    // @spec sstable.striped-block-cache.R5,R30 — evict iterates all stripes sequentially; ISE after
    // close
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
    // @spec sstable.striped-block-cache.R6,R7,R8,R46 — sum of stripe sizes; non-negative and ≤
    // capacity by construction;
    // ISE after close
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
    // @spec sstable.striped-block-cache.R31,R32,R33 — close invokes close on every stripe (R32),
    // accumulating exceptions
    // via the deferred pattern (R31); safe on newly-constructed cache (R33)
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

        // @spec sstable.striped-block-cache.R23 — default stripeCount = min(availableProcessors,
        // 16)
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
        // @spec sstable.striped-block-cache.R17,R18,R19 — reject <= 0, reject > MAX_STRIPE_COUNT,
        // accept = MAX_STRIPE_COUNT
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
        // @spec sstable.striped-block-cache.R20 — eager rejection of capacity <= 0 at the setter
        // call
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
        // @spec sstable.striped-block-cache.R21,R22,R24 — reject capacity < stripeCount; reject
        // per-stripe capacity
        // > Integer.MAX_VALUE; reject missing capacity
        public StripedBlockCache build() {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException(
                        "stripeCount must be positive, got: " + stripeCount);
            }
            // @spec sstable.striped-block-cache.R24 — capacity must be set explicitly before build
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "capacity not set — call .capacity(n) before .build()");
            }
            // @spec sstable.striped-block-cache.R21 — capacity < stripeCount rejected
            if (capacity < stripeCount) {
                throw new IllegalArgumentException("capacity must be at least stripeCount ("
                        + stripeCount + "), got: " + capacity);
            }
            long perStripeCapacity = capacity / stripeCount;
            // @spec sstable.striped-block-cache.R22 — per-stripe capacity must fit LinkedHashMap's
            // int size()
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

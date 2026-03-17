package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;

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

    private final LruBlockCache[] stripes;
    private final int stripeCount;
    private final long capacity;

    private StripedBlockCache(Builder builder) {
        assert builder.stripeCount > 0 : "stripeCount must be positive";
        assert builder.capacity >= builder.stripeCount : "capacity must be >= stripeCount";

        this.stripeCount = builder.stripeCount;
        this.capacity = builder.capacity;
        this.stripes = new LruBlockCache[stripeCount];

        final long perStripeCapacity = capacity / stripeCount;
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
    static int stripeIndex(long sstableId, long blockOffset, int stripeCount) {
        assert stripeCount > 0 : "stripeCount must be positive";

        // Splitmix64 finalizer — constants from java.util.SplittableRandom
        long h = sstableId * 0x9E3779B97F4A7C15L + blockOffset;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (int) ((h & 0x7FFFFFFFFFFFFFFFL) % stripeCount);
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
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        return stripes[stripeIndex(sstableId, blockOffset, stripeCount)].get(sstableId,
                blockOffset);
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
        if (blockOffset < 0) {
            throw new IllegalArgumentException(
                    "blockOffset must be non-negative, got: " + blockOffset);
        }
        Objects.requireNonNull(block, "block must not be null");
        stripes[stripeIndex(sstableId, blockOffset, stripeCount)].put(sstableId, blockOffset,
                block);
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
        for (LruBlockCache stripe : stripes) {
            stripe.evict(sstableId);
        }
    }

    /**
     * Returns the total number of blocks currently held across all stripes.
     *
     * @return current block count; always non-negative
     */
    @Override
    public long size() {
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
        return capacity;
    }

    /**
     * Closes all stripes, accumulating exceptions via the deferred exception pattern. After this
     * call, behavior of all other methods is undefined.
     */
    @Override
    public void close() {
        RuntimeException deferred = null;
        for (LruBlockCache stripe : stripes) {
            try {
                stripe.close();
            } catch (RuntimeException e) {
                if (deferred == null) {
                    deferred = e;
                } else {
                    deferred.addSuppressed(e);
                }
            }
        }
        if (deferred != null) {
            throw deferred;
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
         * @param stripeCount the number of stripes; must be positive
         * @return this builder
         * @throws IllegalArgumentException if {@code stripeCount <= 0}
         */
        public Builder stripeCount(int stripeCount) {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException(
                        "stripeCount must be positive, got: " + stripeCount);
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
            if (capacity < stripeCount) {
                throw new IllegalArgumentException("capacity must be at least stripeCount ("
                        + stripeCount + "), got: " + capacity);
            }
            return new StripedBlockCache(this);
        }
    }
}

package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
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

    /**
     * Default minimum expected block size hint — one local SSTable block (4 KiB). Deployments with
     * larger block sizes (for example 8 MiB for remote backends) should configure this explicitly
     * via {@link Builder#expectedMinimumBlockSize(long)}.
     */
    static final long DEFAULT_EXPECTED_MINIMUM_BLOCK_SIZE = 4096L;

    // @spec sstable.striped-block-cache.R34,R35,R36 — one LruBlockCache per stripe, each with its
    // own ReentrantLock, so concurrent operations on different stripes never contend
    private final LruBlockCache[] stripes;
    private final int stripeCount;
    private final int stripeMask;
    private final long effectiveCapacity;
    private volatile boolean closed;

    // Package-private test seam (F-R1.resource_lifecycle.2.1): when non-null, the constructor's
    // stripe-construction loop delegates per-stripe creation to this factory so adversarial tests
    // can deterministically force a mid-loop failure and verify that previously constructed
    // stripes are closed (not leaked). MUST remain null in production; set only from test code in
    // the same package, and always reset in a finally block.
    static volatile LongFunction<LruBlockCache> testStripeFactory;

    private StripedBlockCache(Builder builder) {
        // Runtime checks (not asserts): reflective callers that bypass Builder.build() still get
        // IllegalArgumentException under -da. Documented by SharedStateAdversarialTest findings
        // F-R1.shared_state.1.2 (stripeCount) and regression watch for byteBudget.
        if (builder.stripeCount <= 0) {
            throw new IllegalArgumentException(
                    "stripeCount must be positive, got: " + builder.stripeCount);
        }
        // Finding F-R1.dispatch_routing.1.2: re-validate the MAX_STRIPE_COUNT upper bound here so
        // that a reflective caller that writes Builder.stripeCount directly (bypassing the setter
        // and build()) cannot drive roundUpToPowerOfTwo into integer overflow, which would
        // otherwise surface as a NegativeArraySizeException or a corrupt negative stripeMask.
        if (builder.stripeCount > MAX_STRIPE_COUNT) {
            throw new IllegalArgumentException("stripeCount must not exceed " + MAX_STRIPE_COUNT
                    + ", got: " + builder.stripeCount);
        }
        // Finding F-R1.contract_boundaries.4.2: when byteBudget is still at the -1L sentinel
        // (setter never called), emit the same "not set" diagnostic that build() emits rather
        // than echoing the sentinel value via the "byteBudget must be >= effective stripeCount"
        // path below.
        if (builder.byteBudget == -1L) {
            throw new IllegalArgumentException(
                    "byteBudget not set — call .byteBudget(n) before .build()");
        }
        final int effectiveStripeCount = roundUpToPowerOfTwo(builder.stripeCount);
        if (builder.byteBudget < effectiveStripeCount) {
            throw new IllegalArgumentException("byteBudget must be >= effective stripeCount ("
                    + effectiveStripeCount + "), got byteBudget=" + builder.byteBudget);
        }

        this.stripeCount = effectiveStripeCount;
        this.stripeMask = effectiveStripeCount - 1;
        this.stripes = new LruBlockCache[stripeCount];

        // @spec sstable.byte-budget-block-cache.R22,R23 — per-stripe budget is the integer
        // quotient; capacity() reports the re-multiplied (truncated) total byte budget
        final long perStripeByteBudget = builder.byteBudget / stripeCount;
        this.effectiveCapacity = perStripeByteBudget * stripeCount;
        // @spec sstable.byte-budget-block-cache.R22,R24 — each stripe is an independent
        // LruBlockCache sized by the per-stripe byte budget; a single entry exceeding the
        // per-stripe budget triggers R11 oversized-entry behavior within its stripe
        // Finding F-R1.resource_lifecycle.2.1: if stripe construction throws mid-loop (e.g.,
        // OutOfMemoryError while allocating the N-th stripe), close any already-constructed
        // stripes before propagating the failure. Without this cleanup the partially-populated
        // stripes array becomes unreachable but the individual stripe instances are orphaned
        // without a close() call, leaking any internal state (maps, locks, cached segments).
        final LongFunction<LruBlockCache> factory = testStripeFactory;
        int constructed = 0;
        try {
            for (int i = 0; i < stripeCount; i++) {
                stripes[i] = (factory != null) ? factory.apply(perStripeByteBudget)
                        : LruBlockCache.builder().byteBudget(perStripeByteBudget).build();
                constructed = i + 1;
            }
        } catch (Throwable primary) {
            for (int j = 0; j < constructed; j++) {
                try {
                    stripes[j].close();
                } catch (Throwable inner) {
                    primary.addSuppressed(inner);
                }
            }
            throw primary;
        }
    }

    /**
     * Rounds {@code value} up to the next power of 2. Assumes {@code value > 0}; if {@code value}
     * is already a power of 2 it is returned unchanged.
     */
    private static int roundUpToPowerOfTwo(int value) {
        assert value > 0 : "value must be positive, got: " + value;
        return Integer.highestOneBit(value) == value ? value : Integer.highestOneBit(value) << 1;
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
    // (distribution emerges from the finalizer's avalanche); package-private static;
    // rejects non-power-of-2 stripeCount with IAE
    static int stripeIndex(long sstableId, long blockOffset, int stripeCount) {
        // @spec sstable.striped-block-cache.R45 — non-power-of-2 stripeCount rejected (R10
        // precondition enforcement)
        if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException(
                    "stripeCount must be a positive power of 2, got: " + stripeCount);
        }
        return (int) (splitmix64Hash(sstableId, blockOffset) & (stripeCount - 1));
    }

    /**
     * Fast-path stripe index computation using the pre-computed {@link #stripeMask}. Skips the
     * power-of-2 validation since it was enforced at construction time.
     */
    private int stripeFor(long sstableId, long blockOffset) {
        return (int) (splitmix64Hash(sstableId, blockOffset) & stripeMask);
    }

    /**
     * Splitmix64 finalizer (Stafford variant 13) combining two {@code long} inputs via the
     * golden-ratio multiplier plus a non-linear pre-avalanche round. Produces full 64-bit
     * avalanche; caller masks to the stripe range.
     *
     * <p>
     * The pre-avalanche multiply-XOR-shift on {@code sstableId * G} (before adding
     * {@code blockOffset}) defeats the linear algebraic pre-image collisions that a pure
     * {@code sstableId * G + blockOffset} combine would otherwise admit — namely, pairs satisfying
     * {@code (s1-s2)*G ≡ o2-o1 (mod 2^64)} no longer collapse to the same combined input.
     */
    // @spec sstable.striped-block-cache.R11 (v4) — Splitmix64 (Stafford variant 13) applied to
    // a combined input that uses a non-linear pre-avalanche round to defeat algebraic
    // pre-image collisions. See audit finding F-R1.data_transformation.1.1.
    private static long splitmix64Hash(long sstableId, long blockOffset) {
        // R11 (v4): pre-avalanche non-linear combine to defeat algebraic pre-image collisions
        long a = sstableId * 0x9E3779B97F4A7C15L;
        a = (a ^ (a >>> 30)) * 0xBF58476D1CE4E5B9L;
        long h = a + blockOffset;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
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
    // offset; NPE on null; ISE after close
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
    // loader (R37); IAE on negative offset (R38); NPE on null loader (R39); ISE after close (R40);
    // concurrent callers observe the same reference (R47, enforced by LruBlockCache
    // double-checked locking in the delegate)
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
    // @spec sstable.striped-block-cache.R5,R30,R42 — evict iterates all stripes sequentially
    // (fan-out); ISE after close; deferred-exception pattern mirrors close() so a single stripe
    // failure cannot leave blocks cached on later stripes (F-R1.dispatch_routing.1.1)
    @Override
    public void evict(long sstableId) {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        RuntimeException deferred = null;
        for (LruBlockCache stripe : stripes) {
            try {
                stripe.evict(sstableId);
            } catch (RuntimeException e) {
                if (deferred == null) {
                    deferred = e;
                } else {
                    deferred.addSuppressed(e);
                }
            }
        }
        if (deferred != null) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw deferred;
        }
    }

    /**
     * Returns the total number of blocks currently held across all stripes.
     *
     * @return current block count; always non-negative
     */
    // @spec sstable.striped-block-cache.R6,R7,R46 — sum of stripe sizes; non-negative by
    // construction; ISE after close
    @Override
    public long size() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        try {
            long total = 0;
            for (LruBlockCache stripe : stripes) {
                total += stripe.size();
            }
            return total;
        } catch (IllegalStateException e) {
            if (closed) {
                throw new IllegalStateException("cache is closed");
            }
            throw e;
        }
    }

    /**
     * Returns the effective total byte budget across all stripes.
     *
     * <p>
     * <b>Truncation (sstable.byte-budget-block-cache v3 R23):</b> computed as
     * {@code (totalByteBudget / effectiveStripeCount) * effectiveStripeCount}. When
     * {@code totalByteBudget} is not evenly divisible by the effective (power-of-2-rounded) stripe
     * count, integer division truncates and the reported capacity may be less than the configured
     * {@link Builder#byteBudget(long)} value.
     *
     * @return effective byte budget; always positive
     */
    // @spec sstable.byte-budget-block-cache.R23 — effective byte budget after truncation
    @Override
    public long capacity() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return effectiveCapacity;
    }

    /**
     * Returns the configured byte budget (per sstable.byte-budget-block-cache v3 R14, parallel to
     * LruBlockCache). Same as {@link #capacity()} modulo the R23 truncation — this accessor returns
     * the effective truncated byte budget, consistent with the per-stripe configuration.
     *
     * @return configured byte budget, always positive
     * @throws IllegalStateException if the cache is closed (R31)
     */
    // @spec sstable.byte-budget-block-cache.R14,R31 — byteBudget accessor; ISE after close
    public long byteBudget() {
        if (closed) {
            throw new IllegalStateException("cache is closed");
        }
        return effectiveCapacity;
    }

    /**
     * Closes all stripes, accumulating exceptions via the deferred exception pattern. After this
     * call, behavior of all other methods is undefined.
     */
    // @spec sstable.striped-block-cache.R31,R32,R33 — close invokes close on every stripe (R32),
    // accumulating exceptions via the deferred pattern (R31); safe on newly-constructed cache
    // (R33); double-close is idempotent via per-stripe idempotent close (byte-budget R16)
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
     * Default stripe count is {@code min(Runtime.getRuntime().availableProcessors(), 16)}. The byte
     * budget must be set explicitly and must be at least {@code stripeCount} (each stripe needs a
     * positive byte budget).
     */
    public static final class Builder {

        // @spec sstable.striped-block-cache.R23 — default stripeCount = min(availableProcessors,
        // 16)
        private int stripeCount = Math.min(Runtime.getRuntime().availableProcessors(), 16);
        private long byteBudget = -1L;
        private long expectedMinimumBlockSize = DEFAULT_EXPECTED_MINIMUM_BLOCK_SIZE;

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
         * Configures the total byte budget for this striped cache.
         *
         * <p>
         * <b>Transactional setter (R18):</b> this setter validates {@code bytes} before mutating
         * any builder state. If validation fails the builder is left unchanged and a subsequent
         * call with a valid value succeeds as if the rejected call had not occurred.
         *
         * @param bytes must be positive; {@code >=} effective stripe count at {@link #build} (R20);
         *            per-stripe budget ({@code bytes / effectiveStripeCount}) must be
         *            {@code >= expectedMinimumBlockSize} at build (R20a)
         * @return this builder for fluent chaining
         * @throws IllegalArgumentException if {@code bytes <= 0}; on rejection the builder state is
         *             unchanged
         */
        // @spec sstable.byte-budget-block-cache.R17,R18 — transactional byteBudget setter
        public Builder byteBudget(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("byteBudget must be positive, got: " + bytes);
            }
            this.byteBudget = bytes;
            return this;
        }

        /**
         * Sets the minimum block size hint for per-stripe budget validation.
         *
         * <p>
         * Defaults to {@value StripedBlockCache#DEFAULT_EXPECTED_MINIMUM_BLOCK_SIZE} bytes (one
         * local SSTable block). Deployments with larger block sizes (for example 8 MiB remote)
         * should configure this explicitly so that the build-time validation rejects configurations
         * whose per-stripe byte budget cannot hold at least one block.
         *
         * <p>
         * <b>Transactional setter (R20b):</b> this setter validates {@code bytes} before mutating
         * any builder state. If validation fails the builder is left unchanged and a subsequent
         * call with a valid value succeeds as if the rejected call had not occurred.
         *
         * @param bytes minimum expected block size; must be positive
         * @return this builder for fluent chaining
         * @throws IllegalArgumentException if {@code bytes <= 0}; on rejection the builder state is
         *             unchanged
         */
        // @spec sstable.byte-budget-block-cache.R20a,R20b — expectedMinimumBlockSize setter with
        // transactional semantics
        public Builder expectedMinimumBlockSize(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException(
                        "expectedMinimumBlockSize must be positive, got: " + bytes);
            }
            this.expectedMinimumBlockSize = bytes;
            return this;
        }

        /**
         * Builds a new {@link StripedBlockCache} with the configured parameters.
         *
         * @return a new {@code StripedBlockCache} instance
         * @throws IllegalArgumentException if byteBudget is not set, {@code stripeCount <= 0},
         *             {@code byteBudget < effectiveStripeCount}, or per-stripe byte budget is below
         *             {@code expectedMinimumBlockSize}
         */
        // @spec sstable.byte-budget-block-cache.R19,R20,R20a — require byteBudget to be set;
        // reject byteBudget < effectiveStripeCount; reject per-stripe below
        // expectedMinimumBlockSize
        // @spec sstable.striped-block-cache.R18 — build() re-enforces MAX_STRIPE_COUNT so that
        // a reflective-bypass caller that writes the stripeCount field directly (without going
        // through the setter) still gets the documented IllegalArgumentException rather than a
        // downstream NegativeArraySizeException from roundUpToPowerOfTwo overflow.
        public StripedBlockCache build() {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException(
                        "stripeCount must be positive, got: " + stripeCount);
            }
            if (stripeCount > MAX_STRIPE_COUNT) {
                throw new IllegalArgumentException("stripeCount must not exceed " + MAX_STRIPE_COUNT
                        + ", got: " + stripeCount);
            }
            // @spec sstable.byte-budget-block-cache.R19 — byteBudget must be set explicitly before
            // build. The error message must indicate that byteBudget was never set rather than
            // echoing the sentinel value.
            if (byteBudget <= 0) {
                throw new IllegalArgumentException(
                        "byteBudget not set — call .byteBudget(n) before .build()");
            }
            final int effectiveStripeCount = roundUpToPowerOfTwo(stripeCount);
            // @spec sstable.byte-budget-block-cache.R20 — byteBudget < effectiveStripeCount
            // rejected so every stripe receives at least 1 byte
            if (byteBudget < effectiveStripeCount) {
                throw new IllegalArgumentException("byteBudget must be at least effective "
                        + "stripeCount (" + effectiveStripeCount + "), got: " + byteBudget);
            }
            final long perStripeByteBudget = byteBudget / effectiveStripeCount;
            // @spec sstable.byte-budget-block-cache.R20a — per-stripe byte budget must be at least
            // expectedMinimumBlockSize so each stripe can hold at least one block without
            // triggering R11 oversized-entry behavior on every insertion
            if (perStripeByteBudget < expectedMinimumBlockSize) {
                throw new IllegalArgumentException("per-stripe byte budget (" + perStripeByteBudget
                        + " = " + byteBudget + " / " + effectiveStripeCount + ") is below "
                        + "expectedMinimumBlockSize (" + expectedMinimumBlockSize + "); "
                        + "either raise byteBudget, lower stripeCount, or lower "
                        + "expectedMinimumBlockSize");
            }
            return new StripedBlockCache(this);
        }
    }
}

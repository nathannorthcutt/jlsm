package jlsm.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for StripedBlockCache and LruBlockCache. Targets contract gaps and
 * implementation risks identified in spec-analysis.md.
 *
 * <p>
 * Migrated to the byte-budget API (sstable.byte-budget-block-cache v3). Tests that exercise
 * per-stripe eviction use 1-byte segments so the byte budget maps 1:1 to entry counts, preserving
 * the semantics the original entry-count tests asserted.
 */
class StripedBlockCacheAdversarialTest {

    private static final long NO_EVICTION_BUDGET = 1L << 20;

    // --- Finding 1: CAPACITY-TRUNCATION ---
    // When byteBudget is not evenly divisible by effectiveStripeCount, integer division
    // truncates the remainder. capacity() reports the effective (truncated) value.

    // @spec sstable.byte-budget-block-cache.R23
    @Test
    void capacityReportsEffectiveNotConfiguredWhenTruncated() {
        // byteBudget=16387, stripeCount=4 → per-stripe=4096 (R20a default minimum satisfied),
        // effective=16384. capacity() should report 16384, not 16387.
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(16_387L).build()) {
            assertEquals(16_384L, cache.capacity(),
                    "capacity() should report effective byte budget "
                            + "(perStripeByteBudget * stripeCount), not the configured value when "
                            + "truncation occurs");
        }
    }

    // @spec sstable.byte-budget-block-cache.R22,R23
    @Test
    void capacityTruncationDoesNotCausePrematureEviction() {
        // byteBudget=10, stripeCount=4 → per-stripe=2, effective=8. Use 1-byte segments and a
        // custom expectedMinimumBlockSize so the build-time validation passes.
        try (var cache = StripedBlockCache.builder().stripeCount(4).expectedMinimumBlockSize(1L)
                .byteBudget(10L).build()) {
            int inserted = 0;
            int[] perStripeCount = new int[4];
            for (long offset = 0; inserted < 8; offset++) {
                int stripe = StripedBlockCache.stripeIndex(1L, offset, 4);
                if (perStripeCount[stripe] < 2) {
                    cache.put(1L, offset, MemorySegment.ofArray(new byte[]{ (byte) offset }));
                    perStripeCount[stripe]++;
                    inserted++;
                }
            }
            assertEquals(8, cache.size(),
                    "8 entries (2 per stripe with per-stripe budget 2 bytes) should all fit "
                            + "without eviction");
        }
    }

    // @spec sstable.byte-budget-block-cache.R22,R10 — per-stripe byte-budget eviction
    @Test
    void effectiveCapacityMatchesActualMaxEntries() {
        // byteBudget=5, stripeCount=2 → per-stripe=2 bytes, effective=4 bytes.
        // 1-byte segments → 2 per stripe, total 4. 5th entry triggers per-stripe eviction.
        try (var cache = StripedBlockCache.builder().stripeCount(2).expectedMinimumBlockSize(1L)
                .byteBudget(5L).build()) {
            long[] stripe0Keys = new long[2];
            long[] stripe1Keys = new long[2];
            int s0 = 0, s1 = 0;
            for (long offset = 0; s0 < 2 || s1 < 2; offset++) {
                int stripe = StripedBlockCache.stripeIndex(1L, offset, 2);
                if (stripe == 0 && s0 < 2) {
                    stripe0Keys[s0++] = offset;
                } else if (stripe == 1 && s1 < 2) {
                    stripe1Keys[s1++] = offset;
                }
            }

            for (long key : stripe0Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 1 }));
            }
            for (long key : stripe1Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 2 }));
            }
            assertEquals(4, cache.size(), "4 entries (2 per stripe) should fit");

            long extraKey = -1;
            for (long offset = 0; offset < 10000; offset++) {
                if (StripedBlockCache.stripeIndex(1L, offset, 2) == 0 && offset != stripe0Keys[0]
                        && offset != stripe0Keys[1]) {
                    extraKey = offset;
                    break;
                }
            }
            assert extraKey >= 0 : "should find a key hashing to stripe 0";
            cache.put(1L, extraKey, MemorySegment.ofArray(new byte[]{ 3 }));

            assertEquals(4, cache.size(),
                    "effective byte budget is 4 with 1-byte entries — 5th entry triggers "
                            + "per-stripe eviction");
        }
    }

    // --- byteBudget may exceed Integer.MAX_VALUE (R28) ---

    // @spec sstable.byte-budget-block-cache.R28 — byte budget is long-valued; Integer.MAX_VALUE
    // is NOT a ceiling for byteBudget (the former entry-count cap no longer applies)
    @Test
    void lruBlockCacheAcceptsByteBudgetExceedingIntRange() {
        assertDoesNotThrow(
                () -> LruBlockCache.builder().byteBudget((long) Integer.MAX_VALUE + 1L).build()
                        .close(),
                "byteBudget beyond Integer.MAX_VALUE is valid under R28 — byte budgets are long-"
                        + "valued and the Integer.MAX_VALUE guard from the F09 entry-count form is "
                        + "no longer applicable");
    }

    // @spec sstable.byte-budget-block-cache.R28
    @Test
    void stripedBlockCacheAcceptsByteBudgetExceedingIntRange() {
        // Single stripe avoids the R20a expectedMinimumBlockSize floor clashing with the big
        // budget; the cache accepts a byteBudget beyond Integer.MAX_VALUE without complaint.
        assertDoesNotThrow(
                () -> StripedBlockCache.builder().stripeCount(1)
                        .byteBudget((long) Integer.MAX_VALUE + 1L).build().close(),
                "striped per-stripe byteBudget beyond Integer.MAX_VALUE is valid under R28");
    }

    // @spec sstable.byte-budget-block-cache.R2,R18 — byteBudget of Integer.MAX_VALUE accepted
    @Test
    void lruBlockCacheAcceptsMaxIntByteBudget() {
        assertDoesNotThrow(
                () -> LruBlockCache.builder().byteBudget(Integer.MAX_VALUE).build().close(),
                "byteBudget of Integer.MAX_VALUE should be accepted");
    }

    // --- getOrLoad atomicity — concurrent callers should not duplicate loader ---

    // @spec sstable.striped-block-cache.R37,R47
    @Test
    void getOrLoadReturnsConsistentValueUnderConcurrency() throws InterruptedException {
        try (var cache = LruBlockCache.builder().byteBudget(NO_EVICTION_BUDGET).build()) {
            var loaderCount = new AtomicInteger(0);
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(2);
            var results = new MemorySegment[2];

            for (int i = 0; i < 2; i++) {
                final int idx = i;
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        results[idx] = cache.getOrLoad(1L, 0L, () -> {
                            loaderCount.incrementAndGet();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return MemorySegment.ofArray(new byte[]{ 42 });
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            doneLatch.await();

            assertTrue(loaderCount.get() >= 1 && loaderCount.get() <= 2,
                    "loader may be invoked 1-2 times (lock released during load), got: "
                            + loaderCount.get());
            assertNotNull(results[0], "first caller must receive a result");
            assertNotNull(results[1], "second caller must receive a result");
            assertEquals((byte) 42, results[0].get(ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 42, results[1].get(ValueLayout.JAVA_BYTE, 0));
            assertSame(results[0], results[1],
                    "concurrent getOrLoad callers must observe the same cached reference");
            assertSame(results[0], cache.get(1L, 0L).orElseThrow(),
                    "the cached value must equal what callers observed");
        }
    }

    // @spec sstable.striped-block-cache.R37,R47
    @Test
    void stripedGetOrLoadReturnsConsistentValueUnderConcurrency() throws InterruptedException {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            var loaderCount = new AtomicInteger(0);
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(2);
            var results = new MemorySegment[2];

            for (int i = 0; i < 2; i++) {
                final int idx = i;
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        results[idx] = cache.getOrLoad(1L, 0L, () -> {
                            loaderCount.incrementAndGet();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return MemorySegment.ofArray(new byte[]{ 42 });
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            doneLatch.await();

            assertTrue(loaderCount.get() >= 1 && loaderCount.get() <= 2,
                    "loader may be invoked 1-2 times (lock released during load), got: "
                            + loaderCount.get());
            assertNotNull(results[0], "first caller must receive a result");
            assertNotNull(results[1], "second caller must receive a result");
            assertEquals((byte) 42, results[0].get(ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 42, results[1].get(ValueLayout.JAVA_BYTE, 0));
            assertSame(results[0], results[1],
                    "concurrent getOrLoad callers must observe the same cached reference");
            assertSame(results[0], cache.get(1L, 0L).orElseThrow(),
                    "the cached value must equal what callers observed");
        }
    }

    // --- Builder byteBudget() should validate eagerly ---

    // @spec sstable.byte-budget-block-cache.R2
    @Test
    void lruBuilderByteBudgetRejectsNegativeEagerly() {
        assertThrows(IllegalArgumentException.class, () -> LruBlockCache.builder().byteBudget(-1),
                "byteBudget setter should validate eagerly — negative value must be rejected "
                        + "immediately");
    }

    // @spec sstable.byte-budget-block-cache.R2
    @Test
    void lruBuilderByteBudgetRejectsZeroEagerly() {
        assertThrows(IllegalArgumentException.class, () -> LruBlockCache.builder().byteBudget(0),
                "byteBudget setter should validate eagerly — zero value must be rejected "
                        + "immediately");
    }

    // @spec sstable.byte-budget-block-cache.R18
    @Test
    void stripedBuilderByteBudgetRejectsNegativeEagerly() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().byteBudget(-1),
                "byteBudget setter should validate eagerly — negative value must be rejected "
                        + "immediately");
    }

    // @spec sstable.byte-budget-block-cache.R18
    @Test
    void stripedBuilderByteBudgetRejectsZeroEagerly() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().byteBudget(0),
                "byteBudget setter should validate eagerly — zero value must be rejected "
                        + "immediately");
    }

    // --- Use-after-close detection ---

    // @spec sstable.striped-block-cache.R29
    @Test
    void lruPutAfterCloseThrows() {
        var cache = LruBlockCache.builder().byteBudget(NO_EVICTION_BUDGET).build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 })),
                "put on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec sstable.striped-block-cache.R28
    @Test
    void lruGetAfterCloseThrows() {
        var cache = LruBlockCache.builder().byteBudget(NO_EVICTION_BUDGET).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get(1L, 0L),
                "get on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec sstable.striped-block-cache.R29
    @Test
    void stripedPutAfterCloseThrows() {
        var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(NO_EVICTION_BUDGET)
                .build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 })),
                "put on a closed StripedBlockCache should throw IllegalStateException");
    }

    // @spec sstable.striped-block-cache.R28
    @Test
    void stripedGetAfterCloseThrows() {
        var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(NO_EVICTION_BUDGET)
                .build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get(1L, 0L),
                "get on a closed StripedBlockCache should throw IllegalStateException");
    }

    // @spec sstable.striped-block-cache.R30
    @Test
    void lruEvictAfterCloseThrows() {
        var cache = LruBlockCache.builder().byteBudget(NO_EVICTION_BUDGET).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.evict(1L),
                "evict on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec sstable.striped-block-cache.R30
    @Test
    void stripedEvictAfterCloseThrows() {
        var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(NO_EVICTION_BUDGET)
                .build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.evict(1L),
                "evict on a closed StripedBlockCache should throw IllegalStateException");
    }

    // --- size() never negative, never exceeds capacity when uniformly sized ---

    // @spec sstable.striped-block-cache.R7
    @Test
    void sizeNeverNegativeAfterEvict() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            cache.evict(999L);
            assertTrue(cache.size() >= 0,
                    "size() must never return negative, even after evicting from empty cache");
        }
    }

    // @spec sstable.byte-budget-block-cache.R12 — byte-budget invariant with uniformly-sized
    // entries
    @Test
    void sizeNeverExceedsCapacity() {
        // With 1-byte entries, byte budget == max entries in aggregate; size() must never exceed
        // capacity() under uniform insertion.
        try (var cache = StripedBlockCache.builder().stripeCount(2).expectedMinimumBlockSize(1L)
                .byteBudget(4L).build()) {
            for (int i = 0; i < 20; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
                assertTrue(cache.size() <= cache.capacity(),
                        "size() must never exceed capacity(); size=" + cache.size() + ", capacity="
                                + cache.capacity());
            }
        }
    }

    // --- stripeCount upper bound ---

    // @spec sstable.striped-block-cache.R18
    @Test
    void excessiveStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(Integer.MAX_VALUE),
                "stripeCount of Integer.MAX_VALUE should be rejected — "
                        + "would allocate billions of LruBlockCache instances");
    }

    // @spec sstable.striped-block-cache.R18
    @Test
    void stripeCountAboveMaxLimitRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder()
                        .stripeCount(StripedBlockCache.MAX_STRIPE_COUNT + 1),
                "stripeCount exceeding MAX_STRIPE_COUNT should be rejected");
    }

    // @spec sstable.striped-block-cache.R19
    @Test
    void stripeCountAtMaxLimitAccepted() {
        // MAX_STRIPE_COUNT stripes × 4096 bytes per stripe meets the R20a default floor.
        assertDoesNotThrow(
                () -> StripedBlockCache.builder().stripeCount(StripedBlockCache.MAX_STRIPE_COUNT)
                        .byteBudget((long) StripedBlockCache.MAX_STRIPE_COUNT * 4_096L).build()
                        .close(),
                "stripeCount of MAX_STRIPE_COUNT should be accepted with a sufficient budget");
    }
}

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
 */
class StripedBlockCacheAdversarialTest {

    // --- Finding 1: CAPACITY-TRUNCATION ---
    // When capacity is not evenly divisible by stripeCount, integer division
    // truncates the remainder. capacity() reports the configured total, but
    // the actual effective capacity is (capacity / stripeCount) * stripeCount.

    // @spec F09.R9
    @Test
    void capacityReportsEffectiveNotConfiguredWhenTruncated() {
        // capacity=7, stripeCount=4 → per-stripe=1, effective=4
        // capacity() should report 4, not 7
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(7).build()) {
            long perStripe = 7 / 4; // 1
            long effective = perStripe * 4; // 4
            assertEquals(effective, cache.capacity(),
                    "capacity() should report effective capacity (perStripeCapacity * stripeCount), "
                            + "not the raw configured value when truncation occurs");
        }
    }

    // @spec F09.R8,R9
    @Test
    void capacityTruncationDoesNotCausePrematureEviction() {
        // capacity=10, stripeCount=4 → per-stripe=2, effective=8
        // If we insert 8 entries distributed across 4 stripes (2 per stripe),
        // none should be evicted. But if capacity tracking is wrong, eviction
        // might trigger early.
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(10).build()) {
            int inserted = 0;
            // Find 2 entries per stripe by probing keys
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
                    "8 entries (2 per stripe with per-stripe capacity 2) should all fit "
                            + "without eviction");
        }
    }

    // @spec F09.R8,R15
    @Test
    void effectiveCapacityMatchesActualMaxEntries() {
        // capacity=5, stripeCount=2 → per-stripe=2, effective=4
        // Insert 4 entries (2 per stripe) — all should fit.
        // Insert a 5th into a stripe already holding 2 — it should evict.
        try (var cache = StripedBlockCache.builder().stripeCount(2).capacity(5).build()) {
            // Find 2 entries for stripe 0 and 2 entries for stripe 1
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

            // Insert all 4 — should fit exactly
            for (long key : stripe0Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 1 }));
            }
            for (long key : stripe1Keys) {
                cache.put(1L, key, MemorySegment.ofArray(new byte[]{ 2 }));
            }
            assertEquals(4, cache.size(), "4 entries (2 per stripe) should fit");

            // The 5th entry goes to stripe 0, evicting the LRU entry there
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

            // Size should still be 4 (one evicted from stripe 0), not 5
            assertEquals(4, cache.size(),
                    "effective capacity is 4, not 5 — 5th entry should trigger per-stripe eviction");
        }
    }

    // --- Finding 2: LONG-CAPACITY-UNBOUNDED ---
    // LruBlockCache.Builder accepts any positive long for capacity, but
    // LinkedHashMap.size() returns int. If capacity > Integer.MAX_VALUE,
    // the removeEldestEntry check (size() > cap) can never trigger because
    // size() is int and can never exceed Integer.MAX_VALUE.

    // @spec F09.R43
    @Test
    void lruBlockCacheRejectsCapacityExceedingIntRange() {
        // capacity = Integer.MAX_VALUE + 1L should be rejected since
        // LinkedHashMap cannot honor eviction at that size
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().capacity((long) Integer.MAX_VALUE + 1L).build(),
                "capacity exceeding Integer.MAX_VALUE should be rejected because "
                        + "LinkedHashMap.size() returns int and eviction would never trigger");
    }

    // @spec F09.R22
    @Test
    void stripedBlockCacheRejectsPerStripeCapacityExceedingIntRange() {
        // If capacity / stripeCount > Integer.MAX_VALUE, per-stripe eviction breaks
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(1)
                        .capacity((long) Integer.MAX_VALUE + 1L).build(),
                "per-stripe capacity exceeding Integer.MAX_VALUE should be rejected");
    }

    // @spec F09.R43
    @Test
    void lruBlockCacheAcceptsMaxIntCapacity() {
        // Integer.MAX_VALUE should be accepted as a valid capacity
        assertDoesNotThrow(
                () -> LruBlockCache.builder().capacity(Integer.MAX_VALUE).build().close(),
                "capacity of Integer.MAX_VALUE should be accepted");
    }

    // --- F1: getOrLoad atomicity — concurrent callers should not duplicate loader ---

    // @spec F09.R37,R47
    // Updated by audit block-cache-hardening: getOrLoad now releases the lock before calling
    // the loader to avoid blocking all cache operations during I/O. This means concurrent callers
    // for the same key may both invoke the loader, but only one result is kept (double-checked
    // locking pattern). The loader may be called up to N times where N is the number of concurrent
    // threads, but the cache entry is consistent.
    @Test
    void getOrLoadReturnsConsistentValueUnderConcurrency() throws InterruptedException {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
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

            // Loader may be called once or twice (lock released during load), but both
            // callers must receive a valid result containing the expected data
            assertTrue(loaderCount.get() >= 1 && loaderCount.get() <= 2,
                    "loader may be invoked 1-2 times (lock released during load), got: "
                            + loaderCount.get());
            assertNotNull(results[0], "first caller must receive a result");
            assertNotNull(results[1], "second caller must receive a result");
            assertEquals((byte) 42, results[0].get(ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 42, results[1].get(ValueLayout.JAVA_BYTE, 0));
            // F09.R47: concurrent callers must return the same MemorySegment reference
            assertSame(results[0], results[1],
                    "concurrent getOrLoad callers must observe the same cached reference");
            assertSame(results[0], cache.get(1L, 0L).orElseThrow(),
                    "the cached value must equal what callers observed");
        }
    }

    // @spec F09.R37,R47
    // Updated by audit block-cache-hardening: same rationale as lruBlockCache test above —
    // getOrLoad releases the lock during loading, so concurrent callers may both invoke the loader.
    @Test
    void stripedGetOrLoadReturnsConsistentValueUnderConcurrency() throws InterruptedException {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
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
            // F09.R47: concurrent callers must return the same MemorySegment reference
            assertSame(results[0], results[1],
                    "concurrent getOrLoad callers must observe the same cached reference");
            assertSame(results[0], cache.get(1L, 0L).orElseThrow(),
                    "the cached value must equal what callers observed");
        }
    }

    // --- F2: Builder capacity() should validate eagerly ---

    // @spec F09.R44
    @Test
    void lruBuilderCapacityRejectsNegativeEagerly() {
        // F2: capacity(-1) should throw immediately, not at build()
        assertThrows(IllegalArgumentException.class, () -> LruBlockCache.builder().capacity(-1),
                "capacity setter should validate eagerly — negative capacity must be rejected immediately");
    }

    // @spec F09.R44
    @Test
    void lruBuilderCapacityRejectsZeroEagerly() {
        // F2: capacity(0) should throw immediately, not at build()
        assertThrows(IllegalArgumentException.class, () -> LruBlockCache.builder().capacity(0),
                "capacity setter should validate eagerly — zero capacity must be rejected immediately");
    }

    // @spec F09.R20
    @Test
    void stripedBuilderCapacityRejectsNegativeEagerly() {
        // F2: StripedBlockCache.Builder.capacity(-1) should throw immediately
        assertThrows(IllegalArgumentException.class, () -> StripedBlockCache.builder().capacity(-1),
                "capacity setter should validate eagerly — negative capacity must be rejected immediately");
    }

    // @spec F09.R20
    @Test
    void stripedBuilderCapacityRejectsZeroEagerly() {
        // F2: StripedBlockCache.Builder.capacity(0) should throw immediately
        assertThrows(IllegalArgumentException.class, () -> StripedBlockCache.builder().capacity(0),
                "capacity setter should validate eagerly — zero capacity must be rejected immediately");
    }

    // --- F4: LruBlockCache capacity == Integer.MAX_VALUE overflow ---
    // F4 classified as UNTRIGGERABLE: reaching Integer.MAX_VALUE entries requires
    // ~160 GB heap. The existing test lruBlockCacheAcceptsMaxIntCapacity confirms
    // acceptance is the intended contract. Documenting the theoretical risk only.

    // --- F5: Use-after-close detection ---

    // @spec F09.R29
    @Test
    void lruPutAfterCloseThrows() {
        // F5: Operations on a closed cache should fail, not silently succeed
        var cache = LruBlockCache.builder().capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 })),
                "put on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec F09.R28
    @Test
    void lruGetAfterCloseThrows() {
        // F5: get on a closed cache should fail
        var cache = LruBlockCache.builder().capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get(1L, 0L),
                "get on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec F09.R29
    @Test
    void stripedPutAfterCloseThrows() {
        // F5: Operations on a closed StripedBlockCache should fail
        var cache = StripedBlockCache.builder().stripeCount(2).capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 })),
                "put on a closed StripedBlockCache should throw IllegalStateException");
    }

    // @spec F09.R28
    @Test
    void stripedGetAfterCloseThrows() {
        // F5: get on a closed StripedBlockCache should fail
        var cache = StripedBlockCache.builder().stripeCount(2).capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get(1L, 0L),
                "get on a closed StripedBlockCache should throw IllegalStateException");
    }

    // @spec F09.R30
    @Test
    void lruEvictAfterCloseThrows() {
        // F5: evict on a closed cache should fail
        var cache = LruBlockCache.builder().capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.evict(1L),
                "evict on a closed LruBlockCache should throw IllegalStateException");
    }

    // @spec F09.R30
    @Test
    void stripedEvictAfterCloseThrows() {
        // F5: evict on a closed StripedBlockCache should fail
        var cache = StripedBlockCache.builder().stripeCount(2).capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.evict(1L),
                "evict on a closed StripedBlockCache should throw IllegalStateException");
    }

    // --- F7: size() never negative, never exceeds capacity ---

    // @spec F09.R7
    @Test
    void sizeNeverNegativeAfterEvict() {
        // F7: size() should never return negative even after evicting non-existent entries
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.evict(999L); // evict from empty cache
            assertTrue(cache.size() >= 0,
                    "size() must never return negative, even after evicting from empty cache");
        }
    }

    // @spec F09.R8
    @Test
    void sizeNeverExceedsCapacity() {
        // F7: size() should never exceed capacity(), even transiently
        try (var cache = StripedBlockCache.builder().stripeCount(2).capacity(4).build()) {
            // Insert more entries than capacity — size should cap at capacity
            for (int i = 0; i < 20; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
                assertTrue(cache.size() <= cache.capacity(),
                        "size() must never exceed capacity(); size=" + cache.size() + ", capacity="
                                + cache.capacity());
            }
        }
    }

    // --- F9: stripeCount upper bound ---

    // @spec F09.R18
    @Test
    void excessiveStripeCountRejected() {
        // F9: stripeCount of Integer.MAX_VALUE would allocate billions of objects.
        // Should be rejected eagerly by the stripeCount setter.
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(Integer.MAX_VALUE),
                "stripeCount of Integer.MAX_VALUE should be rejected — "
                        + "would allocate billions of LruBlockCache instances");
    }

    // @spec F09.R18
    @Test
    void stripeCountAboveMaxLimitRejected() {
        // F9: stripeCount above MAX_STRIPE_COUNT should be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder()
                        .stripeCount(StripedBlockCache.MAX_STRIPE_COUNT + 1),
                "stripeCount exceeding MAX_STRIPE_COUNT should be rejected");
    }

    // @spec F09.R19
    @Test
    void stripeCountAtMaxLimitAccepted() {
        // F9: stripeCount at exactly MAX_STRIPE_COUNT should be accepted.
        assertDoesNotThrow(
                () -> StripedBlockCache.builder().stripeCount(StripedBlockCache.MAX_STRIPE_COUNT)
                        .capacity(StripedBlockCache.MAX_STRIPE_COUNT).build().close(),
                "stripeCount of MAX_STRIPE_COUNT should be accepted");
    }
}

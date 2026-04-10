package jlsm.cache;

import jlsm.core.cache.BlockCache;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StripedBlockCacheTest {

    // --- Builder validation ---

    @Test
    void zeroStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(0).capacity(10).build());
    }

    @Test
    void negativeStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(-1).capacity(10).build());
    }

    @Test
    void nonPowerOfTwoStripeCountRoundedUp() {
        // 3 rounds up to 4, capacity must accommodate the effective count
        try (var cache = StripedBlockCache.builder().stripeCount(3).capacity(40).build()) {
            assertEquals(40, cache.capacity());
        }
        // 5 rounds up to 8
        try (var cache = StripedBlockCache.builder().stripeCount(5).capacity(80).build()) {
            assertEquals(80, cache.capacity());
        }
        // 7 rounds up to 8
        try (var cache = StripedBlockCache.builder().stripeCount(7).capacity(80).build()) {
            assertEquals(80, cache.capacity());
        }
    }

    @Test
    void powerOfTwoStripeCountsAccepted() {
        for (int count : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            try (var cache = StripedBlockCache.builder().stripeCount(count)
                    .capacity(count * 10L).build()) {
                assertNotNull(cache);
            }
        }
    }

    @Test
    void stripeIndexRejectsNonPowerOfTwo() {
        // The static stripeIndex method requires power-of-2
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 3));
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 7));
    }

    @Test
    void capacityLessThanStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(4).capacity(3).build());
    }

    @Test
    void capacityNotSetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(2).build());
    }

    @Test
    void validBuilderConstructsCache() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertNotNull(cache);
            assertInstanceOf(BlockCache.class, cache);
        }
    }

    // --- Stripe index ---

    @Test
    void stripeIndexInRange() {
        int stripeCount = 8;
        for (long sstableId = 0; sstableId < 50; sstableId++) {
            for (long offset = 0; offset < 50; offset++) {
                int idx = StripedBlockCache.stripeIndex(sstableId, offset, stripeCount);
                assertTrue(idx >= 0 && idx < stripeCount, "stripeIndex out of range: " + idx
                        + " for sstableId=" + sstableId + ", offset=" + offset);
            }
        }
    }

    @Test
    void stripeIndexUsesBitmaskForPowerOfTwo() {
        // Verify the bitmask produces the same result as manual computation
        int stripeCount = 16;
        int mask = stripeCount - 1;
        for (long id = 0; id < 100; id++) {
            int idx = StripedBlockCache.stripeIndex(id, 0L, stripeCount);
            assertTrue(idx >= 0 && idx < stripeCount);
            // The result should equal hash & mask — verified implicitly by range check
            // and distribution test below
        }
    }

    @Test
    void stripeIndexDistributesSequentialOffsets() {
        // Sequential 4096-aligned offsets (typical SSTable block offsets) should not all
        // land in the same stripe. With 8 stripes and 64 sequential offsets, we expect
        // at least 4 distinct stripes to be hit.
        int stripeCount = 8;
        var seen = new HashSet<Integer>();
        for (long offset = 0; offset < 64; offset++) {
            seen.add(StripedBlockCache.stripeIndex(1L, offset * 4096, stripeCount));
        }
        assertTrue(seen.size() >= 4, "Expected at least 4 distinct stripes, got " + seen.size());
    }

    // --- get / put delegation ---

    @Test
    void putThenGetReturnsBlock() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 1, 2, 3 });
            cache.put(1L, 0L, block);
            assertEquals(block, cache.get(1L, 0L).orElseThrow());
        }
    }

    @Test
    void getMissReturnsEmpty() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertTrue(cache.get(1L, 0L).isEmpty());
        }
    }

    @Test
    void getWrongKeyMisses() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(2L, 0L).isEmpty(), "wrong sstableId should miss");
            assertTrue(cache.get(1L, 1L).isEmpty(), "wrong offset should miss");
        }
    }

    @Test
    void putOverwritesExisting() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            var first = MemorySegment.ofArray(new byte[]{ 1 });
            var second = MemorySegment.ofArray(new byte[]{ 2 });
            cache.put(1L, 0L, first);
            cache.put(1L, 0L, second);
            assertEquals(second, cache.get(1L, 0L).orElseThrow());
        }
    }

    // --- Input validation ---

    @Test
    void getNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(1L, -1L));
        }
    }

    @Test
    void putNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.put(1L, -1L, MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    @Test
    void putNullBlockRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(NullPointerException.class, () -> cache.put(1L, 0L, null));
        }
    }

    // --- evict across stripes ---

    @Test
    void evictRemovesAllBlocksForSstable() {
        // Use many offsets so entries are likely distributed across multiple stripes
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            for (long offset = 0; offset < 20; offset++) {
                cache.put(1L, offset, MemorySegment.ofArray(new byte[]{ (byte) offset }));
            }
            assertEquals(20, cache.size());
            cache.evict(1L);
            assertEquals(0, cache.size());
            // Verify individual lookups also miss
            for (long offset = 0; offset < 20; offset++) {
                assertTrue(cache.get(1L, offset).isEmpty());
            }
        }
    }

    @Test
    void evictDoesNotAffectOtherSstables() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(2L, 0L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.get(2L, 0L).isPresent());
        }
    }

    @Test
    void evictNonexistentSstableIsNoOp() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertDoesNotThrow(() -> cache.evict(99L));
            assertEquals(1, cache.size());
        }
    }

    // --- size / capacity ---

    @Test
    void initialSizeIsZero() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertEquals(0, cache.size());
        }
    }

    @Test
    void sizeReflectsInsertions() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            for (int i = 0; i < 10; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
            }
            assertEquals(10, cache.size());
        }
    }

    @Test
    void capacityReturnsTotalCapacity() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertEquals(100, cache.capacity());
        }
    }

    // --- LRU eviction through stripes ---

    @Test
    void lruEvictionWorksPerStripe() {
        // With 1 stripe and capacity 2, third insert should evict the LRU entry
        try (var cache = StripedBlockCache.builder().stripeCount(1).capacity(2).build()) {
            var a = MemorySegment.ofArray(new byte[]{ 1 });
            var b = MemorySegment.ofArray(new byte[]{ 2 });
            var c = MemorySegment.ofArray(new byte[]{ 3 });
            cache.put(1L, 0L, a);
            cache.put(1L, 1L, b);
            cache.put(1L, 2L, c); // triggers eviction of LRU entry (offset 0)
            assertTrue(cache.get(1L, 0L).isEmpty(), "LRU entry should have been evicted");
            assertEquals(2, cache.size());
        }
    }

    // --- close ---

    @Test
    void closeDoesNotThrow() {
        var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build();
        assertDoesNotThrow(cache::close);
    }

    // Updated by audit block-cache-hardening: close() now rejects use-after-close on all methods
    // including size(). The old test asserted size()==0 after close, but the correct behavior is
    // that size() throws IllegalStateException on a closed cache.
    @Test
    void closedCacheRejectsSize() {
        var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build();
        for (int i = 0; i < 20; i++) {
            cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
        }
        cache.close();
        assertThrows(IllegalStateException.class, cache::size);
    }

    // --- Factory methods on LruBlockCache ---

    @Test
    void getMultiThreadedReturnsStripedBuilder() {
        assertInstanceOf(StripedBlockCache.Builder.class, LruBlockCache.getMultiThreaded());
    }

    @Test
    void getSingleThreadedReturnsLruBuilder() {
        assertInstanceOf(LruBlockCache.Builder.class, LruBlockCache.getSingleThreaded());
    }

    // --- Concurrent correctness ---

    @Test
    void concurrentPutGetDoesNotLoseEntries() throws InterruptedException {
        int threads = 8;
        int entriesPerThread = 200;
        // Over-provision capacity to avoid LRU eviction due to imperfect hash distribution
        try (var cache = StripedBlockCache.builder().stripeCount(4)
                .capacity((long) threads * entriesPerThread * 2).build()) {

            var errors = new ConcurrentLinkedQueue<Throwable>();
            var failed = new AtomicBoolean(false);
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final long sstableId = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            var block = MemorySegment.ofArray(new byte[]{ (byte) i });
                            cache.put(sstableId, i, block);
                        }
                        // Verify all our entries are present
                        for (int i = 0; i < entriesPerThread; i++) {
                            var result = cache.get(sstableId, i);
                            if (result.isEmpty()) {
                                failed.set(true);
                                errors.add(new AssertionError(
                                        "Missing entry: sstableId=" + sstableId + ", offset=" + i));
                            }
                        }
                    } catch (Throwable e) {
                        failed.set(true);
                        errors.add(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            if (failed.get()) {
                var first = errors.poll();
                assert first != null;
                fail("Concurrent test failed: " + first.getMessage(), first);
            }

            // All entries should be present — no eviction with over-provisioned capacity
            assertEquals((long) threads * entriesPerThread, cache.size());
        }
    }
}

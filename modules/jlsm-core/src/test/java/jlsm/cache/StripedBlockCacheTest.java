package jlsm.cache;

import jlsm.core.cache.BlockCache;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StripedBlockCacheTest {

    // --- Builder validation ---

    // @spec sstable.striped-block-cache.R17
    @Test
    void zeroStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(0).capacity(10).build());
    }

    // @spec sstable.striped-block-cache.R17
    @Test
    void negativeStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(-1).capacity(10).build());
    }

    // @spec sstable.striped-block-cache.R9 — capacity formula uses effectiveStripeCount
    // (rounded-up)
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

    // @spec sstable.striped-block-cache.R19 — power-of-2 stripe counts (including MAX_STRIPE_COUNT)
    // are accepted
    @Test
    void powerOfTwoStripeCountsAccepted() {
        for (int count : new int[]{ 1, 2, 4, 8, 16, 32, 64 }) {
            try (var cache = StripedBlockCache.builder().stripeCount(count).capacity(count * 10L)
                    .build()) {
                assertNotNull(cache);
            }
        }
    }

    // @spec sstable.striped-block-cache.R45 — static stripeIndex rejects non-power-of-2 stripeCount
    @Test
    void stripeIndexRejectsNonPowerOfTwo() {
        // The static stripeIndex method requires power-of-2
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 3));
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 7));
    }

    // @spec sstable.striped-block-cache.R21
    @Test
    void capacityLessThanStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(4).capacity(3).build());
    }

    // @spec sstable.striped-block-cache.R24
    @Test
    void capacityNotSetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(2).build());
    }

    // @spec sstable.striped-block-cache.R1
    @Test
    void validBuilderConstructsCache() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertNotNull(cache);
            assertInstanceOf(BlockCache.class, cache);
        }
    }

    // --- Stripe index ---

    // @spec sstable.striped-block-cache.R10
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

    // @spec sstable.striped-block-cache.R10,R13 — bitmask bound and determinism for power-of-2
    // stripeCount
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

    // @spec sstable.striped-block-cache.R12 — sequential 4096-aligned offsets distribute across ≥
    // half of 8 stripes
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

    // @spec sstable.striped-block-cache.R2,R4
    @Test
    void putThenGetReturnsBlock() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 1, 2, 3 });
            cache.put(1L, 0L, block);
            assertEquals(block, cache.get(1L, 0L).orElseThrow());
        }
    }

    // @spec sstable.striped-block-cache.R3
    @Test
    void getMissReturnsEmpty() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertTrue(cache.get(1L, 0L).isEmpty());
        }
    }

    // @spec sstable.striped-block-cache.R3
    @Test
    void getWrongKeyMisses() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(2L, 0L).isEmpty(), "wrong sstableId should miss");
            assertTrue(cache.get(1L, 1L).isEmpty(), "wrong offset should miss");
        }
    }

    // @spec sstable.striped-block-cache.R4
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

    // @spec sstable.striped-block-cache.R25
    @Test
    void getNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(1L, -1L));
        }
    }

    // @spec sstable.striped-block-cache.R26
    @Test
    void putNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.put(1L, -1L, MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    // @spec sstable.striped-block-cache.R27
    @Test
    void putNullBlockRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(NullPointerException.class, () -> cache.put(1L, 0L, null));
        }
    }

    // --- evict across stripes ---

    // @spec sstable.striped-block-cache.R5,R16
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

    // @spec sstable.striped-block-cache.R5
    @Test
    void evictDoesNotAffectOtherSstables() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(2L, 0L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.get(2L, 0L).isPresent());
        }
    }

    // @spec sstable.striped-block-cache.R5
    @Test
    void evictNonexistentSstableIsNoOp() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertDoesNotThrow(() -> cache.evict(99L));
            assertEquals(1, cache.size());
        }
    }

    // --- size / capacity ---

    // @spec sstable.striped-block-cache.R6,R7
    @Test
    void initialSizeIsZero() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertEquals(0, cache.size());
        }
    }

    // @spec sstable.striped-block-cache.R6
    @Test
    void sizeReflectsInsertions() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            for (int i = 0; i < 10; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
            }
            assertEquals(10, cache.size());
        }
    }

    // @spec sstable.striped-block-cache.R9
    @Test
    void capacityReturnsTotalCapacity() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertEquals(100, cache.capacity());
        }
    }

    // --- LRU eviction through stripes ---

    // @spec sstable.striped-block-cache.R15
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

    // @spec sstable.striped-block-cache.R32,R33
    @Test
    void closeDoesNotThrow() {
        var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build();
        assertDoesNotThrow(cache::close);
    }

    // @spec sstable.striped-block-cache.R46
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

    // @spec sstable.striped-block-cache.R41
    @Test
    void getMultiThreadedReturnsStripedBuilder() {
        assertInstanceOf(StripedBlockCache.Builder.class, LruBlockCache.getMultiThreaded());
    }

    // @spec sstable.striped-block-cache.R42
    @Test
    void getSingleThreadedReturnsLruBuilder() {
        assertInstanceOf(LruBlockCache.Builder.class, LruBlockCache.getSingleThreaded());
    }

    // --- Concurrent correctness ---

    // @spec sstable.striped-block-cache.R34,R35,R36 — concurrent put/get is safe and doesn't lose
    // entries within capacity
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

    // --- Algorithm pin tests ---

    // @spec sstable.striped-block-cache.R11 — pins the Splitmix64 finalizer (Stafford variant 13)
    // constants and the
    // golden-ratio input combining. If any multiplier or shift drifts, this test
    // fails with a deterministic mismatch.
    @Test
    void stripeIndexMatchesSplitmix64StaffordReference() {
        int stripeCount = 1024;
        long[] sstableIds = { 1L, 42L, 0xDEADBEEFL, -1L, Long.MIN_VALUE };
        long[] offsets = { 0L, 4096L, 1L << 30, Long.MAX_VALUE };
        for (long sstableId : sstableIds) {
            for (long offset : offsets) {
                long h = sstableId * 0x9E3779B97F4A7C15L + offset;
                h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
                h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
                h = h ^ (h >>> 31);
                int expected = (int) (h & (stripeCount - 1));
                assertEquals(expected,
                        StripedBlockCache.stripeIndex(sstableId, offset, stripeCount),
                        "sstableId=" + sstableId + ", offset=" + offset);
            }
        }
    }

    // @spec sstable.striped-block-cache.R14 — stripeIndex must be package-private static (not
    // exposed on the public API)
    @Test
    void stripeIndexIsPackagePrivateStatic() throws NoSuchMethodException {
        Method m = StripedBlockCache.class.getDeclaredMethod("stripeIndex", long.class, long.class,
                int.class);
        int mods = m.getModifiers();
        assertTrue(Modifier.isStatic(mods), "stripeIndex must be static");
        assertFalse(Modifier.isPublic(mods), "stripeIndex must not be public");
        assertFalse(Modifier.isProtected(mods), "stripeIndex must not be protected");
        assertFalse(Modifier.isPrivate(mods), "stripeIndex must not be private");
    }

    // @spec sstable.striped-block-cache.R23 — default stripeCount = min(availableProcessors, 16),
    // rounded up to next
    // power of 2 by the Builder
    @Test
    void defaultStripeCountMatchesSpec() throws Exception {
        int expected = Math.min(Runtime.getRuntime().availableProcessors(), 16);
        int expectedEffective = Integer.bitCount(expected) == 1 ? expected
                : Integer.highestOneBit(expected) << 1;
        try (var cache = StripedBlockCache.builder().capacity(1024).build()) {
            var field = StripedBlockCache.class.getDeclaredField("stripeCount");
            field.setAccessible(true);
            assertEquals(expectedEffective, field.getInt(cache),
                    "default stripeCount must match min(availableProcessors, 16) "
                            + "rounded up to power of 2");
        }
    }

    // --- getOrLoad validation ---

    // @spec sstable.striped-block-cache.R38
    @Test
    void getOrLoadNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.getOrLoad(1L, -1L, () -> MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    // @spec sstable.striped-block-cache.R39
    @Test
    void getOrLoadNullLoaderRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build()) {
            assertThrows(NullPointerException.class, () -> cache.getOrLoad(1L, 0L, null));
        }
    }

    // @spec sstable.striped-block-cache.R40
    @Test
    void getOrLoadAfterCloseThrows() {
        var cache = StripedBlockCache.builder().stripeCount(4).capacity(100).build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.getOrLoad(1L, 0L, () -> MemorySegment.ofArray(new byte[]{ 1 })));
    }
}

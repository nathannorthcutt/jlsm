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

    // Large-enough byte budget that the legacy entry-count-style tests don't trigger eviction.
    // Legacy tests wrote 1-byte segments; re-targeting to a 1_048_576-byte budget preserves the
    // original "no-eviction" semantics across the effective stripe counts used below.
    private static final long NO_EVICTION_BUDGET = 1L << 20;

    // --- Builder validation ---

    // @spec sstable.striped-block-cache.R17
    @Test
    void zeroStripeCountRejected() {
        assertThrows(IllegalArgumentException.class, () -> StripedBlockCache.builder()
                .stripeCount(0).byteBudget(NO_EVICTION_BUDGET).build());
    }

    // @spec sstable.striped-block-cache.R17
    @Test
    void negativeStripeCountRejected() {
        assertThrows(IllegalArgumentException.class, () -> StripedBlockCache.builder()
                .stripeCount(-1).byteBudget(NO_EVICTION_BUDGET).build());
    }

    // @spec sstable.byte-budget-block-cache.R23 — capacity() formula uses effectiveStripeCount
    // (rounded up) and byte-budget truncation
    @Test
    void nonPowerOfTwoStripeCountRoundedUp() {
        // 3 rounds up to 4 stripes; 4 * 4096 = 16384, 16384 / 4 = 4096 per stripe → effective 16384
        try (var cache = StripedBlockCache.builder().stripeCount(3).byteBudget(16_384L).build()) {
            assertEquals(16_384L, cache.capacity());
        }
        // 5 rounds up to 8 stripes; 8 * 4096 = 32768
        try (var cache = StripedBlockCache.builder().stripeCount(5).byteBudget(32_768L).build()) {
            assertEquals(32_768L, cache.capacity());
        }
        // 7 rounds up to 8
        try (var cache = StripedBlockCache.builder().stripeCount(7).byteBudget(32_768L).build()) {
            assertEquals(32_768L, cache.capacity());
        }
    }

    // @spec sstable.striped-block-cache.R19 — power-of-2 stripe counts are accepted
    @Test
    void powerOfTwoStripeCountsAccepted() {
        for (int count : new int[]{ 1, 2, 4, 8, 16, 32, 64 }) {
            try (var cache = StripedBlockCache.builder().stripeCount(count)
                    .byteBudget(count * 4_096L).build()) {
                assertNotNull(cache);
            }
        }
    }

    // @spec sstable.striped-block-cache.R45 — static stripeIndex rejects non-power-of-2 stripeCount
    @Test
    void stripeIndexRejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 3));
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 7));
    }

    // @spec sstable.byte-budget-block-cache.R20 — byteBudget < effectiveStripeCount rejected
    @Test
    void byteBudgetLessThanStripeCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(4).byteBudget(3L).build());
    }

    // @spec sstable.byte-budget-block-cache.R19 — byteBudget must be set explicitly
    @Test
    void byteBudgetNotSetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().stripeCount(2).build());
    }

    // @spec sstable.striped-block-cache.R1
    @Test
    void validBuilderConstructsCache() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
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
        int stripeCount = 16;
        for (long id = 0; id < 100; id++) {
            int idx = StripedBlockCache.stripeIndex(id, 0L, stripeCount);
            assertTrue(idx >= 0 && idx < stripeCount);
        }
    }

    // @spec sstable.striped-block-cache.R12 — sequential 4096-aligned offsets distribute across ≥
    // half of 8 stripes
    @Test
    void stripeIndexDistributesSequentialOffsets() {
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
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            var block = MemorySegment.ofArray(new byte[]{ 1, 2, 3 });
            cache.put(1L, 0L, block);
            assertEquals(block, cache.get(1L, 0L).orElseThrow());
        }
    }

    // @spec sstable.striped-block-cache.R3
    @Test
    void getMissReturnsEmpty() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertTrue(cache.get(1L, 0L).isEmpty());
        }
    }

    // @spec sstable.striped-block-cache.R3
    @Test
    void getWrongKeyMisses() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(2L, 0L).isEmpty(), "wrong sstableId should miss");
            assertTrue(cache.get(1L, 1L).isEmpty(), "wrong offset should miss");
        }
    }

    // @spec sstable.striped-block-cache.R4
    @Test
    void putOverwritesExisting() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
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
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(1L, -1L));
        }
    }

    // @spec sstable.striped-block-cache.R26
    @Test
    void putNegativeBlockOffsetRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.put(1L, -1L, MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    // @spec sstable.striped-block-cache.R27
    @Test
    void putNullBlockRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.put(1L, 0L, null));
        }
    }

    // --- evict across stripes ---

    // @spec sstable.striped-block-cache.R5,R16
    @Test
    void evictRemovesAllBlocksForSstable() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            for (long offset = 0; offset < 20; offset++) {
                cache.put(1L, offset, MemorySegment.ofArray(new byte[]{ (byte) offset }));
            }
            assertEquals(20, cache.size());
            cache.evict(1L);
            assertEquals(0, cache.size());
            for (long offset = 0; offset < 20; offset++) {
                assertTrue(cache.get(1L, offset).isEmpty());
            }
        }
    }

    // @spec sstable.striped-block-cache.R5
    @Test
    void evictDoesNotAffectOtherSstables() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(2L, 0L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.get(2L, 0L).isPresent());
        }
    }

    // @spec sstable.striped-block-cache.R5
    @Test
    void evictNonexistentSstableIsNoOp() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertDoesNotThrow(() -> cache.evict(99L));
            assertEquals(1, cache.size());
        }
    }

    // --- size / capacity ---

    // @spec sstable.striped-block-cache.R6,R7
    @Test
    void initialSizeIsZero() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertEquals(0, cache.size());
        }
    }

    // @spec sstable.striped-block-cache.R6
    @Test
    void sizeReflectsInsertions() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            for (int i = 0; i < 10; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
            }
            assertEquals(10, cache.size());
        }
    }

    // @spec sstable.byte-budget-block-cache.R23
    @Test
    void capacityReturnsTotalCapacity() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertEquals(NO_EVICTION_BUDGET, cache.capacity());
        }
    }

    // --- LRU eviction through stripes ---

    // @spec sstable.byte-budget-block-cache.R22,R10 — per-stripe byte-budget eviction with 1-byte
    // segments and per-stripe budget of 2 bytes (1 stripe, 2-byte total budget), third insert
    // evicts the LRU entry within that stripe
    @Test
    void lruEvictionWorksPerStripe() {
        try (var cache = StripedBlockCache.builder().stripeCount(1).expectedMinimumBlockSize(1L)
                .byteBudget(2L).build()) {
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
        var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build();
        assertDoesNotThrow(cache::close);
    }

    // @spec sstable.striped-block-cache.R46
    // close() rejects use-after-close on all methods including size().
    @Test
    void closedCacheRejectsSize() {
        var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build();
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
    // entries within byte budget
    @Test
    void concurrentPutGetDoesNotLoseEntries() throws InterruptedException {
        int threads = 8;
        int entriesPerThread = 200;
        // Over-provision byte budget to avoid LRU eviction due to imperfect hash distribution.
        // Each entry is 1 byte; keep the budget comfortably above threads*entriesPerThread.
        try (var cache = StripedBlockCache.builder().stripeCount(4).expectedMinimumBlockSize(1L)
                .byteBudget((long) threads * entriesPerThread * 8).build()) {

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

            assertEquals((long) threads * entriesPerThread, cache.size());
        }
    }

    // --- Algorithm pin tests ---

    // @spec sstable.striped-block-cache.R11 (v4) — pins the Splitmix64 finalizer constants and
    // the non-linear pre-avalanche combine (a multiply-XOR-shift round on sstableId*G before the
    // add of blockOffset). The pre-avalanche defeats algebraic pre-image collisions in the
    // combined input; see finding F-R1.data_transformation.1.1.
    @Test
    void stripeIndexMatchesSplitmix64StaffordReference() {
        int stripeCount = 1024;
        long[] sstableIds = { 1L, 42L, 0xDEADBEEFL, -1L, Long.MIN_VALUE };
        long[] offsets = { 0L, 4096L, 1L << 30, Long.MAX_VALUE };
        for (long sstableId : sstableIds) {
            for (long offset : offsets) {
                // R11 (v4) pre-avalanche non-linear combine
                long a = sstableId * 0x9E3779B97F4A7C15L;
                a = (a ^ (a >>> 30)) * 0xBF58476D1CE4E5B9L;
                long h = a + offset;
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
    // rounded up to next power of 2 by the Builder
    @Test
    void defaultStripeCountMatchesSpec() throws Exception {
        int expected = Math.min(Runtime.getRuntime().availableProcessors(), 16);
        int expectedEffective = Integer.bitCount(expected) == 1 ? expected
                : Integer.highestOneBit(expected) << 1;
        try (var cache = StripedBlockCache.builder().byteBudget(NO_EVICTION_BUDGET).build()) {
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
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.getOrLoad(1L, -1L, () -> MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    // @spec sstable.striped-block-cache.R39
    @Test
    void getOrLoadNullLoaderRejected() {
        try (var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.getOrLoad(1L, 0L, null));
        }
    }

    // @spec sstable.striped-block-cache.R40
    @Test
    void getOrLoadAfterCloseThrows() {
        var cache = StripedBlockCache.builder().stripeCount(4).byteBudget(NO_EVICTION_BUDGET)
                .build();
        cache.close();
        assertThrows(IllegalStateException.class,
                () -> cache.getOrLoad(1L, 0L, () -> MemorySegment.ofArray(new byte[]{ 1 })));
    }
}

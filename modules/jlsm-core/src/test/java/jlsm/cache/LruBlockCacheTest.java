package jlsm.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LruBlockCacheTest {

    // --- Builder ---

    // @spec sstable.byte-budget-block-cache.R2
    @Test
    void zeroByteBudgetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().byteBudget(0).build());
    }

    // @spec sstable.byte-budget-block-cache.R2
    @Test
    void negativeByteBudgetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().byteBudget(-1).build());
    }

    // --- get / put ---

    @Test
    void getMissOnEmptyCacheReturnsEmpty() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            assertTrue(cache.get(1L, 0L).isEmpty());
        }
    }

    @Test
    void putThenGetReturnsBlock() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 1, 2, 3 });
            cache.put(1L, 0L, block);
            assertEquals(block, cache.get(1L, 0L).orElseThrow());
        }
    }

    @Test
    void getWrongSstableIdMisses() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(2L, 0L).isEmpty());
        }
    }

    @Test
    void getWrongOffsetMisses() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(1L, 1L).isEmpty());
        }
    }

    @Test
    void putOverwritesExistingEntry() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            var first = MemorySegment.ofArray(new byte[]{ 1 });
            var second = MemorySegment.ofArray(new byte[]{ 2 });
            cache.put(1L, 0L, first);
            cache.put(1L, 0L, second);
            assertEquals(second, cache.get(1L, 0L).orElseThrow());
        }
    }

    // --- size / capacity ---

    @Test
    void initialSizeIsZero() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            assertEquals(0, cache.size());
        }
    }

    @Test
    void sizeIncreasesAfterPut() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertEquals(1, cache.size());
        }
    }

    @Test
    void sizeDecreasesAfterEvict() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.size() < 2);
        }
    }

    // @spec sstable.byte-budget-block-cache.R14 — capacity() returns the byte budget (unit:
    // bytes), not the entry count
    @Test
    void capacityReflectsConfiguration() {
        try (var cache = LruBlockCache.builder().byteBudget(42L).build()) {
            assertEquals(42L, cache.capacity());
        }
    }

    // --- Validation ---

    @Test
    void negativeOffsetInGetRejected() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(1L, -1L));
        }
    }

    @Test
    void negativeOffsetInPutRejected() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.put(1L, -1L, MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    @Test
    void nullBlockInPutRejected() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            assertThrows(NullPointerException.class, () -> cache.put(1L, 0L, null));
        }
    }

    // --- LRU eviction ---

    // @spec sstable.byte-budget-block-cache.R10 — budget=2 bytes with 1-byte entries behaves as
    // the former entry-count=2 case: insert A, B, then C → A must be gone
    @Test
    void lruEntryEvictedWhenAtCapacity() {
        try (var cache = LruBlockCache.builder().byteBudget(2L).build()) {
            var a = MemorySegment.ofArray(new byte[]{ 1 });
            var b = MemorySegment.ofArray(new byte[]{ 2 });
            var c = MemorySegment.ofArray(new byte[]{ 3 });
            cache.put(1L, 0L, a);
            cache.put(1L, 1L, b);
            cache.put(1L, 2L, c); // triggers eviction of LRU entry (offset 0)
            assertTrue(cache.get(1L, 0L).isEmpty(), "LRU entry should have been evicted");
        }
    }

    // @spec sstable.byte-budget-block-cache.R10 — access-order promotion under byte-budget eviction
    @Test
    void recentlyAccessedEntryNotEvicted() {
        // budget=2 bytes; insert A, B, access A (makes B LRU), insert C → B evicted, A survives
        try (var cache = LruBlockCache.builder().byteBudget(2L).build()) {
            var a = MemorySegment.ofArray(new byte[]{ 1 });
            var b = MemorySegment.ofArray(new byte[]{ 2 });
            var c = MemorySegment.ofArray(new byte[]{ 3 });
            cache.put(1L, 0L, a);
            cache.put(1L, 1L, b);
            cache.get(1L, 0L); // promote A to MRU
            cache.put(1L, 2L, c); // triggers eviction of LRU entry (offset 1)
            assertTrue(cache.get(1L, 0L).isPresent(), "recently accessed entry should survive");
            assertTrue(cache.get(1L, 1L).isEmpty(), "LRU entry should have been evicted");
        }
    }

    // @spec sstable.byte-budget-block-cache.R12 — size() never exceeds what the byte budget can
    // hold when entries are uniformly sized
    @Test
    void sizeStaysAtCapacityAfterLruEviction() {
        try (var cache = LruBlockCache.builder().byteBudget(3L).build()) {
            for (int i = 0; i < 5; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
            }
            assertEquals(3, cache.size());
        }
    }

    // --- SSTable evict ---

    @Test
    void evictRemovesAllBlocksForSstable() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.put(1L, 2L, MemorySegment.ofArray(new byte[]{ 3 }));
            cache.evict(1L);
            assertEquals(0, cache.size());
        }
    }

    @Test
    void evictDoesNotAffectOtherSstables() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(2L, 0L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.get(2L, 0L).isPresent());
        }
    }

    @Test
    void evictNonexistentSstableIsNoOp() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertDoesNotThrow(() -> cache.evict(99L));
            assertEquals(1, cache.size());
        }
    }

    // --- getOrLoad ---

    @Test
    void getOrLoadCallsLoaderOnMiss() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            var callCount = new AtomicInteger(0);
            var block = MemorySegment.ofArray(new byte[]{ 42 });
            cache.getOrLoad(1L, 0L, () -> {
                callCount.incrementAndGet();
                return block;
            });
            assertEquals(1, callCount.get());
        }
    }

    @Test
    void getOrLoadDoesNotCallLoaderOnHit() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 42 });
            cache.put(1L, 0L, block);
            var callCount = new AtomicInteger(0);
            cache.getOrLoad(1L, 0L, () -> {
                callCount.incrementAndGet();
                return block;
            });
            assertEquals(0, callCount.get());
        }
    }

    @Test
    void getOrLoadCachesLoadedBlock() {
        try (var cache = LruBlockCache.builder().byteBudget(1_000L).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 42 });
            cache.getOrLoad(1L, 0L, () -> block);
            assertTrue(cache.get(1L, 0L).isPresent());
        }
    }

    // --- close ---

    // @spec sstable.byte-budget-block-cache.R16 — close on empty or populated cache must not throw
    @Test
    void closeDoesNotThrow() {
        var cache = LruBlockCache.builder().byteBudget(1_000L).build();
        assertDoesNotThrow(cache::close);
    }

    // @spec sstable.byte-budget-block-cache.R31
    // close() rejects use-after-close on all methods including size().
    @Test
    void closedCacheRejectsSize() {
        var cache = LruBlockCache.builder().byteBudget(1_000L).build();
        cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
        cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
        cache.close();
        assertThrows(IllegalStateException.class, cache::size);
    }
}

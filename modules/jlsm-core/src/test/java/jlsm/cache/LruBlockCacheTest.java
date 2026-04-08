package jlsm.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LruBlockCacheTest {

    // --- Builder ---

    @Test
    void zeroCapacityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().capacity(0).build());
    }

    @Test
    void negativeCapacityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LruBlockCache.builder().capacity(-1).build());
    }

    // --- get / put ---

    @Test
    void getMissOnEmptyCacheReturnsEmpty() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            assertTrue(cache.get(1L, 0L).isEmpty());
        }
    }

    @Test
    void putThenGetReturnsBlock() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 1, 2, 3 });
            cache.put(1L, 0L, block);
            assertEquals(block, cache.get(1L, 0L).orElseThrow());
        }
    }

    @Test
    void getWrongSstableIdMisses() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(2L, 0L).isEmpty());
        }
    }

    @Test
    void getWrongOffsetMisses() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertTrue(cache.get(1L, 1L).isEmpty());
        }
    }

    @Test
    void putOverwritesExistingEntry() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
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
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            assertEquals(0, cache.size());
        }
    }

    @Test
    void sizeIncreasesAfterPut() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertEquals(1, cache.size());
        }
    }

    @Test
    void sizeDecreasesAfterEvict() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.size() < 2);
        }
    }

    @Test
    void capacityReflectsConfiguration() {
        try (var cache = LruBlockCache.builder().capacity(42).build()) {
            assertEquals(42, cache.capacity());
        }
    }

    // --- Validation ---

    @Test
    void negativeOffsetInGetRejected() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(1L, -1L));
        }
    }

    @Test
    void negativeOffsetInPutRejected() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.put(1L, -1L, MemorySegment.ofArray(new byte[]{ 1 })));
        }
    }

    @Test
    void nullBlockInPutRejected() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            assertThrows(NullPointerException.class, () -> cache.put(1L, 0L, null));
        }
    }

    // --- LRU eviction ---

    @Test
    void lruEntryEvictedWhenAtCapacity() {
        // capacity=2; insert A, B, then C → A must be gone
        try (var cache = LruBlockCache.builder().capacity(2).build()) {
            var a = MemorySegment.ofArray(new byte[]{ 1 });
            var b = MemorySegment.ofArray(new byte[]{ 2 });
            var c = MemorySegment.ofArray(new byte[]{ 3 });
            cache.put(1L, 0L, a);
            cache.put(1L, 1L, b);
            cache.put(1L, 2L, c); // triggers eviction of LRU entry (offset 0)
            assertTrue(cache.get(1L, 0L).isEmpty(), "LRU entry should have been evicted");
        }
    }

    @Test
    void recentlyAccessedEntryNotEvicted() {
        // capacity=2; insert A, B, access A (makes B LRU), insert C → B evicted, A survives
        try (var cache = LruBlockCache.builder().capacity(2).build()) {
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

    @Test
    void sizeStaysAtCapacityAfterLruEviction() {
        try (var cache = LruBlockCache.builder().capacity(3).build()) {
            for (int i = 0; i < 5; i++) {
                cache.put(1L, i, MemorySegment.ofArray(new byte[]{ (byte) i }));
            }
            assertEquals(3, cache.size());
        }
    }

    // --- SSTable evict ---

    @Test
    void evictRemovesAllBlocksForSstable() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.put(1L, 2L, MemorySegment.ofArray(new byte[]{ 3 }));
            cache.evict(1L);
            assertEquals(0, cache.size());
        }
    }

    @Test
    void evictDoesNotAffectOtherSstables() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            cache.put(2L, 0L, MemorySegment.ofArray(new byte[]{ 2 }));
            cache.evict(1L);
            assertTrue(cache.get(2L, 0L).isPresent());
        }
    }

    @Test
    void evictNonexistentSstableIsNoOp() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
            assertDoesNotThrow(() -> cache.evict(99L));
            assertEquals(1, cache.size());
        }
    }

    // --- getOrLoad ---

    @Test
    void getOrLoadCallsLoaderOnMiss() {
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
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
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
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
        try (var cache = LruBlockCache.builder().capacity(10).build()) {
            var block = MemorySegment.ofArray(new byte[]{ 42 });
            cache.getOrLoad(1L, 0L, () -> block);
            assertTrue(cache.get(1L, 0L).isPresent());
        }
    }

    // --- close ---

    @Test
    void closeDoesNotThrow() {
        var cache = LruBlockCache.builder().capacity(10).build();
        assertDoesNotThrow(cache::close);
    }

    // Updated by audit block-cache-hardening: close() now rejects use-after-close on all methods
    // including size(). The old test asserted size()==0 after close, but the correct behavior is
    // that size() throws IllegalStateException on a closed cache.
    @Test
    void closedCacheRejectsSize() {
        var cache = LruBlockCache.builder().capacity(10).build();
        cache.put(1L, 0L, MemorySegment.ofArray(new byte[]{ 1 }));
        cache.put(1L, 1L, MemorySegment.ofArray(new byte[]{ 2 }));
        cache.close();
        assertThrows(IllegalStateException.class, cache::size);
    }
}

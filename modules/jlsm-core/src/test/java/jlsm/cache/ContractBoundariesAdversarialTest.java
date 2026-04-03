package jlsm.cache;

import jlsm.core.cache.BlockCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ContractBoundariesAdversarialTest {

    /**
     * Minimal BlockCache that does NOT override getOrLoad, exposing the default
     * implementation for contract-boundary testing.
     */
    private static final class DefaultGetOrLoadCache implements BlockCache {
        private final ConcurrentHashMap<Long, MemorySegment> map = new ConcurrentHashMap<>();

        @Override
        public Optional<MemorySegment> get(long sstableId, long blockOffset) {
            return Optional.ofNullable(map.get(key(sstableId, blockOffset)));
        }

        @Override
        public void put(long sstableId, long blockOffset, MemorySegment block) {
            map.put(key(sstableId, blockOffset), block);
        }

        @Override
        public void evict(long sstableId) { map.clear(); }

        @Override
        public long size() { return map.size(); }

        @Override
        public long capacity() { return Long.MAX_VALUE; }

        @Override
        public void close() { map.clear(); }

        private static long key(long sstableId, long blockOffset) {
            return sstableId * 31 + blockOffset;
        }
    }

    // Finding: F-R1.cb.1.5
    // Bug: BlockCache.getOrLoad default does not null-check loader result
    // Correct behavior: NullPointerException("loader must not return null") when loader returns null
    // Fix location: BlockCache.getOrLoad default method — add null check after loader.get()
    // Regression watch: ensure non-null loader results still flow through correctly
    @Test
    void test_BlockCache_getOrLoad_default_rejectsNullLoaderResult() {
        var cache = new DefaultGetOrLoadCache();
        var ex = assertThrows(NullPointerException.class,
                () -> cache.getOrLoad(1L, 0L, () -> null),
                "default getOrLoad must throw NullPointerException when loader returns null");
        assertNotNull(ex.getMessage(),
                "exception must have a descriptive message, not an opaque NPE");
        assertTrue(ex.getMessage().contains("loader"),
                "exception message must mention 'loader' to identify the root cause");
    }

    // Finding: F-R1.cb.1.6
    // Bug: BlockCache.getOrLoad default does not validate loader for null eagerly
    // Correct behavior: NullPointerException("loader must not be null") thrown eagerly,
    //   even when the key is already cached (cache hit path)
    // Fix location: BlockCache.getOrLoad default method — add null check for loader parameter
    // Regression watch: ensure non-null loaders still work correctly
    @Test
    void test_BlockCache_getOrLoad_default_rejectsNullLoaderEagerly() {
        var cache = new DefaultGetOrLoadCache();
        // Pre-populate the cache so get() returns a hit — the loader lambda is never invoked
        var segment = Arena.ofAuto().allocate(8);
        cache.put(1L, 0L, segment);
        // A null loader must still be rejected eagerly, not deferred to the miss path
        assertThrows(NullPointerException.class,
                () -> cache.getOrLoad(1L, 0L, null),
                "default getOrLoad must reject null loader eagerly, " +
                "even on a cache hit where the loader would not be invoked");
    }

    // Finding: F-R1.cb.1.1
    // Bug: LruBlockCache.size() omits closed-state check, returning 0 after close()
    //      instead of throwing IllegalStateException like all other public methods
    // Correct behavior: size() should throw IllegalStateException when cache is closed
    // Fix location: LruBlockCache.size() — add closed-state check before lock acquisition
    // Regression watch: ensure size() still works correctly on open caches
    @Test
    void test_LruBlockCache_size_throwsAfterClose() {
        var cache = LruBlockCache.builder().capacity(10).build();
        cache.close();
        assertThrows(IllegalStateException.class, cache::size,
                "size() must throw IllegalStateException after close(), " +
                "consistent with get/put/getOrLoad/evict");
    }

    // Finding: F-R1.cb.1.4
    // Bug: LruBlockCache.capacity() omits closed-state check, returning the stored
    //      capacity after close() instead of throwing IllegalStateException like get/put/evict
    // Correct behavior: capacity() should throw IllegalStateException when cache is closed
    // Fix location: LruBlockCache.capacity() — add closed-state check before returning
    // Regression watch: ensure capacity() still returns correct value on open caches
    @Test
    void test_LruBlockCache_capacity_throwsAfterClose() {
        var cache = LruBlockCache.builder().capacity(10).build();
        assertEquals(10, cache.capacity(), "capacity() must return correct value while open");
        cache.close();
        assertThrows(IllegalStateException.class, cache::capacity,
                "capacity() must throw IllegalStateException after close(), " +
                "consistent with get/put/getOrLoad/evict");
    }

    // Finding: F-R1.cb.1.7
    // Bug: BlockCache.getOrLoad default is not atomic — get()+put() are separate operations,
    //      so concurrent callers can both miss on get() and both invoke the loader, violating
    //      the Javadoc contract "called exactly once on a cache miss"
    // Correct behavior: the loader must be invoked at most once per cache miss, even under
    //      concurrent access through the default implementation
    // Fix location: BlockCache.getOrLoad default method — add synchronization
    // Regression watch: ensure non-concurrent getOrLoad still works correctly
    @Test
    @Timeout(10)
    void test_BlockCache_getOrLoad_default_loaderCalledExactlyOnce() throws Exception {
        var cache = new DefaultGetOrLoadCache();
        var loaderCount = new AtomicInteger(0);
        var segment = Arena.ofAuto().allocate(8);
        int threadCount = 8;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    cache.getOrLoad(1L, 0L, () -> {
                        loaderCount.incrementAndGet();
                        return segment;
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

        assertEquals(1, loaderCount.get(),
                "default getOrLoad must invoke the loader exactly once per cache miss, " +
                "even under concurrent access — Javadoc says 'called exactly once on a cache miss'");
    }

    // Finding: F-R1.cb.1.3
    // Bug: StripedBlockCache.capacity() omits closed-state check, returning the stored
    //      capacity after close() instead of throwing IllegalStateException like get/put/evict
    // Correct behavior: capacity() should throw IllegalStateException when cache is closed
    // Fix location: StripedBlockCache.capacity() — add closed-state check before returning
    // Regression watch: ensure capacity() still returns correct value on open caches
    @Test
    void test_StripedBlockCache_capacity_throwsAfterClose() {
        var cache = StripedBlockCache.builder().capacity(64).build();
        cache.close();
        assertThrows(IllegalStateException.class, cache::capacity,
                "capacity() must throw IllegalStateException after close(), " +
                "consistent with get/put/getOrLoad/evict");
    }

}

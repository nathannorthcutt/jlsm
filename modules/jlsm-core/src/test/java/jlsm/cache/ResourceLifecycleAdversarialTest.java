package jlsm.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for resource lifecycle bugs in the cache subsystem.
 */
class ResourceLifecycleAdversarialTest {

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: StripedBlockCache.close() catches only RuntimeException — an Error thrown
    //      by stripe.close() propagates immediately, skipping close() for remaining stripes
    // Correct behavior: all stripes must be closed even if one throws an Error
    // Fix location: StripedBlockCache.close(), catch clause at line 197
    // Regression watch: ensure deferred exception pattern still accumulates RuntimeExceptions correctly
    @Test
    void test_StripedBlockCache_close_errorInStripeSkipsRemainingStripes() throws Exception {
        var cache = StripedBlockCache.builder().stripeCount(3).capacity(3).build();

        // Access the stripes array via reflection
        Field stripesField = StripedBlockCache.class.getDeclaredField("stripes");
        stripesField.setAccessible(true);
        LruBlockCache[] stripes = (LruBlockCache[]) stripesField.get(cache);

        // Replace stripe 0's internal map with one that throws AssertionError on clear()
        Field mapField = LruBlockCache.class.getDeclaredField("map");
        mapField.setAccessible(true);

        @SuppressWarnings("unchecked")
        var originalMap = (LinkedHashMap<Object, MemorySegment>) mapField.get(stripes[0]);

        var throwingMap = new LinkedHashMap<Object, MemorySegment>(originalMap) {
            @Override
            public void clear() {
                throw new AssertionError("simulated Error in stripe close");
            }
        };
        mapField.set(stripes[0], throwingMap);

        // Call close() — the Error from stripe 0 should NOT prevent stripes 1 and 2 from closing
        assertThrows(AssertionError.class, cache::close,
                "close() should propagate the Error after closing all stripes");

        // Verify remaining stripes were closed despite the Error in stripe 0
        Field closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);

        assertTrue((boolean) closedField.get(stripes[1]),
                "stripe 1 should be closed even when stripe 0 throws an Error");
        assertTrue((boolean) closedField.get(stripes[2]),
                "stripe 2 should be closed even when stripe 0 throws an Error");
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: LruBlockCache.getOrLoad holds the ReentrantLock during the external loader callback,
    //      blocking all other cache operations (get, put, evict, close) for the duration of the load
    // Correct behavior: the loader should execute outside the lock so concurrent cache operations
    //                    are not blocked during I/O
    // Fix location: LruBlockCache.getOrLoad(), lines 94-107
    // Regression watch: ensure the loaded value is still inserted atomically (no duplicate loads)
    @Test
    @Timeout(10)
    void test_LruBlockCache_getOrLoad_holdsLockDuringLoaderCallback() throws Exception {
        var cache = LruBlockCache.builder().capacity(10).build();

        // Pre-populate a different key so we can test get() concurrency
        var preloadedBlock = Arena.ofAuto().allocate(8, 8);
        cache.put(1L, 0L, preloadedBlock);

        var loaderStarted = new CountDownLatch(1);
        var loaderCanFinish = new CountDownLatch(1);
        var concurrentGetCompleted = new AtomicBoolean(false);

        // Thread 1: call getOrLoad with a loader that blocks until we signal it
        var loaderThread = Thread.ofVirtual().start(() -> {
            var block = Arena.ofAuto().allocate(8, 8);
            cache.getOrLoad(2L, 0L, () -> {
                loaderStarted.countDown();
                try {
                    loaderCanFinish.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return block;
            });
        });

        // Wait for loader to start executing
        assertTrue(loaderStarted.await(5, TimeUnit.SECONDS),
                "loader should have started");

        // Thread 2: try to get a DIFFERENT key while the loader is running
        var getThread = Thread.ofVirtual().start(() -> {
            cache.get(1L, 0L);
            concurrentGetCompleted.set(true);
        });

        // Give the get thread time to complete if the lock is NOT held
        getThread.join(500);

        // Assert: if the lock is held during loader, the get will be blocked
        // If the lock is NOT held (correct behavior), the get completes quickly
        assertTrue(concurrentGetCompleted.get(),
                "get() on a different key should not be blocked while loader executes — "
                + "lock must not be held during loader callback");

        // Clean up
        loaderCanFinish.countDown();
        loaderThread.join(5000);
    }
}

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for resource lifecycle bugs in the cache subsystem.
 */
class ResourceLifecycleAdversarialTest {

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: StripedBlockCache.close() catches only RuntimeException — an Error thrown
    // by stripe.close() propagates immediately, skipping close() for remaining stripes
    // Correct behavior: all stripes must be closed even if one throws an Error
    // Fix location: StripedBlockCache.close(), catch clause at line 197
    // Regression watch: ensure deferred exception pattern still accumulates RuntimeExceptions
    // correctly
    @Test
    void test_StripedBlockCache_close_errorInStripeSkipsRemainingStripes() throws Exception {
        var cache = StripedBlockCache.builder().stripeCount(4).expectedMinimumBlockSize(1L)
                .byteBudget(4L).build();

        // Access the stripes array via reflection
        Field stripesField = StripedBlockCache.class.getDeclaredField("stripes");
        stripesField.setAccessible(true);
        LruBlockCache[] stripes = (LruBlockCache[]) stripesField.get(cache);

        // Replace stripe 0's internal map with one that throws AssertionError during close()'s
        // iteration. The byte-budget close() iterates entrySet() to drain entries through the R7
        // removal chokepoint; hooking entrySet() is therefore the reliable injection point.
        Field mapField = LruBlockCache.class.getDeclaredField("map");
        mapField.setAccessible(true);

        @SuppressWarnings("unchecked")
        var originalMap = (LinkedHashMap<Object, MemorySegment>) mapField.get(stripes[0]);

        var throwingMap = new LinkedHashMap<Object, MemorySegment>(originalMap) {
            @Override
            public java.util.Set<Map.Entry<Object, MemorySegment>> entrySet() {
                throw new AssertionError("simulated Error in stripe close");
            }
        };
        mapField.set(stripes[0], throwingMap);

        // Call close() — the Error from stripe 0 should NOT prevent stripes 1-3 from closing
        assertThrows(AssertionError.class, cache::close,
                "close() should propagate the Error after closing all stripes");

        // Verify remaining stripes were closed despite the Error in stripe 0
        Field closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);

        for (int i = 1; i < stripes.length; i++) {
            assertTrue((boolean) closedField.get(stripes[i]),
                    "stripe " + i + " should be closed even when stripe 0 throws an Error");
        }
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: LruBlockCache.getOrLoad holds the ReentrantLock during the external loader callback,
    // blocking all other cache operations (get, put, evict, close) for the duration of the load
    // Correct behavior: the loader should execute outside the lock so concurrent cache operations
    // are not blocked during I/O
    // Fix location: LruBlockCache.getOrLoad(), lines 94-107
    // Regression watch: ensure the loaded value is still inserted atomically (no duplicate loads)
    @Test
    @Timeout(10)
    void test_LruBlockCache_getOrLoad_holdsLockDuringLoaderCallback() throws Exception {
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

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
        assertTrue(loaderStarted.await(5, TimeUnit.SECONDS), "loader should have started");

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

    // Finding: F-R1.resource_lifecycle.2.1
    // Bug: StripedBlockCache.<init> constructs stripes in a loop with no rollback; if the N-th
    // stripe construction throws, stripes [0..N-1] are already in the array but never closed —
    // the partially-constructed object is unreachable (exception propagated out of <init>), but
    // the stripe instances themselves are leaked without a close() call.
    // Correct behavior: on any Throwable from stripe construction, close all previously
    // constructed stripes (best-effort, suppressing inner failures onto the original throwable)
    // before re-throwing.
    // Fix location: StripedBlockCache.<init>(Builder), stripe construction loop at lines 88-90
    // Regression watch: successful construction path must remain unchanged; exception type must
    // be preserved (Error stays Error, RuntimeException stays RuntimeException).
    @Test
    void test_StripedBlockCache_init_partialConstructionFailureClosesPriorStripes()
            throws Exception {
        // Track which LruBlockCache instances the test factory produced so we can verify that the
        // successfully constructed ones were closed after the partial-construction failure.
        var producedStripes = new java.util.ArrayList<LruBlockCache>();
        var callCount = new AtomicInteger(0);

        LongFunction<LruBlockCache> failingFactory = perStripeBudget -> {
            int n = callCount.incrementAndGet();
            if (n == 3) {
                // Simulate OutOfMemoryError / any failure during the 3rd stripe's construction
                throw new RuntimeException("simulated failure constructing stripe " + (n - 1));
            }
            LruBlockCache stripe = LruBlockCache.builder().byteBudget(perStripeBudget).build();
            producedStripes.add(stripe);
            return stripe;
        };

        // Install the test seam — package-private so we can set it directly without reflection.
        StripedBlockCache.testStripeFactory = failingFactory;
        try {
            // stripeCount=4 (already a power of 2), so 4 stripe builds will be attempted;
            // the 3rd one (n=3) throws, leaving 2 successfully constructed stripes.
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> StripedBlockCache
                    .builder().stripeCount(4).expectedMinimumBlockSize(1L).byteBudget(8L).build());
            assertTrue(thrown.getMessage().contains("simulated failure"),
                    "original throwable should propagate, got: " + thrown);

            // The 2 stripes that were successfully constructed before the failure must have been
            // closed as part of the rollback — otherwise they are leaked.
            assertEquals(2, producedStripes.size(),
                    "factory should have produced 2 stripes before the 3rd threw");
            Field closedField = LruBlockCache.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            for (int i = 0; i < producedStripes.size(); i++) {
                assertTrue((boolean) closedField.get(producedStripes.get(i)), "stripe " + i
                        + " should be closed by partial-construction rollback; leak!");
            }
        } finally {
            StripedBlockCache.testStripeFactory = null;
        }
    }
}

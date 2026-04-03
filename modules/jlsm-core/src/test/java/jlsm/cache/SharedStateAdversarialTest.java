package jlsm.cache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for shared-state concerns in StripedBlockCache.
 */
class SharedStateAdversarialTest {

    // Finding: F-R1.shared_state.1.1
    // Bug: Builder.build() error message misleading when capacity not set — says
    //      "capacity must be at least stripeCount (N), got: -1" instead of indicating
    //      that capacity was never configured.
    // Correct behavior: The error message should clearly state that capacity was not set.
    // Fix location: StripedBlockCache.Builder.build(), capacity validation block
    // Regression watch: Ensure the capacity-too-small case (capacity explicitly set but < stripeCount)
    //                   still produces the original message.
    // Finding: F-R1.shared_state.1.2
    // Bug: Constructor uses assert-only guards for stripeCount > 0 and capacity >= stripeCount.
    //      With assertions enabled, these throw AssertionError (not IllegalArgumentException).
    //      With assertions disabled (production), stripeCount=0 causes ArithmeticException
    //      (division by zero) and stripeCount<0 causes NegativeArraySizeException.
    // Correct behavior: Constructor should use runtime checks (if/throw IllegalArgumentException)
    //      so that invalid state always produces a clear, informative exception regardless of -ea.
    // Fix location: StripedBlockCache constructor, lines 37-38
    // Regression watch: Ensure normal builder path still works after adding runtime checks.
    @Test
    void test_constructor_assertOnlyGuards_throwsIllegalArgumentNotAssertionError() throws Exception {
        // Use reflection to bypass build() validation and invoke the private constructor
        // with a Builder that has stripeCount=0, which should trigger the guard.
        var builder = StripedBlockCache.builder();

        // Use reflection to set stripeCount to 0 on the builder, bypassing the setter validation
        Field stripeCountField = StripedBlockCache.Builder.class.getDeclaredField("stripeCount");
        stripeCountField.setAccessible(true);
        stripeCountField.set(builder, 0);

        // Set capacity to something valid so only the stripeCount guard is tested
        Field capacityField = StripedBlockCache.Builder.class.getDeclaredField("capacity");
        capacityField.setAccessible(true);
        capacityField.set(builder, 100L);

        // Get the private constructor
        Constructor<StripedBlockCache> ctor = StripedBlockCache.class.getDeclaredConstructor(
                StripedBlockCache.Builder.class);
        ctor.setAccessible(true);

        // Invoke: with assert-only guards, this throws AssertionError (with -ea)
        // or ArithmeticException (without -ea). After fix, it should throw
        // IllegalArgumentException.
        var ex = assertThrows(InvocationTargetException.class, () -> ctor.newInstance(builder));
        Throwable cause = ex.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause,
                "Constructor should throw IllegalArgumentException for invalid stripeCount, "
                        + "but threw " + cause.getClass().getName() + ": " + cause.getMessage());
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: stripeIndex uses assert-only guard for stripeCount > 0. With assertions
    //      disabled, stripeCount=0 causes ArithmeticException (% 0) instead of a clear
    //      IllegalArgumentException. Method is package-private, accessible to any class
    //      in jlsm.cache.
    // Correct behavior: Should throw IllegalArgumentException with informative message
    //      when stripeCount <= 0, regardless of -ea flag.
    // Fix location: StripedBlockCache.stripeIndex(), line 71
    // Regression watch: Ensure normal positive stripeCount calls still work after adding guard.
    @Test
    void test_stripeIndex_zeroStripeCount_throwsIllegalArgumentException() {
        // stripeIndex is package-private static — directly callable from this test class
        var ex = assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.stripeIndex(1L, 0L, 0));
        assertTrue(ex.getMessage().contains("stripeCount"),
                "Exception message should mention stripeCount; got: " + ex.getMessage());
    }

    @Test
    void test_Builder_build_capacityNotSet_errorMessageIndicatesCapacityNotSet() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> StripedBlockCache.builder().build());
        String msg = ex.getMessage();
        // The message must NOT say "got: -1" — that implies the caller set -1.
        // It must indicate that capacity was not set.
        assertFalse(msg.contains("got: -1"),
                "Error message should not say 'got: -1' — capacity was never set, not set to -1");
        assertTrue(msg.toLowerCase().contains("capacity") && msg.toLowerCase().contains("not set"),
                "Error message should indicate capacity was not set; got: " + msg);
    }

    // Finding: F-R1.shared_state.2.1
    // Bug: TOCTOU on closed flag allows put() after close(), leaking entries into a closed cache
    // Correct behavior: put() must throw IllegalStateException if close() has been called,
    //                   even when the close() races with the put() call
    // Fix location: LruBlockCache.put(), add closed re-check inside the lock
    // Regression watch: Ensure put() still works normally when cache is open
    @Test
    @Timeout(10)
    void test_put_closedRace_neverInsertsIntoClosed() throws Exception {
        // Strategy: force the exact TOCTOU interleaving deterministically.
        //
        // The bug scenario (in order):
        //   1. put() reads closed == false (passes the guard)
        //   2. close() sets closed = true, acquires lock, clears map, releases lock
        //   3. put() acquires lock, inserts into the cleared map — leaked entry
        //
        // To force this: the test thread holds the cache's lock, then starts
        // close() on another thread. close() sets closed=true BEFORE acquiring
        // the lock (that's the current implementation), so it sets the flag
        // immediately then blocks waiting for our lock. We then release the
        // lock, let close() clear the map, and THEN call put(). But put()
        // would see closed=true and throw — that's the NORMAL case, not TOCTOU.
        //
        // The TOCTOU requires put() to have already read closed=false. Since
        // we can't pause between machine instructions, we test the invariant
        // with high concurrency: many threads calling put() while one calls
        // close(). After all threads finish, the map must be empty.

        final int iterations = 1000;
        int leakCount = 0;

        for (int i = 0; i < iterations; i++) {
            var cache = LruBlockCache.builder().capacity(100).build();
            var block = Arena.ofAuto().allocate(8, 8);
            var barrier = new CyclicBarrier(9); // 8 putters + 1 closer

            var putSucceeded = new AtomicBoolean(false);

            // Start 8 putter threads
            var putters = new Thread[8];
            for (int t = 0; t < 8; t++) {
                final int offset = t;
                putters[t] = Thread.ofPlatform().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        cache.put(1, offset, block);
                        putSucceeded.set(true);
                    } catch (IllegalStateException _) {
                        // Expected — cache was closed
                    } catch (Exception _) {
                        // Barrier timeout or interruption
                    }
                });
            }

            // Start 1 closer thread
            var closer = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    cache.close();
                } catch (Exception _) {
                    // Barrier timeout or interruption
                }
            });

            // Wait for all threads
            for (var p : putters) p.join(5000);
            closer.join(5000);

            // Invariant: after close() has returned, the map must be empty.
            // If any put() sneaked through the TOCTOU window and inserted AFTER
            // close() cleared the map, the map is non-empty.
            if (putSucceeded.get()) {
                Field mapField = LruBlockCache.class.getDeclaredField("map");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<?, ?>) mapField.get(cache);
                if (!map.isEmpty()) {
                    leakCount++;
                }
            }
        }

        assertEquals(0, leakCount,
                leakCount + " out of " + iterations + " iterations leaked entries "
                        + "into a closed cache via put() TOCTOU");
    }

    // Finding: F-R1.shared_state.2.2
    // Bug: TOCTOU on closed flag allows getOrLoad() after close(), executing loader
    //      and inserting into closed cache. getOrLoad() checks closed outside the lock,
    //      then acquires the lock and proceeds without re-checking closed.
    // Correct behavior: getOrLoad() must throw IllegalStateException if close() has been
    //                   called, even when close() races with the getOrLoad() call.
    //                   The loader must NOT execute after close().
    // Fix location: LruBlockCache.getOrLoad(), add closed re-check inside both lock regions
    // Regression watch: Ensure getOrLoad() still works normally when cache is open
    @Test
    @Timeout(10)
    void test_getOrLoad_closedRace_neverInsertsIntoClosed() throws Exception {
        // Same TOCTOU pattern as F-R1.shared_state.2.1 but with getOrLoad.
        // getOrLoad has two lock acquisitions (lookup, then insert) and loads
        // outside the lock. Neither re-checks closed. After close() clears the
        // map, a getOrLoad that passed the initial closed check can execute the
        // loader and insert into the cleared map.

        final int iterations = 1000;
        int leakCount = 0;
        var loaderAfterCloseCount = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            var cache = LruBlockCache.builder().capacity(100).build();
            var block = Arena.ofAuto().allocate(8, 8);
            var barrier = new CyclicBarrier(9); // 8 loaders + 1 closer
            var closeCalled = new AtomicBoolean(false);

            var loadSucceeded = new AtomicBoolean(false);

            // Start 8 getOrLoad threads
            var loaders = new Thread[8];
            for (int t = 0; t < 8; t++) {
                final int offset = t;
                loaders[t] = Thread.ofPlatform().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        cache.getOrLoad(1, offset, () -> {
                            if (closeCalled.get()) {
                                loaderAfterCloseCount.incrementAndGet();
                            }
                            return block;
                        });
                        loadSucceeded.set(true);
                    } catch (IllegalStateException _) {
                        // Expected — cache was closed
                    } catch (Exception _) {
                        // Barrier timeout or interruption
                    }
                });
            }

            // Start 1 closer thread
            var closer = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    closeCalled.set(true);
                    cache.close();
                } catch (Exception _) {
                    // Barrier timeout or interruption
                }
            });

            // Wait for all threads
            for (var l : loaders) l.join(5000);
            closer.join(5000);

            // Invariant: after close() has returned, the map must be empty.
            if (loadSucceeded.get()) {
                Field mapField = LruBlockCache.class.getDeclaredField("map");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<?, ?>) mapField.get(cache);
                if (!map.isEmpty()) {
                    leakCount++;
                }
            }
        }

        assertEquals(0, leakCount,
                leakCount + " out of " + iterations + " iterations leaked entries "
                        + "into a closed cache via getOrLoad() TOCTOU");
    }

    // Finding: F-R1.shared_state.2.4
    // Bug: Constructor relies on assert-only guard for capacity — disabled in production.
    //      With assertions disabled, capacity <= 0 silently creates a degenerate cache.
    // Correct behavior: Constructor should throw IllegalArgumentException for capacity <= 0
    //      regardless of -ea flag.
    // Fix location: LruBlockCache constructor, line 36
    // Regression watch: Ensure normal builder path still works after adding runtime check.
    @Test
    void test_LruBlockCache_constructor_assertOnlyCapacityGuard_throwsIllegalArgument()
            throws Exception {
        // Bypass Builder validation via reflection to invoke the private constructor
        // with a Builder that has capacity = 0.
        var builder = LruBlockCache.builder();

        // Set capacity to 0 via reflection, bypassing the setter's validation
        Field capacityField = LruBlockCache.Builder.class.getDeclaredField("capacity");
        capacityField.setAccessible(true);
        capacityField.set(builder, 0L);

        // Get the private constructor
        Constructor<LruBlockCache> ctor = LruBlockCache.class.getDeclaredConstructor(
                LruBlockCache.Builder.class);
        ctor.setAccessible(true);

        // With assert-only guard: throws AssertionError (with -ea) or succeeds silently
        // (without -ea). After fix: should throw IllegalArgumentException.
        var ex = assertThrows(InvocationTargetException.class, () -> ctor.newInstance(builder));
        Throwable cause = ex.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause,
                "LruBlockCache constructor should throw IllegalArgumentException for capacity <= 0, "
                        + "but threw " + cause.getClass().getName() + ": " + cause.getMessage());
    }

    // Finding: F-R1.shared_state.2.5
    // Bug: close() sets volatile closed flag before acquiring lock — get() sees closed=true
    //      and throws IllegalStateException even though the map still has valid data (not yet cleared)
    // Correct behavior: close() should set closed=true atomically with the map.clear() (inside the lock),
    //      so that no concurrent operation sees an inconsistent state (closed=true but map populated)
    // Fix location: LruBlockCache.close(), move closed=true inside the lock
    // Regression watch: Ensure put()/getOrLoad() re-checks inside the lock still catch concurrent close
    @Test
    @Timeout(10)
    void test_close_setsClosedBeforeLock_getThrowsPrematurely() throws Exception {
        // Deterministic interleaving:
        // 1. Put a value into the cache
        // 2. Test thread acquires the cache's internal lock (via reflection)
        // 3. Start close() on another thread — it sets closed=true, then blocks on lock
        // 4. Call get() on the main thread (without holding the lock) — with the bug,
        //    get() sees closed=true and throws even though the map still has the value
        // 5. Release the lock
        //
        // After fix (closed=true inside the lock), close() does NOT set closed=true
        // until it acquires the lock, so get() in step 4 sees closed=false and succeeds.

        var cache = LruBlockCache.builder().capacity(10).build();
        var block = Arena.ofAuto().allocate(8, 8);
        cache.put(1, 0, block);

        // Grab the internal lock via reflection
        Field lockField = LruBlockCache.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var internalLock = (ReentrantLock) lockField.get(cache);

        // Hold the lock so close() can't proceed past setting the flag
        internalLock.lock();
        try {
            var closeStarted = new CountDownLatch(1);
            var closerThread = Thread.ofPlatform().start(() -> {
                closeStarted.countDown();
                cache.close(); // sets closed=true, then blocks waiting for lock
            });

            // Wait for close() to have been called (it will set closed=true then block)
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS), "closer thread did not start");
            // Give close() a moment to set the flag and block on the lock
            Thread.sleep(50);

            // Now get() — with the bug, this throws IllegalStateException because
            // closed is already true, even though the map still has our entry.
            // After fix, closed is still false here (set inside the lock which we hold).
            var result = cache.get(1, 0);
            assertTrue(result.isPresent(),
                    "get() should return the cached value while close() is waiting for the lock, "
                            + "but it threw or returned empty because closed was set prematurely");
        } finally {
            internalLock.unlock();
        }
    }

    // Finding: F-R1.shared_state.2.3
    // Bug: size() callable after close() — inconsistent closed-state API behavior.
    //      LruBlockCache.size() had no closed guard, returning 0 silently after close()
    //      while all other methods (get/put/getOrLoad/evict) throw IllegalStateException.
    //      StripedBlockCache.size() also lacks its own closed guard.
    // Correct behavior: size() must throw IllegalStateException when cache is closed,
    //      consistent with all other public methods on both LruBlockCache and StripedBlockCache.
    // Fix location: LruBlockCache.size() — add closed check; StripedBlockCache.size() — add closed check
    // Regression watch: ensure size() still returns correct value on open caches
    @Test
    void test_size_throwsAfterClose_lruAndStriped() {
        // LruBlockCache: size() after close() must throw
        var lru = LruBlockCache.builder().capacity(10).build();
        lru.close();
        assertThrows(IllegalStateException.class, lru::size,
                "LruBlockCache.size() must throw IllegalStateException after close()");

        // StripedBlockCache: size() after close() must throw at the StripedBlockCache level
        var striped = StripedBlockCache.builder().stripeCount(2).capacity(10).build();
        striped.close();
        assertThrows(IllegalStateException.class, striped::size,
                "StripedBlockCache.size() must throw IllegalStateException after close()");
    }
}

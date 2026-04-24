package jlsm.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency-lens adversarial tests for cache components.
 */
class ConcurrencyAdversarialTest {

    // Finding: F-R1.conc.1.1
    // Bug: StripedBlockCache.size() missing use-after-close guard — the closed check
    // is absent, unlike get/put/getOrLoad/evict/capacity which all check the volatile
    // closed flag before delegating to stripes. Without the guard, the exception
    // originates from LruBlockCache.size() at the stripe level, not StripedBlockCache.
    // Correct behavior: size() must throw IllegalStateException with guard at StripedBlockCache
    // level, consistent with all other public methods. The exception's first stack frame
    // should be StripedBlockCache.size, not LruBlockCache.size.
    // Fix location: StripedBlockCache.size() — add if (closed) throw guard before stripe iteration
    // Regression watch: ensure size() still returns correct value on open caches
    @Test
    void test_StripedBlockCache_size_closedGuardAtStripedLevel() {
        var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(1_000_000L).build();
        cache.close();

        var ex = assertThrows(IllegalStateException.class, cache::size,
                "size() must throw IllegalStateException after close()");

        // The guard must be at the StripedBlockCache level, not delegated to LruBlockCache.
        // If the guard is missing from StripedBlockCache.size(), the exception originates
        // from LruBlockCache.size() — the first element in the stack trace will be
        // LruBlockCache.size instead of StripedBlockCache.size.
        StackTraceElement origin = ex.getStackTrace()[0];
        assertEquals("StripedBlockCache",
                origin.getClassName().substring(origin.getClassName().lastIndexOf('.') + 1),
                "IllegalStateException must originate from StripedBlockCache.size(), "
                        + "not from a stripe-level LruBlockCache. Actual origin: "
                        + origin.getClassName() + "." + origin.getMethodName());
    }

    // Finding: F-R1.conc.1.2
    // Bug: TOCTOU race between StripedBlockCache closed-check and stripe operation.
    // Thread A reads closed==false at the StripedBlockCache level, then is preempted.
    // Thread B calls close(), setting closed=true and closing all stripes. Thread A
    // resumes and delegates to the stripe, which throws ISE from LruBlockCache's guard
    // instead of StripedBlockCache's guard. Under high contention, callers observe ISE
    // originating from LruBlockCache rather than StripedBlockCache.
    // Correct behavior: When the TOCTOU race is hit, the ISE should still originate from
    // StripedBlockCache, not leak from the stripe level. All ISEs a caller sees must
    // come from StripedBlockCache.
    // Fix location: StripedBlockCache.get/put/getOrLoad/evict — catch stripe-level ISE and
    // re-throw with StripedBlockCache-level origin, or synchronize close with operations
    // Regression watch: ensure normal (non-racing) operations still work correctly
    @Test
    @Timeout(10)
    void test_StripedBlockCache_toctou_closedCheckRaceExceptionOrigin() throws Exception {
        // Strategy: Use many threads racing get() against close() to hit the TOCTOU window.
        // When the race is hit, the ISE originates from LruBlockCache instead of
        // StripedBlockCache. We detect this by inspecting exception stack traces.
        //
        // We run many iterations because the race window is narrow (single volatile read).
        final int iterations = 500;
        final int operationThreads = 4;
        var stripeLeakDetected = new AtomicReference<StackTraceElement>();

        for (int iter = 0; iter < iterations && stripeLeakDetected.get() == null; iter++) {
            var cache = StripedBlockCache.builder().stripeCount(2).byteBudget(1_000_000L).build();
            // Pre-populate so get() hits a valid stripe path
            try (var arena = Arena.ofConfined()) {
                var block = arena.allocate(64);
                cache.put(1L, 0L, block);
            }

            var barrier = new CyclicBarrier(operationThreads + 1);
            var threads = new Thread[operationThreads];

            for (int t = 0; t < operationThreads; t++) {
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        // Tight loop calling get() — hoping to be between closed-check
                        // and stripe delegation when close() runs
                        for (int spin = 0; spin < 100; spin++) {
                            try {
                                cache.get(1L, 0L);
                            } catch (IllegalStateException e) {
                                // Check if this ISE leaked from stripe level
                                StackTraceElement origin = e.getStackTrace()[0];
                                String simpleClass = origin.getClassName()
                                        .substring(origin.getClassName().lastIndexOf('.') + 1);
                                if (!"StripedBlockCache".equals(simpleClass)) {
                                    stripeLeakDetected.compareAndSet(null, origin);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            }

            // Release all threads, then close immediately to race
            barrier.await();
            cache.close();

            for (var thread : threads) {
                thread.join(2000);
            }
        }

        assertNull(stripeLeakDetected.get(),
                "TOCTOU race: IllegalStateException leaked from stripe level. "
                        + "Expected origin: StripedBlockCache, but got: "
                        + (stripeLeakDetected.get() != null
                                ? stripeLeakDetected.get().getClassName() + "."
                                        + stripeLeakDetected.get().getMethodName()
                                : "n/a"));
    }

    // Finding: F-R1.concurrency.2.1
    // Bug: TOCTOU race — closed check outside lock allows get() on closed cache.
    // Thread A reads closed==false, Thread B closes cache (sets closed=true,
    // clears map under lock), Thread A acquires lock and runs map.get() returning
    // Optional.empty() instead of throwing IllegalStateException.
    // Correct behavior: get() must throw IllegalStateException after close(), even
    // when the close races with the get.
    // Fix location: LruBlockCache.get() — add closed re-check inside the lock
    // Regression watch: ensure get() still returns values on open caches
    @Test
    @Timeout(10)
    void test_LruBlockCache_get_toctouClosedCheckRace() throws Exception {
        // Strategy: deterministic interleaving via reflection.
        // 1. Test thread acquires the cache's internal lock (via reflection).
        // 2. Thread A calls get() — passes the outer closed check (closed==false),
        // then blocks on lock.lock().
        // 3. Test thread sets closed=true (via reflection) and releases lock.
        // 4. Thread A acquires the lock. Without an inner closed check, it proceeds
        // to map.get() on the (still populated but logically closed) cache.
        // With an inner closed check, it throws ISE.
        // 5. We observe whether Thread A threw ISE or returned normally.
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

        // Obtain internal lock and closed field via reflection
        var lockField = LruBlockCache.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var internalLock = (java.util.concurrent.locks.ReentrantLock) lockField.get(cache);

        var closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);

        // Step 1: test thread holds the lock
        internalLock.lock();

        var getResult = new AtomicReference<String>(); // "ok" or "ise"
        var threadPassedOuterCheck = new CountDownLatch(1);

        // Step 2: Thread A calls get() — will pass outer check then block on lock
        var threadA = Thread.ofVirtual().start(() -> {
            try {
                // Signal that we're about to call get() (outer check will pass
                // since closed is still false)
                threadPassedOuterCheck.countDown();
                cache.get(1L, 0L);
                getResult.set("ok");
            } catch (IllegalStateException _) {
                getResult.set("ise");
            }
        });

        // Wait for Thread A to start and pass the outer check
        threadPassedOuterCheck.await();
        // Wait deterministically for Thread A to be parked on internalLock.
        // hasQueuedThreads() reads AQS state directly — no polling jitter.
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!internalLock.hasQueuedThreads() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertTrue(internalLock.hasQueuedThreads(),
                "Thread A did not queue on internalLock within 1s — get() may "
                        + "have returned without attempting the lock");

        // Step 3: set closed=true while we hold the lock, then release
        closedField.setBoolean(cache, true);
        internalLock.unlock();

        // Step 4: Thread A now acquires the lock with closed==true
        threadA.join(5000);

        // Step 5: verify that Thread A got ISE, not a successful return
        assertEquals("ise", getResult.get(),
                "TOCTOU race: get() returned normally on a closed cache instead of "
                        + "throwing IllegalStateException. The closed check must be "
                        + "re-verified inside the lock.");
    }

    // Finding: F-R1.concurrency.2.3
    // Bug: TOCTOU race — getOrLoad invokes the loader on a closed cache. After
    // the first lock section releases the lock (cache miss), close() can run
    // before the loader executes. The loader performs I/O for a dead cache.
    // Even though the second lock section re-checks closed, the unnecessary
    // I/O has already occurred — the contract says use-after-close must throw
    // ISE, not invoke the loader and then throw ISE.
    // Correct behavior: getOrLoad() must re-check closed after the first lock
    // release and before invoking the loader, throwing ISE immediately.
    // Fix location: LruBlockCache.getOrLoad() — add closed check between first
    // lock release and loader invocation
    // Regression watch: ensure getOrLoad() still loads on cache misses for open caches
    @Test
    @Timeout(10)
    void test_LruBlockCache_getOrLoad_toctouClosedLoaderInvocation() throws Exception {
        // Strategy: deterministic interleaving via reflection.
        //
        // Thread A calls getOrLoad() — passes the outer closed check (closed==false),
        // then blocks waiting for the internal lock (held by the test thread). While
        // Thread A is blocked, the test thread sets closed=true via reflection
        // (simulating close() running on another thread) and releases the lock.
        // Thread A then acquires the lock and proceeds through the first lock section.
        //
        // The inner closed check at the first lock section catches closed==true and
        // throws ISE before the loader is invoked. This validates the TOCTOU defense:
        // closed checks inside lock sections prevent the loader from executing on a
        // closed cache, regardless of when close() runs.
        //
        // The gap check (volatile read of closed between the first lock release and
        // loader.get()) provides additional defense-in-depth for the case where
        // closed is set AFTER the first lock section exits. That specific window
        // cannot be tested deterministically without instrumenting production code,
        // but this test confirms the overall contract: the loader is never invoked
        // when the cache is closed.

        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

        var closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);
        var lockField = LruBlockCache.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var internalLock = (java.util.concurrent.locks.ReentrantLock) lockField.get(cache);

        var loaderInvoked = new AtomicBoolean(false);
        var threadReady = new CountDownLatch(1);
        var threadResult = new AtomicReference<String>();

        // Step 1: test thread holds the lock
        internalLock.lock();

        // Step 2: Thread A calls getOrLoad — passes outer closed check (closed==false),
        // blocks on lock.lock() in the first lock section
        var threadA = Thread.ofVirtual().start(() -> {
            threadReady.countDown();
            try {
                cache.getOrLoad(99L, 0L, () -> {
                    loaderInvoked.set(true);
                    return Arena.ofAuto().allocate(64);
                });
                threadResult.set("ok");
            } catch (IllegalStateException _) {
                threadResult.set("ise");
            }
        });

        threadReady.await();
        // Wait deterministically for Thread A to be parked on internalLock.
        // hasQueuedThreads() reads AQS state directly — no polling jitter.
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!internalLock.hasQueuedThreads() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertTrue(internalLock.hasQueuedThreads(),
                "Thread A did not queue on internalLock within 1s — getOrLoad() may "
                        + "have returned without attempting the first-section lock");

        // Step 3: set closed=true via reflection (simulates close() from another thread),
        // then release the lock. Thread A will acquire the lock and see closed==true.
        closedField.setBoolean(cache, true);
        internalLock.unlock();

        threadA.join(5000);

        // Thread A must have thrown ISE without invoking the loader
        assertEquals("ise", threadResult.get(),
                "getOrLoad must throw ISE when closed becomes true before the first "
                        + "lock section completes — the TOCTOU defense (inner closed check "
                        + "or gap check) must prevent loader invocation");
        assertFalse(loaderInvoked.get(),
                "TOCTOU race: getOrLoad() invoked the loader on a closed cache. "
                        + "A closed re-check must exist after the outer check to prevent "
                        + "the loader from executing on a closed cache.");
    }

    // Finding: F-R1.concurrency.2.6
    // Bug: size() has no inner closed re-check inside the lock — TOCTOU race.
    // Thread A reads closed==false at the outer check, Thread B closes cache
    // (sets closed=true, clears map under lock), Thread A acquires lock and
    // returns map.size() (0) instead of throwing IllegalStateException.
    // All other methods (get, put, getOrLoad, evict) re-check closed inside
    // the lock, making size() inconsistent.
    // Correct behavior: size() must throw IllegalStateException after close(),
    // even when close races with size — the closed flag must be re-checked
    // inside the lock.
    // Fix location: LruBlockCache.size() — add closed re-check inside the lock
    // Regression watch: ensure size() still returns correct values on open caches
    @Test
    @Timeout(10)
    void test_LruBlockCache_size_toctouClosedCheckRace() throws Exception {
        // Strategy: deterministic interleaving via reflection (same as 2.1/2.4).
        // 1. Test thread acquires the cache's internal lock.
        // 2. Thread A calls size() — passes the outer closed check (closed==false),
        // then blocks on lock.lock().
        // 3. Test thread sets closed=true (via reflection) and releases lock.
        // 4. Thread A acquires the lock. Without an inner closed check, it proceeds
        // to return map.size(). With an inner closed check, it throws ISE.
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

        var lockField = LruBlockCache.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var internalLock = (java.util.concurrent.locks.ReentrantLock) lockField.get(cache);

        var closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);

        // Step 1: test thread holds the lock
        internalLock.lock();

        var sizeResult = new AtomicReference<String>(); // "ok" or "ise"
        var threadReady = new CountDownLatch(1);

        // Step 2: Thread A calls size() — will pass outer check then block on lock
        var threadA = Thread.ofVirtual().start(() -> {
            threadReady.countDown();
            try {
                cache.size();
                sizeResult.set("ok");
            } catch (IllegalStateException _) {
                sizeResult.set("ise");
            }
        });

        // Wait for Thread A to start and pass the outer check
        threadReady.await();
        // Wait deterministically for Thread A to be parked on internalLock.
        // hasQueuedThreads() reads AQS state directly — no polling jitter.
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!internalLock.hasQueuedThreads() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertTrue(internalLock.hasQueuedThreads(),
                "Thread A did not queue on internalLock within 1s — size() may "
                        + "have returned without attempting the lock");

        // Step 3: set closed=true while we hold the lock, then release
        closedField.setBoolean(cache, true);
        internalLock.unlock();

        // Step 4: Thread A now acquires the lock with closed==true
        threadA.join(5000);

        // Step 5: verify that Thread A got ISE, not a successful return
        assertEquals("ise", sizeResult.get(),
                "TOCTOU race: size() returned normally on a closed cache instead of "
                        + "throwing IllegalStateException. The closed check must be "
                        + "re-verified inside the lock.");
    }

    // Finding: F-R1.concurrency.1.2
    // Bug: subtractBytes uses an assertion-only guard (assert currentBytes >= 0) on a
    // safety-critical invariant. Under production settings (-da, no -ea), the assertion
    // is disabled. If any path drives currentBytes negative (double-subtract bug, state
    // drift, or corruption), the eviction loop's `if (currentBytes > byteBudget)` at
    // line 274 silently becomes permanently false (negative < positive), so the cache
    // grows unbounded until the R28a entry-count cap fires. This violates the project's
    // coding-guidelines.md rule: "asserts must never be the sole mechanism satisfying
    // a spec requirement".
    // Correct behavior: subtractBytes must enforce the non-negative invariant at runtime
    // (throw IllegalStateException) so the failure is visible regardless of -ea/-da.
    // Fix location: LruBlockCache.subtractBytes — replace assert with runtime check
    // Regression watch: ensure normal eviction paths (put with overflow, evict, close)
    // still work when the invariant holds.
    @Test
    @Timeout(10)
    void test_LruBlockCache_subtractBytes_runtimeEnforcesNonNegativeInvariant() throws Exception {
        // Corrupt currentBytes via reflection to simulate an accounting drift bug, then
        // invoke evict() (which routes through subtractBytes via the R7 chokepoint).
        // With the assertion-only guard, under -ea the code produces AssertionError; under
        // -da it silently produces a negative currentBytes. The correct behavior is a
        // runtime IllegalStateException that fires regardless of -ea/-da.
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

        try (var arena = Arena.ofConfined()) {
            var block = arena.allocate(1024);
            cache.put(1L, 0L, block);

            // Corrupt currentBytes: set it below the entry's byteSize so the subtraction
            // during evict() will drive it negative.
            var currentBytesField = LruBlockCache.class.getDeclaredField("currentBytes");
            currentBytesField.setAccessible(true);
            currentBytesField.setLong(cache, 100L); // entry is 1024 bytes, field is 100

            // evict() routes removal through subtractBytes; the subtraction will drive
            // currentBytes negative, which must be caught by a runtime check (not an
            // assertion-only guard).
            var ex = assertThrows(IllegalStateException.class, () -> cache.evict(1L),
                    "subtractBytes must enforce currentBytes >= 0 with a runtime check "
                            + "(IllegalStateException), not an assertion-only guard. "
                            + "The assertion is disabled in production and permits silent "
                            + "unbounded cache growth when accounting drifts.");

            assertNotNull(ex.getMessage(),
                    "IllegalStateException must carry a descriptive message identifying the "
                            + "byte-accounting invariant violation");
        } finally {
            // Clean up: cache is in a corrupted state; close it to release resources.
            try {
                cache.close();
            } catch (Throwable ignored) {
                // close may itself route through subtractBytes and trip the same guard;
                // the test's concern is the runtime enforcement, not clean shutdown of a
                // deliberately corrupted cache.
            }
        }
    }

    // Finding: F-R1.concurrency.2.4
    // Bug: TOCTOU race — evict() has no inner closed check inside the lock.
    // Thread A reads closed==false at the outer check, Thread B closes cache
    // (sets closed=true, clears map under lock), Thread A acquires lock and
    // runs removeIf on the map instead of throwing IllegalStateException.
    // Correct behavior: evict() must throw IllegalStateException after close(),
    // even when close races with evict — the closed flag must be re-checked
    // inside the lock.
    // Fix location: LruBlockCache.evict() — add closed re-check inside the lock
    // Regression watch: ensure evict() still removes entries on open caches
    @Test
    @Timeout(10)
    void test_LruBlockCache_evict_toctouClosedCheckRace() throws Exception {
        // Strategy: deterministic interleaving via reflection (same as 2.1).
        // 1. Test thread acquires the cache's internal lock.
        // 2. Thread A calls evict() — passes the outer closed check (closed==false),
        // then blocks on lock.lock().
        // 3. Test thread sets closed=true (via reflection) and releases lock.
        // 4. Thread A acquires the lock. Without an inner closed check, it proceeds
        // to removeIf on the map. With an inner closed check, it throws ISE.
        var cache = LruBlockCache.builder().byteBudget(1_000_000L).build();

        var lockField = LruBlockCache.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var internalLock = (java.util.concurrent.locks.ReentrantLock) lockField.get(cache);

        var closedField = LruBlockCache.class.getDeclaredField("closed");
        closedField.setAccessible(true);

        // Step 1: test thread holds the lock
        internalLock.lock();

        var evictResult = new AtomicReference<String>(); // "ok" or "ise"
        var threadReady = new CountDownLatch(1);

        // Step 2: Thread A calls evict() — will pass outer check then block on lock
        var threadA = Thread.ofVirtual().start(() -> {
            threadReady.countDown();
            try {
                cache.evict(1L);
                evictResult.set("ok");
            } catch (IllegalStateException _) {
                evictResult.set("ise");
            }
        });

        // Wait for Thread A to start and pass the outer check
        threadReady.await();
        // Wait deterministically for Thread A to be parked on internalLock.
        // hasQueuedThreads() reads AQS state directly — no polling jitter.
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!internalLock.hasQueuedThreads() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertTrue(internalLock.hasQueuedThreads(),
                "Thread A did not queue on internalLock within 1s — evict() may "
                        + "have returned without attempting the lock");

        // Step 3: set closed=true while we hold the lock, then release
        closedField.setBoolean(cache, true);
        internalLock.unlock();

        // Step 4: Thread A now acquires the lock with closed==true
        threadA.join(5000);

        // Step 5: verify that Thread A got ISE, not a successful return
        assertEquals("ise", evictResult.get(),
                "TOCTOU race: evict() completed normally on a closed cache instead of "
                        + "throwing IllegalStateException. The closed check must be "
                        + "re-verified inside the lock.");
    }

}

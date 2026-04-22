package jlsm.core.io;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial tests for shared-state consistency on constructs in {@code jlsm.core.io}. Each test
 * documents the finding it exercises, the bug shape, and the expected correct behavior so that the
 * test serves as regression coverage after a fix lands.
 */
class SharedStateAdversarialTest {

    // Finding: F-R001.shared_state.2.1
    // Bug: ArenaBufferPool.close() uses a non-atomic check-then-act on the `closed` flag.
    // Two threads can both observe closed==false, both set closed=true, and both call
    // arena.close(). Arena.close() is NOT idempotent (throws IllegalStateException
    // "Already closed" on the second invocation), so one of the concurrent callers
    // sees an unexpected runtime exception from a method documented as idempotent.
    // Correct behavior: close() must be idempotent under concurrent invocation — no caller
    // may observe an exception regardless of how many threads invoke close() in parallel.
    // Fix location: ArenaBufferPool.close() at lines 94-99. The minimal fix is to guard the
    // arena.close() call with an atomic claim on the close transition (e.g., AtomicBoolean
    // compareAndSet, or synchronized, so only one thread proceeds to arena.close()).
    // Regression watch: after the fix, isClosed() must still return true after any close() call
    // and the idempotent-close test in ArenaBufferPoolTest must continue to pass.
    @Test
    @Timeout(60)
    void test_ArenaBufferPool_close_concurrentInvocationIsIdempotent() throws Exception {
        // Empirically, the race window in close() (between the volatile `closed` read and the
        // `closed = true; arena.close()` write-and-action) is extremely narrow. The most
        // effective way to expose it is to run MANY independent two-thread-pair attempts — each
        // on its own pool — and let the JVM's scheduling variability catch the race.
        //
        // A single large pool with many threads is LESS effective: the first close()'s
        // Arena.close() thread-local-handshake coordination tends to serialize the remaining
        // threads before they can observe closed==false. Independent small pools preserve
        // the genuine race window per attempt.
        //
        // Arena.close() is NOT idempotent on Java 25 — it throws IllegalStateException
        // "Already closed" on second invocation. ArenaBufferPool.close() is documented as
        // idempotent, so any escaping exception is a race confirmation.
        final int attempts = 50_000;
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        for (int r = 0; r < attempts; r++) {
            ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                    .acquireTimeoutMillis(1000).build();
            CyclicBarrier startGate = new CyclicBarrier(2);
            Thread a = new Thread(() -> {
                try {
                    startGate.await();
                    pool.close();
                } catch (Throwable t) {
                    firstFailure.compareAndSet(null, t);
                }
            });
            Thread b = new Thread(() -> {
                try {
                    startGate.await();
                    pool.close();
                } catch (Throwable t) {
                    firstFailure.compareAndSet(null, t);
                }
            });
            a.start();
            b.start();
            a.join();
            b.join();
            if (firstFailure.get() != null) {
                break; // fail fast — one race confirmation is sufficient
            }
            assertTrue(pool.isClosed(), "isClosed() must be true after close() wave");
        }
        Throwable failure = firstFailure.get();
        assertNull(failure,
                "ArenaBufferPool.close() must be safe under concurrent invocation (close is"
                        + " documented as idempotent); observed: " + (failure == null ? "none"
                                : failure.getClass().getName() + ": " + failure.getMessage()));
    }

    // Finding: F-R001.shared_state.2.2
    // Bug: ArenaBufferPool.close() writes the `closed` flag BEFORE invoking arena.close().
    // An observer that reads isClosed() == true during the gap between those two
    // statements can see the flag flipped while the arena is still alive. The
    // documented meaning of isClosed() ("close() has been invoked") is weaker than
    // what many callers infer ("the arena is closed and segments are invalid"). The
    // shared-state invariant — that isClosed()==true implies arena has been released
    // — is NOT established by the current ordering.
    // Correct behavior: when any thread observes isClosed() == true, the underlying arena
    // must have already had close() invoked on it. The two writes must be ordered so
    // that publication of `closed=true` happens-after `arena.close()` (i.e., close
    // the arena first, then flip the flag — or otherwise coordinate so readers never
    // see closed==true while the arena scope is still alive).
    // Fix location: ArenaBufferPool.close() at lines 94-99. The minimal fix is to invoke
    // arena.close() BEFORE compareAndSet, or to re-order so that a successful CAS
    // only happens after the arena has been closed.
    // Regression watch: the idempotence test (2.1) must still pass; close() must remain
    // safe under concurrent invocation. bufferSize() must still return post-close.
    @Test
    @Timeout(60)
    void test_ArenaBufferPool_close_flagVisibleOnlyAfterArenaClosed() throws Exception {
        // We expose the private `arena` field via reflection so we can directly query
        // arena.scope().isAlive() — this is the only way to observe whether arena.close()
        // has actually taken effect independently of the `closed` flag. The finding's
        // claim is that an observer can see closed==true while arena.scope().isAlive()
        // is still true; we confirm by running many races and checking for any violation.
        Field arenaField = ArenaBufferPool.class.getDeclaredField("arena");
        arenaField.setAccessible(true);

        final int attempts = 20_000;
        AtomicReference<String> violation = new AtomicReference<>();

        for (int r = 0; r < attempts; r++) {
            ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                    .acquireTimeoutMillis(1000).build();
            Arena arena = (Arena) arenaField.get(pool);

            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);

            Thread closer = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    pool.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Thread observer = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    // Spin until we observe closed==true, then immediately sample arena scope.
                    while (!pool.isClosed()) {
                        Thread.onSpinWait();
                    }
                    // At this instant we have just observed isClosed()==true. The shared-state
                    // invariant (per R0 documentation + caller inference) requires that the
                    // arena has already been closed. If arena.scope().isAlive() is still true
                    // here, the ordering gap is exposed.
                    if (arena.scope().isAlive()) {
                        violation.compareAndSet(null,
                                "attempt " + System.nanoTime()
                                        + ": observed isClosed()==true but arena.scope().isAlive()"
                                        + " == true — close() flipped the flag before calling"
                                        + " arena.close(), exposing an intermediate state where"
                                        + " readers see 'closed' but the arena is still live.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            closer.start();
            observer.start();
            ready.await();
            go.countDown();
            closer.join();
            observer.join();

            if (violation.get() != null) {
                break; // one confirmation suffices
            }
        }

        String v = violation.get();
        assertNull(v, "close() must not publish closed==true before arena.close() has taken"
                + " effect; observed: " + v);
    }

    // Finding: F-R001.shared_state.2.3
    // Bug: ArenaBufferPool.<init> creates an Arena via Arena.ofShared() at line 34 and then
    // loops at lines 38-41 calling arena.allocate(bufferSize, 8) poolSize times. If any
    // allocation throws (e.g., OutOfMemoryError when the MemorySegment heap objects
    // exhaust heap, or when native memory is exhausted), the exception propagates out
    // of the ctor and Builder.build(). The partially-constructed instance is never
    // published (this never escapes), so nothing ever calls close() on it. The Arena
    // remains alive for the JVM lifetime, holding every byte of off-heap memory that
    // prior loop iterations committed — Arena backing memory is NOT released by GC;
    // Arena.ofShared() requires an explicit close() to free its native memory. Under
    // a retry-on-failure pattern or a monitoring loop that grows a pool, off-heap
    // usage grows unboundedly despite every ctor call "failing cleanly."
    // Correct behavior: if any allocation inside the ctor loop fails, the ctor must close
    // the Arena before propagating the exception so the off-heap memory is released.
    // A single failed build() invocation must not leak the native memory that its
    // Arena already committed prior to the failing allocation.
    // Fix location: ArenaBufferPool.<init> at lines 29-42. Wrap the allocation loop in a
    // try-catch that invokes arena.close() on any Throwable before rethrowing.
    // Regression watch: successful construction paths must be unchanged. The Arena must
    // remain open after a successful build() so that acquire()/release() continue
    // to operate on live segments. The existing isClosed() tests at 2.1 and 2.2
    // must still pass.
    @Test
    @Timeout(120)
    void test_ArenaBufferPool_ctor_closesArenaOnAllocationFailure() throws Exception {
        // Strategy: arrange for Builder.build() to OOM midway through the ctor's allocation
        // loop at a known partial-commit point, then measure the Arena-attributable native
        // memory using the JDK's Native Memory Tracking (NMT) diagnostic. NMT isolates
        // Arena allocations into the "Other" category, separate from heap growth and
        // other JVM subsystems — this is critical because
        // OperatingSystemMXBean.getCommittedVirtualMemorySize() is confounded by heap
        // expansion when the heap grows to accommodate MemorySegment objects during the
        // OOM-triggering loop.
        //
        // To make the OOM trigger fast and deterministic on a -Xmx6g JVM, we pre-fill ~75%
        // of the heap with byte[] ballast before invoking build(). With heap already
        // mostly full, the ctor's MemorySegment object allocations (one per arena.allocate
        // call) exhaust the remaining heap after a few million iterations (≈15 s), and
        // the Arena at that point has committed ~25-30 MiB of native slab memory.
        //
        // Without the fix: the ctor propagates the OOM without closing the Arena. The
        // NMT "Other" reservation persists for the rest of the JVM lifetime (this is
        // what the assertion catches).
        // With the fix: the ctor's try/catch calls arena.close() on failure, releasing
        // every committed slab. The NMT "Other" reservation drops back to near its
        // pre-build baseline.
        //
        // Requires -XX:NativeMemoryTracking=summary on the test JVM (configured in the
        // jlsm-core build.gradle test.jvmArgs block).

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName diagName = new ObjectName("com.sun.management:type=DiagnosticCommand");

        // Fail fast and skip if NMT is not enabled — avoid a confusing assertion failure
        // in an environment where the trigger cannot be measured.
        if (otherReservedKB(server, diagName) < 0) {
            // NMT is not enabled; the test cannot distinguish fix from bug on this JVM.
            // Fail with a descriptive message — the build.gradle is supposed to enable it.
            throw new IllegalStateException(
                    "SharedStateAdversarialTest requires -XX:NativeMemoryTracking=summary on"
                            + " the test JVM — check jlsm-core build.gradle test.jvmArgs.");
        }

        // Warmup: run a successful build so one-time class loading and Arena cold-path
        // overhead do not bias the measurement.
        try (ArenaBufferPool warmup = ArenaBufferPool.builder().poolSize(4).bufferSize(64)
                .acquireTimeoutMillis(1000).build()) {
            // noop — close via try-with-resources
        }

        // Pre-fill the heap to ~75% full so the ctor's allocation loop hits heap OOM
        // quickly rather than after ~1 minute of iterations. Each element is 16 MiB; we
        // allocate until we can't.
        List<byte[]> ballast = new ArrayList<>(1024);
        final long ballastTarget = (long) (Runtime.getRuntime().maxMemory() * 0.75);
        long filled = 0;
        try {
            while (filled < ballastTarget) {
                byte[] b = new byte[16 * 1024 * 1024];
                ballast.add(b);
                filled += b.length;
            }
        } catch (OutOfMemoryError ignored) {
            // stop filling — we've reached the heap ceiling, which is exactly the
            // condition we want for the ctor's OOM trigger
        }

        try {
            long beforeKB = otherReservedKB(server, diagName);

            // Invoke build() with a poolSize large enough that the pre-filled heap OOMs
            // before the loop completes. Expect OutOfMemoryError.
            try {
                ArenaBufferPool.builder().poolSize(100_000_000).bufferSize(1)
                        .acquireTimeoutMillis(1000).build();
                // If this returns successfully, the heap had more free space than the
                // ballast estimate reserved; the test cannot exercise the leak path. We
                // fail explicitly so the environment is visible rather than silently
                // passing.
                throw new AssertionError(
                        "Builder.build() unexpectedly succeeded under 75% heap pressure; the"
                                + " test cannot exercise the ctor allocation-failure path on"
                                + " this JVM.");
            } catch (OutOfMemoryError expected) {
                // expected — the ctor's loop exhausts heap mid-way, throwing before
                // completing all poolSize allocations
            }

            long afterKB = otherReservedKB(server, diagName);
            long growthKB = afterKB - beforeKB;

            // The fix releases the Arena on ctor failure — NMT "Other" delta near zero.
            // Without the fix, tens of MiB of Arena slab memory remain reserved.
            // Empirically, a leaking ctor reserves ≈25-30 MiB per attempt on -Xmx6g.
            // 8 MiB is well above normal NMT noise and well below a single leaked
            // attempt's footprint.
            final long thresholdKB = 8L * 1024L;
            assertTrue(growthKB < thresholdKB,
                    "ArenaBufferPool.<init> must close its Arena on allocation failure to"
                            + " avoid leaking off-heap memory; NMT 'Other' reservation grew by "
                            + growthKB + " KB after one failed build (threshold " + thresholdKB
                            + " KB).");
        } finally {
            // Release ballast so subsequent tests in this JVM have room to operate.
            ballast.clear();
        }
    }

    /**
     * Returns the current NMT "Other" reservation in KB, or -1 if NMT is not enabled. This category
     * accumulates Arena.allocate-backed native memory separately from heap growth and other JVM
     * subsystems, so it is the precise observable for isolating Arena leaks.
     */
    private static long otherReservedKB(MBeanServer server, ObjectName diagName) throws Exception {
        String result = (String) server.invoke(diagName, "vmNativeMemory",
                new Object[]{ new String[]{ "summary" } }, new String[]{ "[Ljava.lang.String;" });
        if (result.contains("Native memory tracking is not enabled")) {
            return -1;
        }
        int idx = result.indexOf("Other (reserved=");
        if (idx < 0) {
            return 0;
        }
        int start = idx + "Other (reserved=".length();
        int end = result.indexOf("KB", start);
        return Long.parseLong(result.substring(start, end));
    }
}

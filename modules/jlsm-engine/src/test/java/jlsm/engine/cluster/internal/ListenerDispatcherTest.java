package jlsm.engine.cluster.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ListenerDispatcher}.
 */
final class ListenerDispatcherTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        Exception first = null;
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception e) {
                if (first == null)
                    first = e;
                else
                    first.addSuppressed(e);
            }
        }
        closeables.clear();
        if (first != null)
            throw first;
    }

    private <L> ListenerDispatcher<L> newDispatcher(String name, int capacity) {
        var d = new ListenerDispatcher<L>(name, capacity);
        closeables.add(d);
        return d;
    }

    // --- Constructor validation ---

    @Test
    @Timeout(5)
    void constructor_rejectsNullThreadName() {
        assertThrows(NullPointerException.class, () -> new ListenerDispatcher<>(null, 16));
    }

    @Test
    @Timeout(5)
    void constructor_rejectsEmptyThreadName() {
        assertThrows(IllegalArgumentException.class, () -> new ListenerDispatcher<>("", 16));
    }

    @Test
    @Timeout(5)
    void constructor_rejectsNonPositiveQueueCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new ListenerDispatcher<>("test-thread", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ListenerDispatcher<>("test-thread", -1));
    }

    // --- dispatch argument validation ---

    @Test
    @Timeout(5)
    void dispatch_rejectsNullListenersOrEvent() {
        var dispatcher = this.<Runnable>newDispatcher("test-thread", 16);
        assertThrows(NullPointerException.class, () -> dispatcher.dispatch(null, Runnable::run));
        assertThrows(NullPointerException.class, () -> dispatcher.dispatch(List.of(() -> {
        }), null));
    }

    // --- Happy path ---

    @Test
    @Timeout(5)
    void dispatch_invokesEachListenerExactlyOnce() throws Exception {
        var dispatcher = this.<Runnable>newDispatcher("test-thread", 16);
        var latch = new CountDownLatch(3);
        var counts = new AtomicInteger[]{ new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger() };

        Runnable l1 = () -> {
            counts[0].incrementAndGet();
            latch.countDown();
        };
        Runnable l2 = () -> {
            counts[1].incrementAndGet();
            latch.countDown();
        };
        Runnable l3 = () -> {
            counts[2].incrementAndGet();
            latch.countDown();
        };

        dispatcher.dispatch(List.of(l1, l2, l3), Runnable::run);

        assertTrue(latch.await(3, TimeUnit.SECONDS), "all 3 listeners should be invoked");
        assertEquals(1, counts[0].get());
        assertEquals(1, counts[1].get());
        assertEquals(1, counts[2].get());
    }

    @Test
    @Timeout(5)
    void dispatch_preservesOrderForSingleListener() throws Exception {
        var dispatcher = this.<Consumer<Integer>>newDispatcher("test-thread", 64);
        var observed = new ConcurrentLinkedQueue<Integer>();
        var latch = new CountDownLatch(5);

        Consumer<Integer> listener = i -> {
            observed.add(i);
            latch.countDown();
        };

        for (int i = 0; i < 5; i++) {
            final int val = i;
            dispatcher.dispatch(List.of(listener), l -> l.accept(val));
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS), "all 5 events should be delivered");
        assertEquals(List.of(0, 1, 2, 3, 4), new ArrayList<>(observed),
                "single-thread executor must preserve FIFO order");
    }

    // --- Exception isolation ---

    @Test
    @Timeout(5)
    void dispatch_isolatesThrowingListener() throws Exception {
        var dispatcher = this.<Runnable>newDispatcher("test-thread", 16);
        var latch = new CountDownLatch(2);
        var received = new AtomicInteger();

        Runnable good1 = () -> {
            received.incrementAndGet();
            latch.countDown();
        };
        Runnable bad = () -> {
            throw new RuntimeException("boom");
        };
        Runnable good2 = () -> {
            received.incrementAndGet();
            latch.countDown();
        };

        dispatcher.dispatch(List.of(good1, bad, good2), Runnable::run);

        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "siblings of a throwing listener should still be notified");
        assertEquals(2, received.get());
    }

    // --- Async behavior ---

    @Test
    @Timeout(10)
    void dispatch_doesNotBlockCallerOnSlowListener() throws Exception {
        var dispatcher = this.<Runnable>newDispatcher("test-thread", 16);
        var done = new CountDownLatch(1);
        Runnable slow = () -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        long start = System.nanoTime();
        dispatcher.dispatch(List.of(slow), Runnable::run);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 500L,
                "dispatch must not block caller; actual elapsed=" + elapsedMs + "ms");
        // Let the slow listener finish so tear-down close() does not interrupt it noisily.
        assertTrue(done.await(5, TimeUnit.SECONDS), "slow listener should eventually complete");
    }

    // --- close() ---

    @Test
    @Timeout(5)
    void close_isIdempotent() {
        var dispatcher = new ListenerDispatcher<Runnable>("test-thread", 16);
        assertDoesNotThrow(dispatcher::close);
        assertDoesNotThrow(dispatcher::close);
    }

    @Test
    @Timeout(5)
    void dispatch_afterClose_throwsIllegalStateException() {
        var dispatcher = new ListenerDispatcher<Runnable>("test-thread", 16);
        dispatcher.close();
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(List.of(() -> {
        }), Runnable::run));
    }

    @Test
    @Timeout(10)
    void close_awaitsPendingTasksBrieflyThenForces() throws Exception {
        var dispatcher = new ListenerDispatcher<Runnable>("test-thread", 16);
        var started = new CountDownLatch(1);

        Runnable longRunning = () -> {
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        dispatcher.dispatch(List.of(longRunning), Runnable::run);

        assertTrue(started.await(2, TimeUnit.SECONDS), "long-running listener should have started");

        long t0 = System.nanoTime();
        dispatcher.close();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(elapsedMs < 1_500L,
                "close() must return within ~1.5s (await then force); actual=" + elapsedMs + "ms");
    }

    // --- Overflow ---

    @Test
    @Timeout(15)
    void dispatch_dropsOldestOnOverflow() throws Exception {
        // Capacity 2: with drop-oldest, the two most recent tasks should eventually run
        // after we release the gate-blocked first task. The first blocker occupies the
        // worker thread (not the queue), so queue can hold 2 additional tasks.
        var dispatcher = this.<Runnable>newDispatcher("overflow-thread", 2);
        var gate = new Semaphore(0);
        var firstRunning = new CountDownLatch(1);
        var executed = new ConcurrentLinkedQueue<Integer>();

        Runnable blocker = () -> {
            firstRunning.countDown();
            try {
                gate.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        dispatcher.dispatch(List.of(blocker), Runnable::run);
        assertTrue(firstRunning.await(3, TimeUnit.SECONDS), "blocker must occupy the worker");

        // Submit tasks 1..5. With queue capacity 2 + drop-oldest, by the time we stop
        // submitting, the queue should hold tasks 4 and 5 (oldest 1,2,3 dropped).
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            dispatcher.dispatch(List.of((Runnable) () -> executed.add(id)), Runnable::run);
        }

        // Release the blocker; the surviving tasks drain.
        gate.release();

        // Wait briefly for the executor to catch up — use a poll loop.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadlineNanos && executed.size() < 2) {
            Thread.sleep(20);
        }
        // Allow any additional drains a tick.
        Thread.sleep(100);

        int ranCount = executed.size();
        assertTrue(ranCount <= 2,
                "drop-oldest must cap surviving middle tasks at queue capacity; ran=" + ranCount
                        + ", observed=" + executed);
        assertTrue(ranCount >= 1, "at least one task should have survived; observed=" + executed);

        // The survivors should be the *newest* submissions, not the oldest. So task 5
        // should appear, and task 1 should NOT appear.
        assertTrue(executed.contains(5),
                "newest submission (5) should not have been dropped; observed=" + executed);
        assertFalse(executed.contains(1),
                "oldest submission (1) should have been dropped; observed=" + executed);
    }
}

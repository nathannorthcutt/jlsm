package jlsm.cluster.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * @spec transport.multiplexed-framing.R34b
 * @spec transport.multiplexed-framing.R20
 */
class DispatchPoolTest {

    private DispatchPool pool;

    @AfterEach
    void cleanup() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void submitRunsTaskOnVirtualThread() throws Exception {
        pool = new DispatchPool(8, 16);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger threadFlag = new AtomicInteger();
        boolean accepted = pool.submit(() -> {
            threadFlag.set(Thread.currentThread().isVirtual() ? 1 : 0);
            ran.countDown();
        }, () -> true);
        assertTrue(accepted);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals(1, threadFlag.get());
    }

    @Test
    void boundedConcurrencyEnforcedBySemaphore() throws Exception {
        pool = new DispatchPool(2, 16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch midway = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                int now = active.incrementAndGet();
                maxActive.updateAndGet(prev -> Math.max(prev, now));
                midway.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                active.decrementAndGet();
            }, () -> true);
        }
        // Wait for first 2 tasks to be active before releasing
        midway.await(2, TimeUnit.SECONDS);
        Thread.sleep(50); // give a moment for any extras to leak through
        assertTrue(maxActive.get() <= 2,
                "max concurrent tasks should be ≤ 2 (semaphore=2); got " + maxActive.get());
        start.countDown();
        pool.close();
    }

    @Test
    void queueOverflowReportsDiscard() throws Exception {
        pool = new DispatchPool(1, 2); // 1 permit, 2-deep queue
        CountDownLatch hold = new CountDownLatch(1);
        // Block first task
        pool.submit(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> true);
        // Now fill queue (2 entries) and overflow
        pool.submit(() -> {
        }, () -> true);
        pool.submit(() -> {
        }, () -> true);
        // Next submit should overflow
        boolean accepted = pool.submit(() -> {
        }, () -> true);
        assertFalse(accepted);
        hold.countDown();
    }

    @Test
    void deadConnectionEpochDiscardsBeforeRunning() throws Exception {
        pool = new DispatchPool(1, 4);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger ranAfter = new AtomicInteger();
        // Hold first task to force queueing
        pool.submit(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> true);
        // Submit task with an epoch that becomes dead BEFORE consumption
        boolean[] deadFlag = { false };
        BooleanSupplier alive = () -> !deadFlag[0];
        pool.submit(() -> ranAfter.incrementAndGet(), alive);
        deadFlag[0] = true; // mark dead
        hold.countDown(); // release first task
        // Give dispatcher time to consume
        Thread.sleep(100);
        assertEquals(0, ranAfter.get(), "dead-epoch task should be discarded on dequeue");
    }

    @Test
    void closeStopsDispatcherAndRejectsFurtherSubmits() throws Exception {
        pool = new DispatchPool(2, 4);
        pool.close();
        boolean accepted = pool.submit(() -> {
        }, () -> true);
        assertFalse(accepted, "submit after close must return false");
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class, () -> new DispatchPool(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DispatchPool(1, 0));
    }
}

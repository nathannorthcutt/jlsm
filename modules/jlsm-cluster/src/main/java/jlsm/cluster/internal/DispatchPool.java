package jlsm.cluster.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Bounded handler-dispatch pool per {@code transport.multiplexed-framing} R34b.
 *
 * <p>
 * Owns a fixed-permit semaphore (default 256) and a bounded queue (default 1024). Submits go to the
 * queue; a dedicated dispatcher virtual thread takes from the queue, acquires a permit, and runs
 * each task on its own virtual thread. Permits are released on task completion. When the queue is
 * full, submit returns {@code false}.
 *
 * <p>
 * Per-task <b>liveness check</b> (R20 v3 lazy-drain): each submit carries a {@link BooleanSupplier}
 * that the dispatcher invokes on dequeue; if liveness returns {@code false}, the task is discarded
 * with {@link #discardCount()} incremented and no permit is acquired.
 *
 * @spec transport.multiplexed-framing.R34b
 * @spec transport.multiplexed-framing.R20
 */
public final class DispatchPool implements AutoCloseable {

    /** Default permit count per spec. */
    public static final int DEFAULT_PERMITS = 256;

    /** Default queue depth per spec. */
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private final Semaphore permits;
    private final BlockingQueue<DispatchTask> queue;
    private final Thread dispatcher;
    private final AtomicLong overflows = new AtomicLong();
    private final AtomicLong discards = new AtomicLong();
    private volatile boolean closed = false;

    public DispatchPool() {
        this(DEFAULT_PERMITS, DEFAULT_QUEUE_CAPACITY);
    }

    public DispatchPool(int permitCount, int queueCapacity) {
        if (permitCount <= 0) {
            throw new IllegalArgumentException("permitCount must be positive: " + permitCount);
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive: " + queueCapacity);
        }
        this.permits = new Semaphore(permitCount);
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.dispatcher = Thread.ofVirtual().name("jlsm-dispatch-pool").start(this::dispatcherLoop);
    }

    /**
     * Submit a task with a per-task liveness check (e.g., connection-alive epoch).
     *
     * @return {@code true} if the task was queued; {@code false} if the queue was full (R34b
     *         overflow) or the pool is closed
     */
    public boolean submit(Runnable task, BooleanSupplier liveness) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (liveness == null) {
            throw new IllegalArgumentException("liveness must not be null");
        }
        if (closed) {
            return false;
        }
        boolean accepted = queue.offer(new DispatchTask(task, liveness));
        if (!accepted) {
            overflows.incrementAndGet();
        }
        return accepted;
    }

    /** R34b counter: how many submits have been rejected because the queue was full. */
    public long overflowCount() {
        return overflows.get();
    }

    /** R20 lazy-drain counter: how many queued tasks were discarded on dequeue (dead epoch). */
    public long discardCount() {
        return discards.get();
    }

    /** Currently in-queue task count. */
    public int queueDepth() {
        return queue.size();
    }

    @Override
    public void close() {
        closed = true;
        dispatcher.interrupt();
        queue.clear();
    }

    private void dispatcherLoop() {
        while (!closed) {
            // Acquire permit BEFORE taking from the queue so queue depth accurately reflects
            // tasks waiting beyond the in-flight (semaphore-held) tasks. Without this ordering
            // the dispatcher would consume queue entries before securing capacity, inflating
            // observed queue space and weakening the R34b backpressure signal.
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            DispatchTask task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                permits.release();
                Thread.currentThread().interrupt();
                return;
            }
            if (!task.liveness.getAsBoolean()) {
                permits.release();
                discards.incrementAndGet();
                continue;
            }
            Thread.ofVirtual().name("jlsm-dispatch-handler").start(() -> {
                try {
                    task.runnable.run();
                } finally {
                    permits.release();
                }
            });
        }
    }

    private record DispatchTask(Runnable runnable, BooleanSupplier liveness) {
    }
}

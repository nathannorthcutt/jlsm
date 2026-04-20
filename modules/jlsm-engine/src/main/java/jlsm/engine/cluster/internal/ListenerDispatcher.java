package jlsm.engine.cluster.internal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded asynchronous dispatcher for listener callbacks.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Constructs a bounded single-thread executor named {@code threadName} with a queue of
 * {@code queueCapacity}.</li>
 * <li>{@code dispatch} submits one task per listener; on queue overflow, drops the oldest pending
 * task (drop-oldest policy — keeps the queue fresh).</li>
 * <li>A throwing listener is caught and swallowed; siblings continue.</li>
 * <li>{@code close()} attempts orderly shutdown (awaits 1s), then {@code shutdownNow()}.
 * Idempotent.</li>
 * <li>Null {@code threadName}, non-positive {@code queueCapacity}, or null args to {@code dispatch}
 * rejected eagerly.</li>
 * </ul>
 *
 * <p>
 * Generic over {@code L} so consumers beyond membership (e.g. consensus observers in WD-04) can
 * reuse the dispatcher.
 *
 * <p>
 * Governed by: F04.R39 and {@code .decisions/cluster-membership-protocol/adr.md}
 */
public final class ListenerDispatcher<L> implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ListenerDispatcher.class.getName());

    /** Grace period awaited during {@link #close()} before forcing shutdown. */
    private static final long SHUTDOWN_GRACE_MILLIS = 1_000L;

    private final ThreadPoolExecutor executor;
    private final LinkedBlockingQueue<Runnable> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String threadName;

    /**
     * Creates a bounded dispatcher.
     *
     * @param threadName the executor thread name; must not be null or empty
     * @param queueCapacity queue capacity before drop-oldest kicks in; must be positive
     */
    public ListenerDispatcher(String threadName, int queueCapacity) {
        Objects.requireNonNull(threadName, "threadName must not be null");
        if (threadName.isEmpty()) {
            throw new IllegalArgumentException("threadName must not be empty");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "queueCapacity must be positive; got " + queueCapacity);
        }
        this.threadName = threadName;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue, r -> {
            final Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        assert executor.getCorePoolSize() == 1 : "single-thread executor preserves FIFO order";
    }

    /**
     * Submits one callback per listener to the executor.
     *
     * <p>
     * Iteration over the provided listener list happens synchronously within this call so that the
     * listener snapshot used for dispatch reflects the caller's current registration state — not a
     * later state observed by the executor thread. Each listener is captured by the submitted task
     * by value (as a lambda capture), so subsequent mutations to the list do not affect already
     * submitted tasks.
     *
     * @param listeners the listeners to notify; must not be null (individual entries should be
     *            non-null)
     * @param event the callback to apply to each listener; must not be null
     * @throws IllegalStateException if the dispatcher has been closed
     */
    public void dispatch(List<L> listeners, Consumer<L> event) {
        Objects.requireNonNull(listeners, "listeners must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (closed.get()) {
            throw new IllegalStateException("dispatcher is closed");
        }
        for (L listener : listeners) {
            if (listener == null) {
                continue;
            }
            final L captured = listener;
            submitWithDropOldest(() -> {
                try {
                    event.accept(captured);
                } catch (Throwable t) {
                    // Listener exceptions must never propagate — log and continue.
                    LOGGER.log(Level.WARNING, () -> "listener threw from dispatcher thread '"
                            + threadName + "': " + t);
                }
            });
        }
    }

    /**
     * Enqueues a task on the executor's queue and ensures the worker thread is started.
     *
     * <p>
     * On queue-full, drops the oldest pending task and retries. The retry budget is bounded
     * (capacity + 2) so a pathological producer cannot spin forever. If the executor is
     * mid-shutdown, the enqueue silently fails — callers already checked {@code closed}, this
     * branch handles a close() race.
     */
    private void submitWithDropOldest(Runnable task) {
        assert task != null : "submitted task must not be null";
        // Ensure the worker thread is up so queued tasks eventually drain.
        executor.prestartCoreThread();

        final int maxAttempts = queue.remainingCapacity() + queue.size() + 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (executor.isShutdown()) {
                return;
            }
            if (queue.offer(task)) {
                return;
            }
            // Queue full: drop the oldest pending task and retry.
            final Runnable dropped = queue.poll();
            if (dropped != null) {
                LOGGER.log(Level.WARNING, () -> "dispatcher '" + threadName
                        + "' queue full — dropped oldest pending task");
            }
        }
        // Retry budget exhausted — drop the incoming task rather than block.
        LOGGER.log(Level.WARNING, () -> "dispatcher '" + threadName
                + "' dropped incoming task after exhausting retry budget");
    }

    /**
     * Shuts the executor down. Idempotent — calling more than once is a no-op after the first.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}

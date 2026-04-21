package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.MembershipProtocol;
import jlsm.engine.cluster.NodeAddress;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Scheduled task that re-invokes {@link MembershipProtocol#start(List)} on a seed list while the
 * engine reports quorum loss.
 *
 * <p>
 * Contract: While {@code isQuorumLost.getAsBoolean()} returns {@code true}, the task re-invokes
 * {@code membership.start(seeds)} at {@code retryInterval} on the supplied scheduler. The task
 * cancels itself once {@code isQuorumLost} transitions to {@code false}, or when {@link #stop()} is
 * called. {@link #start()} and {@link #stop()} are both idempotent — calling {@code start()} on an
 * already-running task, or {@code stop()} on a stopped or never-started task, is a no-op.
 *
 * <p>
 * Side effects: schedules recurring work on the supplied {@link ScheduledExecutorService}; invokes
 * {@code membership.start(seeds)} which itself performs network I/O.
 *
 * <p>
 * {@code @spec engine.clustering.R42}
 */
public final class SeedRetryTask {

    private static final System.Logger LOGGER = System.getLogger(SeedRetryTask.class.getName());

    private final MembershipProtocol membership;
    private final List<NodeAddress> seeds;
    private final BooleanSupplier isQuorumLost;
    private final ScheduledExecutorService scheduler;
    private final Duration retryInterval;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    /**
     * Creates a new seed-retry task.
     *
     * @param membership the membership protocol to re-seed; must not be null
     * @param seeds the seed list to pass to {@code membership.start}; must not be null
     * @param isQuorumLost supplier that returns {@code true} while quorum remains lost; must not be
     *            null
     * @param scheduler scheduler on which the retry task runs; must not be null
     * @param retryInterval interval between retry attempts; must be positive and non-null
     */
    public SeedRetryTask(MembershipProtocol membership, List<NodeAddress> seeds,
            BooleanSupplier isQuorumLost, ScheduledExecutorService scheduler,
            Duration retryInterval) {
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds must not be null"));
        this.isQuorumLost = Objects.requireNonNull(isQuorumLost, "isQuorumLost must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(retryInterval, "retryInterval must not be null");
        if (retryInterval.isZero() || retryInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "retryInterval must be positive, got: " + retryInterval);
        }
        this.retryInterval = retryInterval;
    }

    /**
     * Starts the retry schedule. Idempotent — calling {@code start} on an already-running task is a
     * no-op. Calling {@code start} after {@link #stop()} is also a no-op — a stopped task cannot be
     * restarted.
     */
    public void start() {
        if (stopped.get()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        final long intervalMillis = retryInterval.toMillis();
        final ScheduledFuture<?> f = scheduler.scheduleWithFixedDelay(this::tick, intervalMillis,
                intervalMillis, TimeUnit.MILLISECONDS);
        future.set(f);
    }

    /**
     * Stops the retry schedule. Idempotent — safe to call multiple times or before
     * {@link #start()}.
     */
    public void stop() {
        stopped.set(true);
        final ScheduledFuture<?> f = future.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void tick() {
        if (stopped.get()) {
            return;
        }
        try {
            if (!isQuorumLost.getAsBoolean()) {
                return;
            }
            membership.start(seeds);
        } catch (RuntimeException | Error e) {
            // Swallow & log — the retry loop must continue so that a transient failure does not
            // kill the scheduled task (ScheduledExecutorService stops re-running on throw).
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "SeedRetryTask: membership.start failed; will retry at next tick", e);
        } catch (Exception e) {
            // IOException and other checked failures — same treatment.
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "SeedRetryTask: membership.start failed; will retry at next tick", e);
        }
    }
}

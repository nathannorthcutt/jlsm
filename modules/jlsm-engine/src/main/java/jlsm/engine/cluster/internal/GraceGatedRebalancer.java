package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Coordinates deferred, differential partition rebalancing driven by grace-period expiries.
 *
 * <p>
 * Contract: On a periodic cadence, drains expired departures from {@link GracePeriodManager} and,
 * for each expired node, invokes
 * {@link RendezvousOwnership#differentialAssign(MembershipView, MembershipView, Set)} with the set
 * of partitions previously owned by that node. Only cache entries corresponding to the affected
 * partitions are invalidated; stable ownerships are preserved. If a departed node returns before
 * its grace window expires, {@link #cancelPending(NodeAddress)} cancels the scheduled rebalance for
 * that node and records the return via {@link GracePeriodManager#recordReturn(NodeAddress)}.
 *
 * <p>
 * Side effects: schedules recurring work on the supplied scheduler; mutates ownership-cache state
 * inside {@link RendezvousOwnership}; may invalidate cache entries.
 *
 * <p>
 * Thread-safety: internal state is synchronised; {@link #start()} and {@link #stop()} are
 * idempotent.
 *
 * <p>
 * {@code @spec engine.clustering.R47, R48, R50}
 *
 * @spec engine.clustering.R47 — defers rebalance until grace expiry via scheduled drain
 * @spec engine.clustering.R48 — differentialAssign touches only departed partitions on expiry
 * @spec engine.clustering.R50 — cancelPending aborts scheduled rebalance when node returns within grace
 */
public final class GraceGatedRebalancer {

    private static final System.Logger LOGGER = System
            .getLogger(GraceGatedRebalancer.class.getName());

    private final GracePeriodManager gracePeriodManager;
    private final RendezvousOwnership ownership;
    private final Supplier<MembershipView> viewSupplier;
    private final Function<NodeAddress, Set<String>> partitionsOwnedByFn;
    private final ScheduledExecutorService scheduler;
    private final Duration checkInterval;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> drainFuture = new AtomicReference<>();
    /**
     * Tracks the "previous" view per departed node, captured at the time the departure was first
     * observed. The differential rebalancer needs an oldView→newView pair; this cache supplies the
     * oldView side.
     */
    private final ConcurrentHashMap<NodeAddress, MembershipView> viewAtDeparture = new ConcurrentHashMap<>();

    /**
     * Creates a new grace-gated rebalancer.
     *
     * @param gracePeriodManager source of expired departures; must not be null
     * @param ownership ownership index to update on expiry; must not be null
     * @param viewSupplier supplier of the current membership view; must not be null
     * @param partitionsOwnedByFn function mapping a node to the partition IDs it owned prior to
     *            departure; must not be null
     * @param scheduler scheduler on which the periodic drain task runs; must not be null
     * @param checkInterval cadence between grace-expiry checks; must be positive and non-null
     */
    public GraceGatedRebalancer(GracePeriodManager gracePeriodManager,
            RendezvousOwnership ownership, Supplier<MembershipView> viewSupplier,
            Function<NodeAddress, Set<String>> partitionsOwnedByFn,
            ScheduledExecutorService scheduler, Duration checkInterval) {
        this.gracePeriodManager = Objects.requireNonNull(gracePeriodManager,
                "gracePeriodManager must not be null");
        this.ownership = Objects.requireNonNull(ownership, "ownership must not be null");
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier must not be null");
        this.partitionsOwnedByFn = Objects.requireNonNull(partitionsOwnedByFn,
                "partitionsOwnedByFn must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        if (checkInterval.isZero() || checkInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "checkInterval must be positive, got: " + checkInterval);
        }
        this.checkInterval = checkInterval;
    }

    /**
     * Starts the periodic grace-expiry drain. Idempotent.
     */
    public void start() {
        if (stopped.get()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        final long millis = checkInterval.toMillis();
        final ScheduledFuture<?> f = scheduler.scheduleWithFixedDelay(this::drainTick, millis,
                millis, TimeUnit.MILLISECONDS);
        drainFuture.set(f);
    }

    /**
     * Stops the periodic grace-expiry drain. Idempotent.
     */
    public void stop() {
        stopped.set(true);
        final ScheduledFuture<?> f = drainFuture.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * Cancels any in-flight scheduled rebalance for a node that has returned during its grace
     * window and delegates to {@link GracePeriodManager#recordReturn(NodeAddress)} to clear the
     * departure record.
     *
     * @param returning the node address that has rejoined; must not be null
     */
    public void cancelPending(NodeAddress returning) {
        Objects.requireNonNull(returning, "returning must not be null");
        viewAtDeparture.remove(returning);
        gracePeriodManager.recordReturn(returning);
    }

    /**
     * Records the membership view in effect when a node departed. Callers (e.g.
     * {@code ClusteredEngine.onViewChanged}) invoke this alongside
     * {@link GracePeriodManager#recordDeparture(NodeAddress, java.time.Instant)} so the rebalancer
     * has the oldView side of the differential pair when grace expires.
     *
     * @param departed the address of the departed node; must not be null
     * @param priorView the view in effect immediately before the departure was recorded; must not
     *            be null
     */
    public void recordDepartureView(NodeAddress departed, MembershipView priorView) {
        Objects.requireNonNull(departed, "departed must not be null");
        Objects.requireNonNull(priorView, "priorView must not be null");
        viewAtDeparture.put(departed, priorView);
    }

    // --- internals ---

    private void drainTick() {
        if (stopped.get()) {
            return;
        }
        try {
            final Set<NodeAddress> expired = gracePeriodManager.expiredDepartures();
            if (expired.isEmpty()) {
                return;
            }
            final MembershipView currentView = viewSupplier.get();
            if (currentView == null) {
                return;
            }
            for (NodeAddress departed : expired) {
                final Set<String> partitions = partitionsOwnedByFn.apply(departed);
                if (partitions == null || partitions.isEmpty()) {
                    viewAtDeparture.remove(departed);
                    continue;
                }
                final MembershipView priorView = viewAtDeparture.getOrDefault(departed,
                        currentView);
                try {
                    ownership.differentialAssign(priorView, currentView, new HashSet<>(partitions));
                } catch (RuntimeException ex) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            () -> "GraceGatedRebalancer: differentialAssign failed for " + departed,
                            ex);
                }
                viewAtDeparture.remove(departed);
            }
        } catch (RuntimeException ex) {
            // Never propagate — the scheduler would cancel the recurring task.
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "GraceGatedRebalancer: drain tick failed; will retry", ex);
        }
    }
}

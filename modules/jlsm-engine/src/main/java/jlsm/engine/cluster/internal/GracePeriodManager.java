package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.NodeAddress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks departed nodes and manages the grace period before permanent removal.
 *
 * <p>
 * Contract: Records node departures with timestamps. A departed node is considered in grace period
 * if the configured duration has not elapsed since departure. After grace period expiry, the
 * departure is permanent. If a node returns during grace, its departure record is cleared. Grace
 * period controls cleanup timing, not ownership assignment — partitions are eagerly reassigned on
 * departure.
 *
 * <p>
 * Thread-safe: uses concurrent data structures.
 *
 * <p>
 * Governed by: {@code .decisions/rebalancing-grace-period-strategy/adr.md}
 *
 * @spec engine.clustering.R51 — track each departed member with a monotonic departure timestamp
 * @spec engine.clustering.R52 — support cancellation when a departed member returns
 * @spec engine.clustering.R53 — use a monotonic time source (never wall-clock) for duration
 *       comparisons
 * @spec engine.clustering.R54 — handle concurrent departures independently (ConcurrentHashMap)
 * @spec engine.clustering.R76 — safe for concurrent membership-thread + ownership-thread access
 * @spec engine.clustering.R94 — expired departures removed from internal state during expiration
 *       checks
 * @spec engine.clustering.R95 — injectable clock; single-timestamp capture per operation boundary
 * @spec engine.clustering.R96 — re-departure preserves the original departure timestamp (first
 *       anchors the grace)
 */
public final class GracePeriodManager {

    private final Duration gracePeriod;
    private final ConcurrentHashMap<NodeAddress, Instant> departures = new ConcurrentHashMap<>();
    private volatile Clock clock;

    /**
     * Creates a grace period manager with the specified duration using a monotonic clock
     * ({@link MonotonicClock}). A monotonic clock guarantees that backward wall-clock adjustments
     * (NTP corrections, manual clock sets) cannot extend an active grace window.
     *
     * @param gracePeriod the grace period duration; must not be null and must be positive
     */
    public GracePeriodManager(Duration gracePeriod) {
        this(gracePeriod, new MonotonicClock());
    }

    /**
     * Creates a grace period manager with the specified duration and clock source.
     *
     * @param gracePeriod the grace period duration; must not be null and must be positive
     * @param clock the clock to use for time queries; must not be null
     */
    public GracePeriodManager(Duration gracePeriod, Clock clock) {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("gracePeriod must be positive");
        }
        this.gracePeriod = gracePeriod;
        this.clock = clock;
    }

    /**
     * Replaces the clock source used for grace period evaluation. Intended for testing to simulate
     * clock jumps and deterministic time progression.
     *
     * @param clock the new clock; must not be null
     */
    public void setClock(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.clock = clock;
    }

    /**
     * Records the departure of a node at the given time.
     *
     * @param node the departed node's address; must not be null
     * @param departedAt the instant of departure; must not be null and must not be in the future
     */
    public void recordDeparture(NodeAddress node, Instant departedAt) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(departedAt, "departedAt must not be null");
        if (departedAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException(
                    "departedAt must not be in the future — a future timestamp would extend "
                            + "the grace window beyond the configured duration");
        }
        departures.putIfAbsent(node, departedAt);
    }

    /**
     * Returns whether the given node is currently in its grace period.
     *
     * @param node the node address to check; must not be null
     * @return {@code true} if the node departed and the grace period has not expired
     */
    public boolean isInGracePeriod(NodeAddress node) {
        return isInGracePeriod(node, clock.instant());
    }

    /**
     * Returns whether the given node is in its grace period at the specified instant.
     *
     * <p>
     * Use this overload together with {@link #expiredDepartures(Instant)} passing the same
     * {@code now} value to get a consistent snapshot — the two results will never contradict each
     * other for the same instant.
     *
     * @param node the node address to check; must not be null
     * @param now the instant to evaluate against; must not be null
     * @return {@code true} if the node departed and the grace period has not expired at {@code now}
     */
    public boolean isInGracePeriod(NodeAddress node, Instant now) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(now, "now must not be null");
        final Instant departedAt = departures.get(node);
        if (departedAt == null) {
            return false;
        }
        final Instant expiry = departedAt.plus(gracePeriod);
        return now.isBefore(expiry);
    }

    /**
     * Returns the set of nodes whose grace period has expired.
     *
     * @return a set of node addresses with expired grace periods; never null
     */
    public Set<NodeAddress> expiredDepartures() {
        return expiredDepartures(clock.instant());
    }

    /**
     * Returns the set of nodes whose grace period has expired at the specified instant.
     *
     * <p>
     * Use this overload together with {@link #isInGracePeriod(NodeAddress, Instant)} passing the
     * same {@code now} value to get a consistent snapshot — the two results will never contradict
     * each other for the same instant.
     *
     * @param now the instant to evaluate against; must not be null
     * @return a set of node addresses with expired grace periods; never null
     */
    public Set<NodeAddress> expiredDepartures(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        final Set<NodeAddress> expired = new HashSet<>();
        final var iterator = departures.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final Instant expiry = entry.getValue().plus(gracePeriod);
            assert expiry != null : "expiry must not be null";
            if (!now.isBefore(expiry)) {
                expired.add(entry.getKey());
                iterator.remove();
            }
        }
        return expired;
    }

    /**
     * Records the return of a previously departed node, clearing its departure record.
     *
     * @param node the returning node's address; must not be null
     */
    public void recordReturn(NodeAddress node) {
        Objects.requireNonNull(node, "node must not be null");
        departures.remove(node);
    }
}

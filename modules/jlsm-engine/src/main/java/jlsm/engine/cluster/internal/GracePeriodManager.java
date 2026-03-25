package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.NodeAddress;

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
 */
public final class GracePeriodManager {

    private final Duration gracePeriod;
    private final ConcurrentHashMap<NodeAddress, Instant> departures = new ConcurrentHashMap<>();

    /**
     * Creates a grace period manager with the specified duration.
     *
     * @param gracePeriod the grace period duration; must not be null and must be positive
     */
    public GracePeriodManager(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("gracePeriod must be positive");
        }
        this.gracePeriod = gracePeriod;
    }

    /**
     * Records the departure of a node at the given time.
     *
     * @param node the departed node's address; must not be null
     * @param departedAt the instant of departure; must not be null
     */
    public void recordDeparture(NodeAddress node, Instant departedAt) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(departedAt, "departedAt must not be null");
        departures.put(node, departedAt);
    }

    /**
     * Returns whether the given node is currently in its grace period.
     *
     * @param node the node address to check; must not be null
     * @return {@code true} if the node departed and the grace period has not expired
     */
    public boolean isInGracePeriod(NodeAddress node) {
        Objects.requireNonNull(node, "node must not be null");
        final Instant departedAt = departures.get(node);
        if (departedAt == null) {
            return false;
        }
        final Instant expiry = departedAt.plus(gracePeriod);
        return Instant.now().isBefore(expiry);
    }

    /**
     * Returns the set of nodes whose grace period has expired.
     *
     * @return a set of node addresses with expired grace periods; never null
     */
    public Set<NodeAddress> expiredDepartures() {
        final Instant now = Instant.now();
        final Set<NodeAddress> expired = new HashSet<>();
        for (var entry : departures.entrySet()) {
            final Instant expiry = entry.getValue().plus(gracePeriod);
            assert expiry != null : "expiry must not be null";
            if (!now.isBefore(expiry)) {
                expired.add(entry.getKey());
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

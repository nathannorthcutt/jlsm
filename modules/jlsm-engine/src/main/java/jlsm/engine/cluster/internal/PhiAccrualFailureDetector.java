package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.NodeAddress;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phi accrual failure detector for adaptive node liveness detection.
 *
 * <p>
 * Contract: Maintains a sliding window of heartbeat inter-arrival times per monitored node.
 * Computes phi = -log10(1 - CDF(timeSinceLastHeartbeat)) where CDF is based on a normal
 * distribution fitted to the heartbeat history. A higher phi value indicates a greater
 * likelihood that the node has failed. The detector is stateless for unknown nodes (returns
 * phi = 0.0 until at least two heartbeats have been recorded).
 *
 * <p>
 * Thread-safe: uses concurrent data structures for per-node state.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public final class PhiAccrualFailureDetector {

    private final int windowSize;
    private final ConcurrentHashMap<NodeAddress, Object> heartbeatHistory =
            new ConcurrentHashMap<>();

    /**
     * Creates a failure detector with the specified sliding window size.
     *
     * @param windowSize the maximum number of heartbeat intervals to retain; must be >= 2
     */
    public PhiAccrualFailureDetector(int windowSize) {
        if (windowSize < 2) {
            throw new IllegalArgumentException(
                    "windowSize must be >= 2, got: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * Records a heartbeat from the specified node at the current time.
     *
     * @param from the address of the node that sent the heartbeat; must not be null
     */
    public void recordHeartbeat(NodeAddress from) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Computes the current phi value for the specified node.
     *
     * @param node the address of the node to check; must not be null
     * @return the phi value; 0.0 if insufficient history
     */
    public double phi(NodeAddress node) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Returns whether the specified node is considered available at the given threshold.
     *
     * @param node      the address of the node to check; must not be null
     * @param threshold the phi threshold above which the node is considered failed; must be positive
     * @return {@code true} if phi(node) is below the threshold
     */
    public boolean isAvailable(NodeAddress node, double threshold) {
        throw new UnsupportedOperationException("Not implemented");
    }
}

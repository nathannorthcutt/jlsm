package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;

import java.util.Objects;

/**
 * A cluster member with its address, lifecycle state, and incarnation number.
 *
 * <p>
 * Contract: Immutable value type representing a node's membership information at a point in time.
 * The {@code incarnation} number is incremented by a node when it refutes suspicion, allowing the
 * protocol to distinguish stale failure reports from current ones.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 *
 * @param address the node's address; must not be null
 * @param state the node's current membership state; must not be null
 * @param incarnation the node's incarnation number; must be non-negative
 *
 * @spec engine.clustering.R12 — member tracks state + monotonically increasing incarnation per node
 *       identity
 */
public record Member(NodeAddress address, MemberState state, long incarnation) {

    public Member {
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (incarnation < 0) {
            throw new IllegalArgumentException(
                    "incarnation must be non-negative, got: " + incarnation);
        }
    }
}

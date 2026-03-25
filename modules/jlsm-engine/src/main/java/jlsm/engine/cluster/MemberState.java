package jlsm.engine.cluster;

/**
 * Lifecycle state of a cluster member.
 *
 * <p>
 * Contract: Represents the three possible states in the membership protocol's failure
 * detection lifecycle. Transitions: {@code ALIVE → SUSPECTED → DEAD}. A node may also
 * transition directly from {@code ALIVE → DEAD} on explicit leave.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public enum MemberState {

    /** Node is reachable and participating in the cluster. */
    ALIVE,

    /** Node has missed liveness checks and is under suspicion. */
    SUSPECTED,

    /** Node has been confirmed unreachable and removed from the active view. */
    DEAD
}

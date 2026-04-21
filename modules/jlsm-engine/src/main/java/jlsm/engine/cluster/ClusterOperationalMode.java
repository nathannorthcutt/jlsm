package jlsm.engine.cluster;

/**
 * Operational mode of a {@link ClusteredEngine}.
 *
 * <p>
 * Contract: {@link #NORMAL} indicates the engine has quorum and accepts both reads and writes.
 * {@link #READ_ONLY} indicates quorum has been lost; reads continue to be served by locally-owned
 * partitions but all mutating operations must throw {@link QuorumLostException}. Mode transitions
 * are driven by membership-view change events evaluated against the configured quorum size.
 *
 * <p>
 * Side effects: none — this is a pure value type.
 *
 * <p>
 * {@code @spec engine.clustering.R41}
 */
public enum ClusterOperationalMode {

    /** Quorum present — reads and writes are accepted. */
    NORMAL,

    /** Quorum lost — writes must be rejected with {@link QuorumLostException}; reads continue. */
    READ_ONLY
}

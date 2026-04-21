package jlsm.engine.cluster;

import java.io.IOException;

/**
 * Thrown by clustered write operations when the engine is in READ_ONLY mode due to loss of quorum.
 *
 * <p>
 * Contract: Signals a deliberate write rejection by a {@link ClusteredEngine} whose operational
 * mode has been downgraded to {@link ClusterOperationalMode#READ_ONLY} because the membership view
 * no longer contains a quorum of known members. Callers may safely retry the operation once the
 * engine returns to {@link ClusterOperationalMode#NORMAL}.
 *
 * <p>
 * Side effects: none.
 *
 * <p>
 * {@code @spec engine.clustering.R41}
 */
public final class QuorumLostException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code QuorumLostException} with the given message.
     *
     * @param message the detail message; may be null
     */
    public QuorumLostException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code QuorumLostException} with the given message and cause.
     *
     * @param message the detail message; may be null
     * @param cause the underlying cause; may be null
     */
    public QuorumLostException(String message, Throwable cause) {
        super(message, cause);
    }
}

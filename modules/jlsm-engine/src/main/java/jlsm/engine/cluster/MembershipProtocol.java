package jlsm.engine.cluster;

import java.io.IOException;
import java.util.List;

/**
 * SPI for cluster membership management and failure detection.
 *
 * <p>
 * Contract: Manages the lifecycle of this node's membership in the cluster. After {@link #start},
 * the protocol actively monitors other members and maintains a consistent {@link MembershipView}.
 * Listeners are notified of view changes. {@link #leave} initiates a graceful departure. The
 * protocol must be closed when no longer needed.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public interface MembershipProtocol extends AutoCloseable {

    /**
     * Starts the membership protocol, joining the cluster using the given seed nodes.
     *
     * @param seeds the initial seed addresses to contact; must not be null
     * @throws IOException if the protocol fails to start or contact seeds
     */
    void start(List<NodeAddress> seeds) throws IOException;

    /**
     * Returns the current membership view.
     *
     * @return the current view; never null
     */
    MembershipView currentView();

    /**
     * Registers a listener for membership events.
     *
     * @param listener the listener to register; must not be null
     */
    void addListener(MembershipListener listener);

    /**
     * Removes a previously-registered listener.
     *
     * <p>
     * Used for rollback when a registering caller (e.g. {@code ClusteredEngine} ctor) fails partway
     * through and must release any listener it already installed. Implementations must tolerate
     * being called with a listener that was never registered — such a call is a best-effort no-op.
     *
     * <p>
     * The default implementation is a no-op so existing test stubs that never exercise rollback
     * need not implement it. Production implementations (e.g. {@code RapidMembership}) override
     * this to remove the listener from their internal listener list.
     *
     * @param listener the listener to remove; must not be null
     */
    default void removeListener(MembershipListener listener) {
        // no-op default for implementations that do not track listener removal.
    }

    /**
     * Initiates a graceful leave from the cluster.
     *
     * @throws IOException if the leave announcement fails
     */
    void leave() throws IOException;

    /**
     * Closes the protocol, stopping all background activity.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;
}

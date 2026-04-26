package jlsm.cluster;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * SPI for inter-node message transport in the cluster.
 *
 * <p>
 * Contract: Provides fire-and-forget sending ({@link #send}), request-response ({@link #request}),
 * and type-based handler registration ({@link #registerHandler}). Implementations must be
 * thread-safe. The transport must be closed when no longer needed.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 *
 * @spec engine.clustering.R27 — SPI with send (one-way), request (future response), registerHandler
 *       (by message type)
 * @spec engine.clustering.R29 — request returns a future that completes with the response or
 *       completes exceptionally on delivery failure (timeout is enforced at the caller layer)
 * @spec engine.clustering.R30 — handler registration keyed by message type; re-registration
 *       replaces atomically
 */
public interface ClusterTransport extends AutoCloseable {

    /**
     * Sends a message to the target node (fire-and-forget).
     *
     * @param target the destination node address; must not be null
     * @param msg the message to send; must not be null
     * @throws IOException if the send fails
     */
    void send(NodeAddress target, Message msg) throws IOException;

    /**
     * Sends a request message to the target node and returns a future for the response.
     *
     * @param target the destination node address; must not be null
     * @param msg the request message; must not be null
     * @return a future that completes with the response message; never null
     */
    CompletableFuture<Message> request(NodeAddress target, Message msg);

    /**
     * Registers a handler for messages of the specified type.
     *
     * @param type the message type to handle; must not be null
     * @param handler the handler to invoke for messages of this type; must not be null
     */
    void registerHandler(MessageType type, MessageHandler handler);

    /**
     * Deregisters the handler for the specified message type.
     *
     * @param type the message type whose handler should be removed; must not be null
     */
    void deregisterHandler(MessageType type);

    /**
     * Closes the transport, releasing any resources.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;
}

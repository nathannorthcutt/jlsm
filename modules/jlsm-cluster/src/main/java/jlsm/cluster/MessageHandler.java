package jlsm.cluster;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for incoming cluster messages of a specific {@link MessageType}.
 *
 * <p>
 * Contract: Registered with a {@link ClusterTransport} for a specific message type. When a message
 * of that type arrives, the handler is invoked with the sender address and the message. The handler
 * returns a future that completes with the response message, or completes exceptionally on failure.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handles an incoming message from the specified sender.
     *
     * @param sender the address of the node that sent the message; never null
     * @param msg the received message; never null
     * @return a future completing with the response message, or exceptionally on error; never null
     */
    CompletableFuture<Message> handle(NodeAddress sender, Message msg);
}

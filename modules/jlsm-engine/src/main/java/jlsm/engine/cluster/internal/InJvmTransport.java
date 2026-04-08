package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageHandler;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-JVM transport for testing and single-process multi-engine deployments.
 *
 * <p>
 * Contract: Maintains a static registry of all in-JVM transport instances keyed by
 * {@link NodeAddress}. Messages are delivered by directly invoking the target's registered handler
 * — no serialization or networking occurs. Thread-safe via concurrent data structures.
 *
 * <p>
 * Side effects: Modifies the static transport registry on creation and {@link #close()}.
 *
 * <p>
 * Governed by: {@code .decisions/transport-abstraction-design/adr.md}
 */
public final class InJvmTransport implements ClusterTransport {

    private static final ConcurrentHashMap<NodeAddress, InJvmTransport> REGISTRY = new ConcurrentHashMap<>();

    private final NodeAddress localAddress;
    private final ConcurrentHashMap<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates and registers a new in-JVM transport for the given local address.
     *
     * @param localAddress the address of the local node; must not be null
     * @throws IllegalArgumentException if the address is already registered
     */
    public InJvmTransport(NodeAddress localAddress) {
        Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.localAddress = localAddress;
        final var previous = REGISTRY.putIfAbsent(localAddress, this);
        if (previous != null) {
            throw new IllegalArgumentException("Address already registered: " + localAddress);
        }
    }

    @Override
    public void send(NodeAddress target, Message msg) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(msg, "msg must not be null");
        if (closed.get()) {
            throw new IOException("Transport is closed");
        }
        final var targetTransport = REGISTRY.get(target);
        if (targetTransport == null) {
            throw new IOException("No transport registered for target: " + target);
        }
        final var handler = targetTransport.handlers.get(msg.type());
        if (handler == null) {
            throw new IOException("No handler registered for type: " + msg.type());
        }
        try {
            handler.handle(localAddress, msg);
        } catch (RuntimeException _) {
            // Fire-and-forget: swallow handler exceptions so sending node is unaffected
        }
    }

    @ Override
    public CompletableFuture<Message> request(NodeAddress target, Message msg) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(msg, "msg must not be null");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("Transport is closed"));
        }
        final var targetTransport = REGISTRY.get(target);
        if (targetTransport == null) {
            return CompletableFuture
                    .failedFuture(new IOException("No transport registered for target: " + target));
        }
        final var handler = targetTransport.handlers.get(msg.type());
        if (handler == null) {
            return CompletableFuture
                    .failedFuture(new IOException("No handler registered for type: " + msg.type()));
        }
        return handler.handle(localAddress, msg);
    }

    @Override
    public void registerHandler(MessageType type, MessageHandler handler) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Cannot register handler on closed transport");
        }
        handlers.put(type, handler);
    }

    @Override
    public void deregisterHandler(MessageType type) {
        Objects.requireNonNull(type, "type must not be null");
        handlers.remove(type);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        REGISTRY.remove(localAddress);
        handlers.clear();
    }

    /**
     * Returns the handler registered for the given type, or null if none. Package-private for
     * testing.
     *
     * @param type the message type
     * @return the registered handler, or null
     */
    MessageHandler handler(MessageType type) {
        return handlers.get(type);
    }

    /**
     * Clears the global registry. For test cleanup only.
     */
    public static void clearRegistry() {
        for (final var transport : REGISTRY.values()) {
            transport.close();
        }
        REGISTRY.clear();
    }
}

package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageHandler;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * @spec engine.clustering.R31 — in-JVM transport implementation for testing (no network I/O)
 * @spec engine.clustering.R30 — handler registration replaces atomically via ConcurrentHashMap.put
 */
public final class InJvmTransport implements ClusterTransport {

    private static final ConcurrentHashMap<NodeAddress, InJvmTransport> REGISTRY = new ConcurrentHashMap<>();

    private final NodeAddress localAddress;
    private final ConcurrentHashMap<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArraySet<CompletableFuture<Message>> inFlightFutures = new CopyOnWriteArraySet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    // @spec engine.clustering.R32 — fault-injection knobs default to zero delay / zero loss.
    private volatile Duration deliveryDelay = Duration.ZERO;
    private volatile double messageLossRate = 0.0;

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

    // @spec engine.clustering.R32 — configure the per-send delivery delay for fault-injection
    // testing.
    /**
     * Sets the simulated per-delivery delay. The default is {@link Duration#ZERO}. Must not be
     * negative.
     *
     * @param delay the simulated delay; must not be null or negative
     */
    public void setDeliveryDelay(Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative, got: " + delay);
        }
        this.deliveryDelay = delay;
    }

    // @spec engine.clustering.R32 — configure the simulated message loss rate for fault-injection
    // testing.
    /**
     * Sets the simulated per-send message loss probability. Must be in [0.0, 1.0]. The default is
     * {@code 0.0} (no loss).
     *
     * @param rate the simulated loss rate in [0.0, 1.0]
     */
    public void setMessageLossRate(double rate) {
        if (!(rate >= 0.0) || !(rate <= 1.0)) {
            throw new IllegalArgumentException("rate must be in [0.0, 1.0], got: " + rate);
        }
        this.messageLossRate = rate;
    }

    @Override
    public void send(NodeAddress target, Message msg) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(msg, "msg must not be null");
        // @spec engine.clustering.R81 — closed transport rejects send with IllegalStateException
        // (not
        // IOException)
        if (closed.get()) {
            throw new IllegalStateException("Transport is closed");
        }
        // @spec engine.clustering.R32 — simulate message loss by silently dropping a fraction of
        // sends.
        if (messageLossRate > 0.0 && ThreadLocalRandom.current().nextDouble() < messageLossRate) {
            return;
        }
        applyDeliveryDelay();
        final var targetTransport = REGISTRY.get(target);
        // @spec engine.clustering.R28 — delivery failures (unreachable target or handler) are
        // silently absorbed;
        // the failure detector is the mechanism for detecting unreachable nodes.
        if (targetTransport == null) {
            return;
        }
        final var handler = targetTransport.handlers.get(msg.type());
        if (handler == null) {
            return;
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
        // @spec engine.clustering.R81 — closed transport rejects request with IllegalStateException
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
        }
        // @spec engine.clustering.R32 — simulate message loss on requests by completing with
        // unreachable.
        if (messageLossRate > 0.0 && ThreadLocalRandom.current().nextDouble() < messageLossRate) {
            return CompletableFuture
                    .failedFuture(new IOException("Simulated message loss to: " + target));
        }
        applyDeliveryDelay();
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
        final CompletableFuture<Message> result = handler.handle(localAddress, msg);
        // @spec engine.clustering.R81 — track in-flight futures so close() can complete them
        // exceptionally.
        if (!result.isDone()) {
            inFlightFutures.add(result);
            result.whenComplete((_, _) -> inFlightFutures.remove(result));
            // Re-check close: if close() ran between the isDone() check and the add, the future
            // may still be pending — complete it ourselves to match R81.
            if (closed.get() && !result.isDone()) {
                result.completeExceptionally(new IllegalStateException("Transport is closed"));
            }
        }
        return result;
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
        // @spec engine.clustering.R81 — complete any in-flight response futures exceptionally on
        // close.
        for (final CompletableFuture<Message> f : inFlightFutures) {
            if (!f.isDone()) {
                f.completeExceptionally(new IllegalStateException("Transport closed"));
            }
        }
        inFlightFutures.clear();
    }

    private void applyDeliveryDelay() {
        final Duration d = deliveryDelay;
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis(), (int) (d.toNanosPart() % 1_000_000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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

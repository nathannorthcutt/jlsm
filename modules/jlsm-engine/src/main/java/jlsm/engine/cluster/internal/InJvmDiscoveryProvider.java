package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.DiscoveryProvider;
import jlsm.engine.cluster.NodeAddress;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-JVM discovery provider for testing and single-process multi-engine deployments.
 *
 * <p>
 * Contract: Maintains a static shared set of registered node addresses. {@link #register} adds the
 * address, {@link #deregister} removes it, and {@link #discoverSeeds()} returns a snapshot of all
 * currently registered addresses. Thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>
 * Side effects: Modifies the static shared registration set.
 *
 * <p>
 * Governed by: {@code .decisions/discovery-spi-design/adr.md}
 *
 * @spec engine.clustering.R26 — in-JVM discovery provider backed by a thread-safe shared registry
 * @spec engine.clustering.R24 — register is idempotent (map put reuses same key)
 * @spec engine.clustering.R25 — deregister is idempotent (remove on absent key is a no-op)
 */
public final class InJvmDiscoveryProvider implements DiscoveryProvider, AutoCloseable {

    private static final ConcurrentHashMap<NodeAddress, Boolean> REGISTERED = new ConcurrentHashMap<>();

    /** Addresses registered through this instance, deregistered on {@link #close()}. */
    private final Set<NodeAddress> ownRegistrations = new CopyOnWriteArraySet<>();

    @Override
    public Set<NodeAddress> discoverSeeds() {
        return Set.copyOf(REGISTERED.keySet());
    }

    @Override
    public void register(NodeAddress self) {
        Objects.requireNonNull(self, "self must not be null");
        REGISTERED.put(self, Boolean.TRUE);
        ownRegistrations.add(self);
    }

    @Override
    public void deregister(NodeAddress self) {
        Objects.requireNonNull(self, "self must not be null");
        REGISTERED.remove(self);
        ownRegistrations.remove(self);
    }

    /**
     * Deregisters all addresses that were registered through this instance. Prevents static state
     * leaking across lifecycle boundaries (e.g., between test runs).
     */
    @Override
    public void close() {
        for (NodeAddress addr : ownRegistrations) {
            REGISTERED.remove(addr);
        }
        ownRegistrations.clear();
    }

    /**
     * Clears the global registration set. For test cleanup only.
     */
    public static void clearRegistrations() {
        REGISTERED.clear();
    }
}

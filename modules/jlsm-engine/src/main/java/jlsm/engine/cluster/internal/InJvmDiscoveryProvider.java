package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.DiscoveryProvider;
import jlsm.engine.cluster.NodeAddress;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-JVM discovery provider for testing and single-process multi-engine deployments.
 *
 * <p>
 * Contract: Maintains a static shared set of registered node addresses. {@link #register}
 * adds the address, {@link #deregister} removes it, and {@link #discoverSeeds()} returns
 * a snapshot of all currently registered addresses. Thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>
 * Side effects: Modifies the static shared registration set.
 *
 * <p>
 * Governed by: {@code .decisions/discovery-spi-design/adr.md}
 */
public final class InJvmDiscoveryProvider implements DiscoveryProvider {

    private static final ConcurrentHashMap<NodeAddress, Boolean> REGISTERED =
            new ConcurrentHashMap<>();

    @Override
    public Set<NodeAddress> discoverSeeds() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void register(NodeAddress self) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void deregister(NodeAddress self) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Clears the global registration set. For test cleanup only.
     */
    static void clearRegistrations() {
        REGISTERED.clear();
    }
}

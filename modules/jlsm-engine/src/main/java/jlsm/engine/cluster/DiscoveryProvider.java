package jlsm.engine.cluster;

import java.io.IOException;
import java.util.Set;

/**
 * SPI for discovering seed nodes during cluster bootstrap.
 *
 * <p>
 * Contract: Provides a pluggable mechanism for engines to discover initial cluster members. The
 * required method {@link #discoverSeeds()} returns a set of known seed addresses. Optional
 * {@link #register}/{@link #deregister} methods support self-announcement in environments without
 * managed service discovery. Stale registrations from crashed nodes are harmless — the membership
 * protocol handles liveness.
 *
 * <p>
 * Governed by: {@code .decisions/discovery-spi-design/adr.md}
 *
 * @spec engine.clustering.R22 — pluggable SPI with three operations: discover seeds, register self,
 *       deregister self
 * @spec engine.clustering.R23 — discoverSeeds returns an (optionally empty) set of seed addresses
 * @spec engine.clustering.R24 — register must be idempotent
 * @spec engine.clustering.R25 — deregister must be idempotent (tolerates not-registered)
 */
public interface DiscoveryProvider {

    /**
     * Discovers seed node addresses for cluster bootstrap.
     *
     * @return a set of seed addresses; never null, may be empty if no seeds are known
     * @throws IOException if discovery fails
     */
    Set<NodeAddress> discoverSeeds() throws IOException;

    /**
     * Registers this node's address with the discovery service.
     *
     * <p>
     * Default implementation is a no-op for discovery providers that do not support
     * self-registration (e.g., static seed lists).
     *
     * @param self the address of this node; must not be null
     */
    default void register(NodeAddress self) {
    }

    /**
     * Deregisters this node's address from the discovery service.
     *
     * <p>
     * Default implementation is a no-op.
     *
     * @param self the address of this node; must not be null
     */
    default void deregister(NodeAddress self) {
    }
}

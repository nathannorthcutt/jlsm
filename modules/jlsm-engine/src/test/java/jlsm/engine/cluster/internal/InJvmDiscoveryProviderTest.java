package jlsm.engine.cluster.internal;

import jlsm.cluster.NodeAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InJvmDiscoveryProvider} — register, discover, deregister.
 *
 * @spec engine.clustering.R22 — pluggable SPI: discover / register / deregister
 * @spec engine.clustering.R23 — empty seed set is valid (solo cluster bootstrap)
 * @spec engine.clustering.R24 — register idempotent
 * @spec engine.clustering.R25 — deregister idempotent
 * @spec engine.clustering.R26 — in-JVM discovery implementation with thread-safe shared registry
 */
class InJvmDiscoveryProviderTest {

    private static final NodeAddress ADDR_A = new NodeAddress("a", "localhost", 8001);
    private static final NodeAddress ADDR_B = new NodeAddress("b", "localhost", 8002);

    @AfterEach
    void cleanup() {
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @Test
    void discoverEmptyReturnsEmptySet() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        var seeds = provider.discoverSeeds();
        assertNotNull(seeds);
        assertTrue(seeds.isEmpty());
    }

    @Test
    void registerAndDiscover() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        provider.register(ADDR_A);
        var seeds = provider.discoverSeeds();
        assertEquals(1, seeds.size());
        assertTrue(seeds.contains(ADDR_A));
    }

    @Test
    void registerMultipleAndDiscover() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        provider.register(ADDR_A);
        provider.register(ADDR_B);
        var seeds = provider.discoverSeeds();
        assertEquals(2, seeds.size());
        assertTrue(seeds.contains(ADDR_A));
        assertTrue(seeds.contains(ADDR_B));
    }

    @Test
    void deregisterRemovesAddress() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        provider.register(ADDR_A);
        provider.register(ADDR_B);
        provider.deregister(ADDR_A);
        var seeds = provider.discoverSeeds();
        assertEquals(1, seeds.size());
        assertFalse(seeds.contains(ADDR_A));
        assertTrue(seeds.contains(ADDR_B));
    }

    @Test
    void deregisterNonexistentIsNoOp() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        provider.deregister(ADDR_A); // Should not throw
        var seeds = provider.discoverSeeds();
        assertTrue(seeds.isEmpty());
    }

    @Test
    void registerNullThrows() {
        var provider = new InJvmDiscoveryProvider();
        assertThrows(NullPointerException.class, () -> provider.register(null));
    }

    @Test
    void deregisterNullThrows() {
        var provider = new InJvmDiscoveryProvider();
        assertThrows(NullPointerException.class, () -> provider.deregister(null));
    }

    @Test
    void sharedStateBetweenInstances() throws IOException {
        var provider1 = new InJvmDiscoveryProvider();
        var provider2 = new InJvmDiscoveryProvider();
        provider1.register(ADDR_A);
        var seeds = provider2.discoverSeeds();
        assertEquals(1, seeds.size());
        assertTrue(seeds.contains(ADDR_A));
    }

    @Test
    void discoverSeedsReturnsSnapshot() throws IOException {
        var provider = new InJvmDiscoveryProvider();
        provider.register(ADDR_A);
        var seeds = provider.discoverSeeds();
        provider.register(ADDR_B);
        // Snapshot should not change
        assertEquals(1, seeds.size());
    }
}

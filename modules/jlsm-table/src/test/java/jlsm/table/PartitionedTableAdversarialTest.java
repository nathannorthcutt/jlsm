package jlsm.table;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link PartitionedTable}.
 */
class PartitionedTableAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    // --- PT-1: Builder.build() leaks clients on factory failure ---

    /**
     * Finding PT-1: If the factory throws for partition N, clients 0..N-1 are leaked.
     * KB match: multi-index-atomicity — sequential operations leave inconsistent state on Nth failure.
     */
    @Test
    void build_factoryThrowsOnSecondPartition_closesAlreadyCreatedClients() throws IOException {
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("m"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));

        final AtomicInteger closeCount = new AtomicInteger(0);

        // Factory: first partition returns a tracking stub, second throws
        final var ex = assertThrows(RuntimeException.class,
                () -> PartitionedTable.builder().partitionConfig(config)
                        .partitionClientFactory(desc -> {
                            if (desc.id() == 1L) {
                                return new TrackingStubClient(desc, closeCount);
                            }
                            throw new RuntimeException("simulated factory failure");
                        }).build());

        assertTrue(ex.getMessage().contains("simulated"),
                "should propagate factory exception");
        assertEquals(1, closeCount.get(),
                "client created for partition 1 must be closed when partition 2 factory fails");
    }

    /**
     * Finding PT-1: Variant with 3 partitions — factory fails on the 3rd.
     * Clients 1 and 2 must both be closed.
     */
    @Test
    void build_factoryThrowsOnThirdPartition_closesAllPriorClients() throws IOException {
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("h"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("h"), seg("p"), "local",
                0L);
        final PartitionDescriptor desc3 = new PartitionDescriptor(3L, seg("p"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2, desc3));

        final AtomicInteger closeCount = new AtomicInteger(0);

        assertThrows(RuntimeException.class,
                () -> PartitionedTable.builder().partitionConfig(config)
                        .partitionClientFactory(desc -> {
                            if (desc.id() == 3L) {
                                throw new RuntimeException("fail on third");
                            }
                            return new TrackingStubClient(desc, closeCount);
                        }).build());

        assertEquals(2, closeCount.get(),
                "both prior clients must be closed when 3rd factory call fails");
    }

    // --- PT-2: Builder.build() with duplicate descriptor IDs ---

    /**
     * Finding PT-2: If two descriptors share the same id, the second client overwrites the first
     * in the map, leaking the first client.
     */
    @Test
    void build_duplicateDescriptorIds_rejectsOrHandlesGracefully() throws IOException {
        // Two descriptors with the same id but different ranges
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(1L, seg("m"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));

        final AtomicInteger closeCount = new AtomicInteger(0);

        // Should either reject duplicate IDs or close the overwritten client
        try {
            final var table = PartitionedTable.builder().partitionConfig(config)
                    .partitionClientFactory(desc -> new TrackingStubClient(desc, closeCount))
                    .build();
            // If build succeeds, verify both clients aren't silently lost
            table.close();
            // The first client should have been closed either during build (replaced) or on
            // table.close()
            assertEquals(2, closeCount.get(),
                    "both clients must be closed — the overwritten one must not be leaked");
        } catch (IllegalArgumentException | IllegalStateException _) {
            // Rejecting duplicate IDs at build time is also acceptable
        }
    }

    // -------------------------------------------------------------------------
    // Stub client that tracks close() calls
    // -------------------------------------------------------------------------

    private static class TrackingStubClient implements PartitionClient {

        private final PartitionDescriptor descriptor;
        private final AtomicInteger closeCounter;

        TrackingStubClient(PartitionDescriptor descriptor, AtomicInteger closeCounter) {
            this.descriptor = descriptor;
            this.closeCounter = closeCounter;
        }

        @Override
        public PartitionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public Iterator<TableEntry<String>> getRange(String fromKey, String toKey) {
            return List.<TableEntry<String>>of().iterator();
        }

        @Override
        public List<ScoredEntry<String>> query(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() throws IOException {
            closeCounter.incrementAndGet();
        }
    }
}

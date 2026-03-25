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
     * Finding PT-1: If the factory throws for partition N, clients 0..N-1 are leaked. KB match:
     * multi-index-atomicity — sequential operations leave inconsistent state on Nth failure.
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
        final var ex = assertThrows(RuntimeException.class, () -> PartitionedTable.builder()
                .partitionConfig(config).partitionClientFactory(desc -> {
                    if (desc.id() == 1L) {
                        return new TrackingStubClient(desc, closeCount);
                    }
                    throw new RuntimeException("simulated factory failure");
                }).build());

        assertTrue(ex.getMessage().contains("simulated"), "should propagate factory exception");
        assertEquals(1, closeCount.get(),
                "client created for partition 1 must be closed when partition 2 factory fails");
    }

    /**
     * Finding PT-1: Variant with 3 partitions — factory fails on the 3rd. Clients 1 and 2 must both
     * be closed.
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

        assertThrows(RuntimeException.class, () -> PartitionedTable.builder()
                .partitionConfig(config).partitionClientFactory(desc -> {
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
     * Finding PT-2: If two descriptors share the same id, the second client overwrites the first in
     * the map, leaking the first client. After PC-3 fix, PartitionConfig.of() now rejects duplicate
     * IDs at the config layer, providing defense-in-depth.
     */
    @Test
    void build_duplicateDescriptorIds_rejectedAtConfigLayer() {
        // Two descriptors with the same id but different ranges — rejected by PartitionConfig.of()
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(1L, seg("m"), seg("{"), "local",
                0L);
        assertThrows(IllegalArgumentException.class,
                () -> PartitionConfig.of(List.of(desc1, desc2)),
                "PartitionConfig must reject duplicate partition IDs");
    }

    // --- PT-3: close() does not catch RuntimeException (Round 2) ---

    /**
     * Finding PT-3: PartitionedTable.close() only catches IOException. If a PartitionClient.close()
     * throws RuntimeException, remaining clients are never closed — resource leak. project-rule:
     * coding-guidelines (deferred close pattern must accumulate all exceptions).
     */
    @Test
    void close_clientThrowsRuntimeException_remainingClientsStillClosed() throws IOException {
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("m"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));

        final AtomicInteger closeCount = new AtomicInteger(0);

        final PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .partitionClientFactory(desc -> {
                    if (desc.id() == 1L) {
                        // Client 1 throws RuntimeException on close
                        return new TrackingStubClient(desc, closeCount) {
                            @Override
                            public void close() {
                                closeCount.incrementAndGet();
                                throw new RuntimeException("simulated runtime close failure");
                            }
                        };
                    }
                    return new TrackingStubClient(desc, closeCount);
                }).build();

        // close() should still close client 2 even though client 1 threw RuntimeException.
        // The RuntimeException is wrapped in IOException by the deferred-close pattern.
        try {
            table.close();
        } catch (IOException _) {
            // Expected — we just need to verify both clients were closed
        }

        assertEquals(2, closeCount.get(),
                "All clients must be closed even when one throws RuntimeException");
    }

    // --- PT-4: closeAllClients() swallows close exceptions (Round 2) ---

    /**
     * Finding PT-4: When the factory fails and previously-created clients are cleaned up, any
     * IOException from their close() is silently swallowed by closeAllClients(). These should be
     * added as suppressed exceptions to the original failure. project-rule: coding-guidelines
     * (deferred close pattern).
     */
    @Test
    void build_factoryFails_closeExceptionsAddedAsSuppressed() {
        final PartitionDescriptor desc1 = new PartitionDescriptor(1L, seg("a"), seg("m"), "local",
                0L);
        final PartitionDescriptor desc2 = new PartitionDescriptor(2L, seg("m"), seg("{"), "local",
                0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc1, desc2));

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> PartitionedTable
                .builder().partitionConfig(config).partitionClientFactory(desc -> {
                    if (desc.id() == 1L) {
                        // Client 1 will fail on close during cleanup
                        return new TrackingStubClient(desc, new AtomicInteger(0)) {
                            @Override
                            public void close() throws IOException {
                                throw new IOException("cleanup close failed");
                            }
                        };
                    }
                    throw new RuntimeException("factory failure on partition 2");
                }).build());

        assertTrue(thrown.getMessage().contains("factory failure"),
                "original exception should propagate");

        // The close exception from client 1 should be attached as suppressed
        final Throwable[] suppressed = thrown.getSuppressed();
        assertTrue(suppressed.length > 0,
                "close exceptions during cleanup must be added as suppressed, "
                        + "not silently swallowed");
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

package jlsm.encryption.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;
import jlsm.encryption.spi.CompactionInputRegistry.CompactionId;
import jlsm.encryption.spi.CompactionInputRegistry.SSTableId;
import jlsm.encryption.spi.InMemorySpis.InMemoryCompactionInputRegistry;
import jlsm.encryption.spi.InMemorySpis.InMemoryWalLivenessSource;

/**
 * Tests for the in-memory test fakes ({@link InMemoryCompactionInputRegistry},
 * {@link InMemoryWalLivenessSource}). These fakes underpin all DEK-pruner scenarios so their
 * correctness is load-bearing.
 *
 * @spec encryption.primitives-lifecycle R30b
 * @spec encryption.primitives-lifecycle R30c
 * @spec encryption.primitives-lifecycle R75c
 */
class InMemorySpisTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final KekRef KEK_REF_1 = new KekRef("kek/v1");
    private static final KekRef KEK_REF_2 = new KekRef("kek/v2");

    @Test
    void utilityClass_constructorRejected() {
        // The InMemorySpis container is a holder for nested fakes; instantiating the wrapper has
        // no meaning. Reflection access is required because the constructor is private.
        assertThrows(Exception.class, () -> {
            var ctor = InMemorySpis.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            try {
                ctor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        });
    }

    // --- InMemoryCompactionInputRegistry ---------------------------------

    @Test
    void compactionInputs_emptyRegistry_currentInputSetIsEmpty() {
        final var reg = new InMemoryCompactionInputRegistry();
        assertTrue(reg.currentInputSet().isEmpty());
    }

    @Test
    void compactionInputs_registerInputs_appearInCurrentInputSet() {
        final var reg = new InMemoryCompactionInputRegistry();
        final SSTableId t1 = new SSTableId("sst-1");
        final SSTableId t2 = new SSTableId("sst-2");
        reg.registerInputs(new CompactionId("c1"), Set.of(t1, t2));
        assertEquals(Set.of(t1, t2), reg.currentInputSet());
    }

    @Test
    void compactionInputs_currentInputSet_isUnionOfAllRegistered() {
        final var reg = new InMemoryCompactionInputRegistry();
        final SSTableId t1 = new SSTableId("sst-1");
        final SSTableId t2 = new SSTableId("sst-2");
        final SSTableId t3 = new SSTableId("sst-3");
        reg.registerInputs(new CompactionId("c1"), Set.of(t1, t2));
        reg.registerInputs(new CompactionId("c2"), Set.of(t2, t3));
        assertEquals(Set.of(t1, t2, t3), reg.currentInputSet());
    }

    @Test
    void compactionInputs_reregisterSameId_replacesPriorSet() {
        final var reg = new InMemoryCompactionInputRegistry();
        final CompactionId id = new CompactionId("c1");
        final SSTableId t1 = new SSTableId("sst-1");
        final SSTableId t2 = new SSTableId("sst-2");
        reg.registerInputs(id, Set.of(t1));
        reg.registerInputs(id, Set.of(t2));
        assertEquals(Set.of(t2), reg.currentInputSet());
    }

    @Test
    void compactionInputs_deregister_removesFromUnion() {
        final var reg = new InMemoryCompactionInputRegistry();
        final SSTableId t1 = new SSTableId("sst-1");
        final SSTableId t2 = new SSTableId("sst-2");
        final CompactionId c1 = new CompactionId("c1");
        final CompactionId c2 = new CompactionId("c2");
        reg.registerInputs(c1, Set.of(t1));
        reg.registerInputs(c2, Set.of(t2));
        reg.deregisterInputs(c1);
        assertEquals(Set.of(t2), reg.currentInputSet());
    }

    @Test
    void compactionInputs_deregisterUnknownId_noOp() {
        final var reg = new InMemoryCompactionInputRegistry();
        // Must not throw.
        reg.deregisterInputs(new CompactionId("never-registered"));
        assertTrue(reg.currentInputSet().isEmpty());
    }

    @Test
    void compactionInputs_currentInputSet_isImmutable() {
        final var reg = new InMemoryCompactionInputRegistry();
        reg.registerInputs(new CompactionId("c1"), Set.of(new SSTableId("sst-1")));
        final Set<SSTableId> snap = reg.currentInputSet();
        assertThrows(UnsupportedOperationException.class,
                () -> snap.add(new SSTableId("interloper")));
    }

    @Test
    void compactionInputs_registerInputs_nullArgsThrow() {
        final var reg = new InMemoryCompactionInputRegistry();
        assertThrows(NullPointerException.class,
                () -> reg.registerInputs(null, Set.of(new SSTableId("sst"))));
        assertThrows(NullPointerException.class,
                () -> reg.registerInputs(new CompactionId("c1"), null));
    }

    @Test
    void compactionInputs_deregister_nullArgThrows() {
        final var reg = new InMemoryCompactionInputRegistry();
        assertThrows(NullPointerException.class, () -> reg.deregisterInputs(null));
    }

    @Test
    void compactionInputs_registerInputs_inputSetIsDefensivelyCopied() {
        final var reg = new InMemoryCompactionInputRegistry();
        final HashSet<SSTableId> mutable = new HashSet<>();
        mutable.add(new SSTableId("sst-1"));
        reg.registerInputs(new CompactionId("c1"), mutable);
        // After registration, mutating the caller-supplied set must not affect the registry.
        mutable.add(new SSTableId("interloper"));
        assertEquals(Set.of(new SSTableId("sst-1")), reg.currentInputSet());
    }

    @Test
    void compactionInputs_concurrentRegistrations_finalUnionIsCorrect() throws Exception {
        // Many threads register distinct compaction ids concurrently; the final union must
        // contain every registered SSTable.
        final var reg = new InMemoryCompactionInputRegistry();
        final int threads = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        reg.registerInputs(new CompactionId("c-" + idx),
                                Set.of(new SSTableId("sst-" + idx)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            final Set<SSTableId> union = reg.currentInputSet();
            assertEquals(threads, union.size());
        } finally {
            pool.shutdownNow();
        }
    }

    // --- InMemoryWalLivenessSource ---------------------------------------

    @Test
    void walLiveness_unsetCount_returnsZero() {
        final var src = new InMemoryWalLivenessSource();
        assertEquals(0L, src.dependsOnKekRef(TENANT_A, KEK_REF_1));
    }

    @Test
    void walLiveness_setCount_dependsOnKekRefReturnsValue() {
        final var src = new InMemoryWalLivenessSource();
        src.setCount(TENANT_A, KEK_REF_1, 7L);
        assertEquals(7L, src.dependsOnKekRef(TENANT_A, KEK_REF_1));
    }

    @Test
    void walLiveness_setCount_overwritesPriorValue() {
        final var src = new InMemoryWalLivenessSource();
        src.setCount(TENANT_A, KEK_REF_1, 7L);
        src.setCount(TENANT_A, KEK_REF_1, 0L);
        assertEquals(0L, src.dependsOnKekRef(TENANT_A, KEK_REF_1));
    }

    @Test
    void walLiveness_perTenantPerKekRefIsolation() {
        final var src = new InMemoryWalLivenessSource();
        src.setCount(TENANT_A, KEK_REF_1, 1L);
        src.setCount(TENANT_A, KEK_REF_2, 2L);
        src.setCount(TENANT_B, KEK_REF_1, 3L);
        assertEquals(1L, src.dependsOnKekRef(TENANT_A, KEK_REF_1));
        assertEquals(2L, src.dependsOnKekRef(TENANT_A, KEK_REF_2));
        assertEquals(3L, src.dependsOnKekRef(TENANT_B, KEK_REF_1));
    }

    @Test
    void walLiveness_dependsOnKekRef_nullArgsThrow() {
        final var src = new InMemoryWalLivenessSource();
        assertThrows(NullPointerException.class, () -> src.dependsOnKekRef(null, KEK_REF_1));
        assertThrows(NullPointerException.class, () -> src.dependsOnKekRef(TENANT_A, null));
    }

    @Test
    void walLiveness_setCount_nullArgsThrow() {
        final var src = new InMemoryWalLivenessSource();
        assertThrows(NullPointerException.class, () -> src.setCount(null, KEK_REF_1, 1L));
        assertThrows(NullPointerException.class, () -> src.setCount(TENANT_A, null, 1L));
    }
}

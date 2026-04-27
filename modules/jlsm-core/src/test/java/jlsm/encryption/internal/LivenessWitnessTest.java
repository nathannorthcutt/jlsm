package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link LivenessWitness} — per-tenant durable monotonic counter of artifacts (SSTables +
 * WAL segments) whose wrapping chain depends on a given retired {@link KekRef}.
 *
 * @spec encryption.primitives-lifecycle R75c
 * @spec encryption.primitives-lifecycle R78e
 */
class LivenessWitnessTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final KekRef REF_1 = new KekRef("kek/v1");
    private static final KekRef REF_2 = new KekRef("kek/v2");

    @Test
    void open_nullRoot_throwsNpe() {
        assertThrows(NullPointerException.class, () -> LivenessWitness.open(null));
    }

    @Test
    void open_returnsNonNull(@TempDir Path tempDir) {
        assertNotNull(LivenessWitness.open(tempDir));
    }

    @Test
    void initialCount_isZero(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        assertEquals(0L, w.count(TENANT_A, REF_1));
    }

    @Test
    void increment_returnsNewCount_andIsObservable(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        assertEquals(1L, w.increment(TENANT_A, REF_1));
        assertEquals(2L, w.increment(TENANT_A, REF_1));
        assertEquals(2L, w.count(TENANT_A, REF_1));
    }

    @Test
    void decrement_returnsNewCount(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_1);
        assertEquals(2L, w.decrement(TENANT_A, REF_1));
        assertEquals(1L, w.decrement(TENANT_A, REF_1));
        assertEquals(0L, w.decrement(TENANT_A, REF_1));
    }

    @Test
    void decrement_belowZero_throwsIse(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        // Count is 0; decrementing must throw rather than silently underflow.
        assertThrows(IllegalStateException.class, () -> w.decrement(TENANT_A, REF_1));
    }

    @Test
    void countsAreScopedByTenant(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_B, REF_1);
        assertEquals(2L, w.count(TENANT_A, REF_1));
        assertEquals(1L, w.count(TENANT_B, REF_1));
    }

    @Test
    void countsAreScopedByKekRef(@TempDir Path tempDir) throws IOException {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_2);
        w.increment(TENANT_A, REF_2);
        assertEquals(1L, w.count(TENANT_A, REF_1));
        assertEquals(2L, w.count(TENANT_A, REF_2));
    }

    @Test
    void increment_nullArgs_throwNpe(@TempDir Path tempDir) {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        assertThrows(NullPointerException.class, () -> w.increment(null, REF_1));
        assertThrows(NullPointerException.class, () -> w.increment(TENANT_A, null));
    }

    @Test
    void decrement_nullArgs_throwNpe(@TempDir Path tempDir) {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        assertThrows(NullPointerException.class, () -> w.decrement(null, REF_1));
        assertThrows(NullPointerException.class, () -> w.decrement(TENANT_A, null));
    }

    @Test
    void count_nullArgs_throwNpe(@TempDir Path tempDir) {
        final LivenessWitness w = LivenessWitness.open(tempDir);
        assertThrows(NullPointerException.class, () -> w.count(null, REF_1));
        assertThrows(NullPointerException.class, () -> w.count(TENANT_A, null));
    }

    @Test
    void countsPersistAcrossReopen(@TempDir Path tempDir) throws IOException {
        // R78e — the witness must be durable. Counts updated by one process must be visible
        // to a fresh open() over the same root.
        LivenessWitness w = LivenessWitness.open(tempDir);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_1);
        w.increment(TENANT_A, REF_1);
        // No explicit close; the witness commits each update durably.
        final LivenessWitness reopened = LivenessWitness.open(tempDir);
        assertEquals(3L, reopened.count(TENANT_A, REF_1));
    }

    @Test
    void incrementsAreMonotonic_acrossManyOps(@TempDir Path tempDir) throws IOException {
        // Bounded loop — never recursive. Ensures the witness handles a workload-realistic
        // burst without losing updates.
        final LivenessWitness w = LivenessWitness.open(tempDir);
        long previous = 0L;
        for (int i = 0; i < 100; i++) {
            final long observed = w.increment(TENANT_A, REF_1);
            org.junit.jupiter.api.Assertions.assertEquals(previous + 1, observed,
                    "increments must be monotonic");
            previous = observed;
        }
        assertEquals(100L, w.count(TENANT_A, REF_1));
    }
}

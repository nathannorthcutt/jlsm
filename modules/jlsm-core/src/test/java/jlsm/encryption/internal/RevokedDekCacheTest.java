package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link RevokedDekCache} (R83g, R83a). In-process per-DEK short-circuit cache;
 * markRevoked is monotonic and returns true once per handle; subsequent calls return false to
 * support per-detection-epoch dedup of state-machine counter increments (R83a).
 *
 * @spec encryption.primitives-lifecycle R83g
 * @spec encryption.primitives-lifecycle R83a
 */
class RevokedDekCacheTest {

    private static DekHandle handle(int v) {
        return new DekHandle(new TenantId("t"), new DomainId("d"), new TableId("tab"),
                new DekVersion(v));
    }

    @Test
    void emptyCacheReportsHandlesAsNotRevoked() {
        final RevokedDekCache c = RevokedDekCache.create();
        assertFalse(c.isRevoked(handle(1)));
    }

    @Test
    void markRevokedReturnsTrueOnFirstCall() {
        final RevokedDekCache c = RevokedDekCache.create();
        assertTrue(c.markRevoked(handle(1)),
                "first markRevoked must return true (R83a dedup hook)");
    }

    @Test
    void markRevokedReturnsFalseOnSubsequentCalls() {
        final RevokedDekCache c = RevokedDekCache.create();
        c.markRevoked(handle(1));
        assertFalse(c.markRevoked(handle(1)),
                "subsequent markRevoked must return false (R83a dedup)");
        assertFalse(c.markRevoked(handle(1)));
    }

    @Test
    void distinctHandlesDoNotInterfere() {
        final RevokedDekCache c = RevokedDekCache.create();
        assertTrue(c.markRevoked(handle(1)));
        assertTrue(c.markRevoked(handle(2)));
        assertFalse(c.markRevoked(handle(1)));
    }

    @Test
    void isRevokedReflectsMembership() {
        final RevokedDekCache c = RevokedDekCache.create();
        c.markRevoked(handle(7));
        assertTrue(c.isRevoked(handle(7)));
        assertFalse(c.isRevoked(handle(8)));
    }

    @Test
    void markRevokedNullRejected() {
        final RevokedDekCache c = RevokedDekCache.create();
        assertThrows(NullPointerException.class, () -> c.markRevoked(null));
    }

    @Test
    void isRevokedNullRejected() {
        final RevokedDekCache c = RevokedDekCache.create();
        assertThrows(NullPointerException.class, () -> c.isRevoked(null));
    }

    @Test
    void monotonicityAcrossManyCalls() {
        // Once revoked, an entry never becomes un-revoked. Repeated reads stay true.
        final RevokedDekCache c = RevokedDekCache.create();
        c.markRevoked(handle(1));
        for (int i = 0; i < 1000; i++) {
            assertTrue(c.isRevoked(handle(1)));
        }
    }

    @Test
    void concurrentMarkRevokedExactlyOneWinner() throws Exception {
        final RevokedDekCache c = RevokedDekCache.create();
        final DekHandle h = handle(1);
        final int threads = 16;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(
                1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(
                threads);
        final java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors
                .newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        if (c.markRevoked(h)) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(winners.get() == 1, "exactly one concurrent caller must win the markRevoked "
                    + "race (R83a single-counter-increment invariant)");
        } finally {
            exec.shutdownNow();
        }
    }
}

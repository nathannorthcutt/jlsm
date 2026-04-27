package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.TenantId;
import jlsm.encryption.internal.ShardLockRegistry.ExclusiveStamp;
import jlsm.encryption.internal.ShardLockRegistry.ShardKey;

/**
 * Tests for {@link ShardLockRegistry} — per-shard StampedLock primitives with per-tenant isolation
 * (R32c, R34a, R32b-1).
 *
 * @spec encryption.primitives-lifecycle R32c
 * @spec encryption.primitives-lifecycle R34a
 * @spec encryption.primitives-lifecycle R32b-1
 */
class ShardLockRegistryTest {

    private static final TenantId TENANT_A = new TenantId("tenantA");
    private static final TenantId TENANT_B = new TenantId("tenantB");
    private static final DomainId DOMAIN_1 = new DomainId("domain-1");
    private static final DomainId DOMAIN_2 = new DomainId("domain-2");
    private static final Duration BUDGET = Duration.ofMillis(250);

    @Test
    void create_returnsNonNull() {
        assertNotNull(ShardLockRegistry.create());
    }

    // ── ShardKey factory invariants ──────────────────────────────────────────

    @Test
    void shardKey_tier1_setsShardIdAndNullDomain() {
        final ShardKey key = ShardKey.tier1(TENANT_A, "shard-99");
        assertEquals(TENANT_A, key.tenantId());
        assertEquals("shard-99", key.shardId());
        assertNull(key.domainId());
    }

    @Test
    void shardKey_tier2_setsDomainAndNullShardId() {
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        assertEquals(TENANT_A, key.tenantId());
        assertEquals(DOMAIN_1, key.domainId());
        assertNull(key.shardId());
    }

    @Test
    void shardKey_nullTenantId_throwsNpe() {
        assertThrows(NullPointerException.class, () -> ShardKey.tier1(null, "shard-1"));
        assertThrows(NullPointerException.class, () -> ShardKey.tier2(null, DOMAIN_1));
    }

    @Test
    void shardKey_bothDomainAndShardIdNull_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ShardKey(TENANT_A, null, null));
    }

    @Test
    void shardKey_equalityIncludesAllComponents() {
        assertEquals(ShardKey.tier1(TENANT_A, "s1"), ShardKey.tier1(TENANT_A, "s1"));
        assertNotEquals(ShardKey.tier1(TENANT_A, "s1"), ShardKey.tier1(TENANT_A, "s2"));
        assertNotEquals(ShardKey.tier1(TENANT_A, "s1"), ShardKey.tier1(TENANT_B, "s1"));
        assertNotEquals(ShardKey.tier2(TENANT_A, DOMAIN_1), ShardKey.tier2(TENANT_A, DOMAIN_2));
        assertNotEquals(ShardKey.tier1(TENANT_A, "s1"), ShardKey.tier2(TENANT_A, DOMAIN_1));
    }

    // ── Shared lock acquire/release ──────────────────────────────────────────

    @Test
    void acquireShared_thenReleaseShared_succeeds() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final long stamp = reg.acquireShared(key);
        assertDoesNotThrow(() -> reg.releaseShared(key, stamp));
    }

    @Test
    void acquireShared_multipleHoldersOnSameKey_doNotBlock() throws Exception {
        // R34a — concurrent DEK creation acquires shared lock; multiple shared holders coexist
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final int holders = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(holders);
        final CountDownLatch acquired = new CountDownLatch(holders);
        final CountDownLatch release = new CountDownLatch(1);
        final long[] stamps = new long[holders];
        try {
            for (int i = 0; i < holders; i++) {
                final int idx = i;
                pool.submit(() -> {
                    stamps[idx] = reg.acquireShared(key);
                    acquired.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    reg.releaseShared(key, stamps[idx]);
                });
            }
            assertTrue(acquired.await(5, TimeUnit.SECONDS),
                    "all shared holders must acquire concurrently");
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acquireShared_nullShardKey_throwsNpe() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        assertThrows(NullPointerException.class, () -> reg.acquireShared(null));
    }

    @Test
    void releaseShared_nullShardKey_throwsNpe() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        assertThrows(NullPointerException.class, () -> reg.releaseShared(null, 0L));
    }

    // ── Exclusive lock acquire/release ───────────────────────────────────────

    @Test
    void acquireExclusiveTimed_thenRelease_succeeds() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ExclusiveStamp stamp = reg.acquireExclusiveTimed(key, BUDGET);
        assertNotNull(stamp);
        assertEquals(key, stamp.shardKey());
        assertDoesNotThrow(() -> reg.releaseExclusive(stamp));
    }

    @Test
    void acquireExclusiveTimed_carriesDeadlineFromBudget() {
        // R32c — stamp records deadline at which lock auto-releases
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final long beforeNanos = System.nanoTime();
        final ExclusiveStamp stamp = reg.acquireExclusiveTimed(key, BUDGET);
        final long afterNanos = System.nanoTime();
        try {
            // Deadline must be at least beforeNanos + budget and at most afterNanos + budget
            final long minDeadline = beforeNanos + BUDGET.toNanos();
            final long maxDeadline = afterNanos + BUDGET.toNanos();
            assertTrue(stamp.deadlineNanos() >= minDeadline,
                    "deadline must reflect acquisition + budget");
            assertTrue(stamp.deadlineNanos() <= maxDeadline,
                    "deadline must not exceed acquisition + budget");
        } finally {
            reg.releaseExclusive(stamp);
        }
    }

    @Test
    void acquireExclusiveTimed_nullShardKey_throwsNpe() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        assertThrows(NullPointerException.class, () -> reg.acquireExclusiveTimed(null, BUDGET));
    }

    @Test
    void acquireExclusiveTimed_nullBudget_throwsNpe() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        assertThrows(NullPointerException.class, () -> reg.acquireExclusiveTimed(key, null));
    }

    @Test
    void acquireExclusiveTimed_nonPositiveBudget_throwsIae() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        assertThrows(IllegalArgumentException.class,
                () -> reg.acquireExclusiveTimed(key, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> reg.acquireExclusiveTimed(key, Duration.ofMillis(-1)));
    }

    @Test
    void releaseExclusive_nullStamp_throwsNpe() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        assertThrows(NullPointerException.class, () -> reg.releaseExclusive(null));
    }

    // ── R34a — exclusive blocks shared on same key ───────────────────────────

    @Test
    void exclusiveOnSameKey_blocksSharedAcquirers() throws Exception {
        // R34a — rotation acquires exclusive; concurrent DEK creation (shared) blocks until release
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ExclusiveStamp xStamp = reg.acquireExclusiveTimed(key, Duration.ofSeconds(5));

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final CountDownLatch sharedAcquired = new CountDownLatch(1);
        final long[] sharedStamp = new long[1];
        try {
            pool.submit(() -> {
                sharedStamp[0] = reg.acquireShared(key);
                sharedAcquired.countDown();
            });

            // Shared acquirer must NOT progress while exclusive is held
            assertFalse(sharedAcquired.await(200, TimeUnit.MILLISECONDS),
                    "shared acquirer must block while exclusive lock is held on same key");

            // Release exclusive — shared must acquire promptly
            reg.releaseExclusive(xStamp);
            assertTrue(sharedAcquired.await(5, TimeUnit.SECONDS),
                    "shared acquirer must proceed after exclusive release");
            reg.releaseShared(key, sharedStamp[0]);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── R34a / R32b-1 — per-tenant and per-(tenant,domain) isolation ────────

    @Test
    void exclusiveOnTenantA_doesNotBlockSharedOnTenantB() throws Exception {
        // R34a — rotations in different tenants' shards must not lock each other
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey keyA = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ShardKey keyB = ShardKey.tier2(TENANT_B, DOMAIN_1);
        final ExclusiveStamp xStamp = reg.acquireExclusiveTimed(keyA, Duration.ofSeconds(5));
        try {
            final long sharedB = reg.acquireShared(keyB);
            // If isolation works, we acquired without blocking; release immediately
            reg.releaseShared(keyB, sharedB);
        } finally {
            reg.releaseExclusive(xStamp);
        }
    }

    @Test
    void exclusiveOnDomain1_doesNotBlockSharedOnDomain2_sameTenant() throws Exception {
        // R32b-1 — domain KEK rotation locks scoped to (tenantId, domainId);
        // DEK creation in other domains under the same tenant must not be blocked
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey keyD1 = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ShardKey keyD2 = ShardKey.tier2(TENANT_A, DOMAIN_2);
        final ExclusiveStamp xStamp = reg.acquireExclusiveTimed(keyD1, Duration.ofSeconds(5));
        try {
            final long sharedD2 = reg.acquireShared(keyD2);
            reg.releaseShared(keyD2, sharedD2);
        } finally {
            reg.releaseExclusive(xStamp);
        }
    }

    @Test
    void tier1AndTier2_keysAreIndependentLocks() throws Exception {
        // Tier-1 (tenantId, shardId) and tier-2 (tenantId, domainId) should map to distinct locks
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey tier1 = ShardKey.tier1(TENANT_A, "shard-1");
        final ShardKey tier2 = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ExclusiveStamp x1 = reg.acquireExclusiveTimed(tier1, Duration.ofSeconds(5));
        try {
            // tier-2 exclusive should not block; same tenant but a different key class
            final ExclusiveStamp x2 = reg.acquireExclusiveTimed(tier2, Duration.ofSeconds(5));
            reg.releaseExclusive(x2);
        } finally {
            reg.releaseExclusive(x1);
        }
    }

    // ── Reusability after release ────────────────────────────────────────────

    @Test
    void releasingExclusive_allowsSubsequentSharedAndExclusiveAcquires() {
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final ShardKey key = ShardKey.tier2(TENANT_A, DOMAIN_1);
        final ExclusiveStamp x1 = reg.acquireExclusiveTimed(key, BUDGET);
        reg.releaseExclusive(x1);

        final long s = reg.acquireShared(key);
        reg.releaseShared(key, s);

        final ExclusiveStamp x2 = reg.acquireExclusiveTimed(key, BUDGET);
        reg.releaseExclusive(x2);
    }

    // ── Concurrency under load ───────────────────────────────────────────────

    @Test
    void concurrentSharedAcquirers_makeProgressOnDifferentKeys() throws Exception {
        // Per-tenant isolation under load: concurrent shared acquires on distinct keys
        // must all complete; no global serialization.
        final ShardLockRegistry reg = ShardLockRegistry.create();
        final int threads = 16;
        final AtomicInteger completed = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    final ShardKey key = ShardKey.tier2(new TenantId("tenant-" + idx), DOMAIN_1);
                    for (int n = 0; n < 100; n++) {
                        final long stamp = reg.acquireShared(key);
                        reg.releaseShared(key, stamp);
                    }
                    completed.incrementAndGet();
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                    "concurrent shared acquires should complete");
            assertEquals(threads, completed.get());
        } finally {
            pool.shutdownNow();
        }
    }
}

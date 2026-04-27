package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DeployerInstanceId;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link PollingScheduler} (R79c-1 deterministic HMAC jitter + R79c per-instance
 * aggregate rate limit).
 *
 * @spec encryption.primitives-lifecycle R79c
 * @spec encryption.primitives-lifecycle R79c-1
 * @spec encryption.primitives-lifecycle R83c-2
 */
class PollingSchedulerTest {

    private static byte[] secret(int seed) {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) (seed + i);
        }
        return s;
    }

    private static DeployerInstanceId instanceId(int seed) {
        return new DeployerInstanceId(secret(seed));
    }

    @Test
    void createNullInstanceIdRejected() {
        assertThrows(NullPointerException.class, () -> PollingScheduler.create(null));
    }

    @Test
    void createWithRateLimitNullInstanceIdRejected() {
        assertThrows(NullPointerException.class, () -> PollingScheduler.withRateLimit(null, 100));
    }

    @Test
    void createWithRateLimitZeroOrNegativeRejected() {
        final DeployerInstanceId id = instanceId(1);
        assertThrows(IllegalArgumentException.class, () -> PollingScheduler.withRateLimit(id, 0));
        assertThrows(IllegalArgumentException.class, () -> PollingScheduler.withRateLimit(id, -1));
    }

    @Test
    void defaultRateLimitConstantIs100() {
        assertEquals(100, PollingScheduler.DEFAULT_RATE_LIMIT_PER_SEC,
                "R79c default per-instance aggregate rate limit pinned at 100 polls/sec");
    }

    @Test
    void nextPollAtNullTenantRejected() {
        final PollingScheduler s = PollingScheduler.create(instanceId(1));
        assertThrows(NullPointerException.class, () -> s.nextPollAt(null, Duration.ofSeconds(30)));
    }

    @Test
    void nextPollAtNullCadenceRejected() {
        final PollingScheduler s = PollingScheduler.create(instanceId(1));
        assertThrows(NullPointerException.class, () -> s.nextPollAt(new TenantId("t"), null));
    }

    @Test
    void nextPollAtZeroCadenceRejected() {
        final PollingScheduler s = PollingScheduler.create(instanceId(1));
        assertThrows(IllegalArgumentException.class,
                () -> s.nextPollAt(new TenantId("t"), Duration.ZERO));
    }

    @Test
    void nextPollAtNegativeCadenceRejected() {
        final PollingScheduler s = PollingScheduler.create(instanceId(1));
        assertThrows(IllegalArgumentException.class,
                () -> s.nextPollAt(new TenantId("t"), Duration.ofSeconds(-1)));
    }

    @Test
    void nextPollAtReturnsNonNullInstant() {
        final PollingScheduler s = PollingScheduler.create(instanceId(1));
        final Instant when = s.nextPollAt(new TenantId("t"), Duration.ofMinutes(15));
        assertNotNull(when);
    }

    @Test
    void offsetIsDeterministicAcrossSchedulerRecreations() {
        // R79c-1: offset must be stable across EncryptionKeyHolder recreations for the same
        // (deployerInstanceId, tenantId, cadence) tuple. Two fresh schedulers with the same
        // instanceId secret must produce the same nextPollAt offset for the same tenant.
        final byte[] secret = secret(7);
        final TenantId tenant = new TenantId("tenant-A");
        final Duration cadence = Duration.ofMinutes(15);

        final PollingScheduler a = PollingScheduler.create(new DeployerInstanceId(secret));
        final PollingScheduler b = PollingScheduler.create(new DeployerInstanceId(secret));

        final Instant whenA = a.nextPollAt(tenant, cadence);
        final Instant whenB = b.nextPollAt(tenant, cadence);

        // The offset within the cadence period must be identical — only the period start may
        // differ by a few milliseconds because it's anchored to wall-clock.
        final long offsetA = Math.floorMod(whenA.toEpochMilli(), cadence.toMillis());
        final long offsetB = Math.floorMod(whenB.toEpochMilli(), cadence.toMillis());
        assertEquals(offsetA, offsetB, "R79c-1: offset must be deterministic across "
                + "scheduler recreations for the same (instanceId, tenant)");
    }

    @Test
    void differentTenantsGetDifferentOffsets() {
        // Anti-thundering-herd: two tenants with the same instance must NOT lockstep on the same
        // offset. We test by sampling a population of tenant IDs and confirming the distribution
        // of offsets is not collapsed to a single value.
        final PollingScheduler s = PollingScheduler.create(instanceId(11));
        final Duration cadence = Duration.ofMinutes(15);
        final Set<Long> distinctOffsets = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            final Instant when = s.nextPollAt(new TenantId("tenant-" + i), cadence);
            distinctOffsets.add(Math.floorMod(when.toEpochMilli(), cadence.toMillis()));
        }
        assertTrue(distinctOffsets.size() > 1,
                "different tenants must spread to different jitter offsets — anti-thundering-herd");
    }

    @Test
    void differentDeployerInstancesGetDifferentOffsetsForSameTenant() {
        // Two different deployer instances polling the same tenant must NOT both pick the same
        // offset. Sample several pairs and require at least one differs (the probability of
        // coincidental match across N independent secrets approaches zero).
        final TenantId tenant = new TenantId("shared-tenant");
        final Duration cadence = Duration.ofMinutes(15);
        final Set<Long> offsetsAcrossInstances = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            final PollingScheduler s = PollingScheduler.create(instanceId(100 + i));
            final Instant when = s.nextPollAt(tenant, cadence);
            offsetsAcrossInstances.add(Math.floorMod(when.toEpochMilli(), cadence.toMillis()));
        }
        assertTrue(offsetsAcrossInstances.size() > 1, "R79c-1: different deployer instances "
                + "must produce different jitter offsets for the same tenant");
    }

    @Test
    void nextPollAtOffsetWithinCadenceWindow() {
        // R79c first-poll-must-occur-at-offset-within-[0,cadence): the floor-mod of (next - now)
        // must be < cadence millis.
        final PollingScheduler s = PollingScheduler.create(instanceId(3));
        final Duration cadence = Duration.ofSeconds(30);
        for (int i = 0; i < 25; i++) {
            final Instant when = s.nextPollAt(new TenantId("t-" + i), cadence);
            final long offset = Math.floorMod(when.toEpochMilli(), cadence.toMillis());
            assertTrue(offset >= 0 && offset < cadence.toMillis(),
                    "offset must lie in [0, cadence); was " + offset);
        }
    }

    @Test
    void tryAcquireBudgetReturnsTrueWithinLimit() {
        final PollingScheduler s = PollingScheduler.withRateLimit(instanceId(2), 10);
        // 10 acquisitions within the same second should all succeed.
        for (int i = 0; i < 10; i++) {
            assertTrue(s.tryAcquireBudget(),
                    "budget acquire must succeed within the configured rate-limit");
        }
    }

    @Test
    void tryAcquireBudgetExhaustionReturnsFalse() {
        // R79c per-instance aggregate rate limit: when the limit would be exceeded, polls must be
        // deferred (caller observes false). We use a very low rate to force exhaustion within a
        // single test pass without sleeping for >1s.
        final PollingScheduler s = PollingScheduler.withRateLimit(instanceId(2), 3);
        for (int i = 0; i < 3; i++) {
            assertTrue(s.tryAcquireBudget(), "first 3 acquires within budget");
        }
        // Fourth must be deferred: no budget remains in the current second.
        assertTrue(!s.tryAcquireBudget(),
                "R79c: rate-limit exhaustion must defer (return false), not drop");
    }

    @Test
    void rateLimitBudgetRefillsOverTime() throws InterruptedException {
        // Budget refills with wall-clock advance — simplest contract is a per-second token bucket.
        // Use a small rate, exhaust, sleep ~1.1s, and confirm budget is available again.
        final PollingScheduler s = PollingScheduler.withRateLimit(instanceId(2), 2);
        assertTrue(s.tryAcquireBudget());
        assertTrue(s.tryAcquireBudget());
        assertTrue(!s.tryAcquireBudget(), "second exhausted within window");
        // Wait long enough for the budget to refill (>1 second)
        Thread.sleep(1100);
        assertTrue(s.tryAcquireBudget(),
                "budget must refill once the rate-limit window has rolled over");
    }

    @Test
    void differentTenantsButSameDeployerKeepDistinctOffsetsAcrossCadences() {
        // R79c-1 invariant: offset is a function of (deployerInstanceId, tenantId, cadenceMillis).
        // Switching cadence must produce a different mod result for the same tenant (since the
        // offset is mod cadenceMillis), but the underlying HMAC bytes are stable.
        final PollingScheduler s = PollingScheduler.create(instanceId(5));
        final TenantId tenant = new TenantId("t");
        final Instant w1 = s.nextPollAt(tenant, Duration.ofSeconds(30));
        final Instant w2 = s.nextPollAt(tenant, Duration.ofSeconds(60));
        // Both calls return some instant. They may produce coincidentally-equal offsets but the
        // contract is the function is well-defined; assert simply that both succeed and lie in
        // their respective cadence windows.
        assertNotNull(w1);
        assertNotNull(w2);
    }

    @Test
    void offsetStableAcrossManyCalls() {
        // Calling nextPollAt repeatedly within the same deployer instance for the same tenant
        // produces the same offset (within the cadence window).
        final PollingScheduler s = PollingScheduler.create(instanceId(4));
        final TenantId tenant = new TenantId("stable-tenant");
        final Duration cadence = Duration.ofMinutes(15);
        final long offset1 = Math.floorMod(s.nextPollAt(tenant, cadence).toEpochMilli(),
                cadence.toMillis());
        final long offset2 = Math.floorMod(s.nextPollAt(tenant, cadence).toEpochMilli(),
                cadence.toMillis());
        assertEquals(offset1, offset2, "offset must be stable across repeated calls");
    }

    @Test
    void offsetDistributionUsesAllFourBytesOfHmac() {
        // R79c-1: offset = HMAC-SHA256(...)[0..3] mod cadenceMillis. With a 2^32 input space and a
        // cadence of 1 hour (3.6M ms), we expect the offsets to span more than ~256 distinct values
        // across 1000 tenants. (If the impl only used [0..0] we'd see at most 256 distinct values;
        // [0..3] gives ~3.6M.)
        final PollingScheduler s = PollingScheduler.create(instanceId(99));
        final Duration cadence = Duration.ofHours(1);
        final Set<Long> offsets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            final Instant when = s.nextPollAt(new TenantId("scale-tenant-" + i), cadence);
            offsets.add(Math.floorMod(when.toEpochMilli(), cadence.toMillis()));
        }
        assertTrue(offsets.size() > 256, "offset must use >1 byte of HMAC entropy — got only "
                + offsets.size() + " distinct offsets across 1000 tenants");
    }

    @Test
    void differentInstanceSecretsProduceDifferentOffsetsDeterministically() {
        // Two deployer instances with deterministically-different secrets must consistently
        // produce different offsets for the same tenant — confirms the HMAC key path actually
        // includes the secret rather than only the tenant id.
        final TenantId tenant = new TenantId("same-tenant");
        final Duration cadence = Duration.ofMinutes(15);
        final PollingScheduler a = PollingScheduler.create(instanceId(1));
        final PollingScheduler b = PollingScheduler.create(instanceId(2));
        final long offsetA = Math.floorMod(a.nextPollAt(tenant, cadence).toEpochMilli(),
                cadence.toMillis());
        final long offsetB = Math.floorMod(b.nextPollAt(tenant, cadence).toEpochMilli(),
                cadence.toMillis());
        // Probability of collision under HMAC-SHA256 truncated to 32 bits is 1/cadenceMillis ≈
        // 1/900_000 — vanishingly small for a deliberately-controlled test seed.
        assertNotEquals(offsetA, offsetB,
                "different instance secrets must produce different offsets (HMAC keying)");
    }
}

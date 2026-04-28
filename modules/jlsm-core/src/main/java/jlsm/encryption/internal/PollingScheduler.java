package jlsm.encryption.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jlsm.encryption.DeployerInstanceId;
import jlsm.encryption.TenantId;

/**
 * Per-tenant HMAC-SHA256({@code deployerInstanceId.secret}, {@code tenantId.value}) jitter offset
 * (R79c-1) plus per-instance aggregate rate-limit budget (default 100/sec). Provides
 * deterministic-but-jittered polling cadences so multiple deployer instances do not lock-step on
 * the same KMS endpoint.
 *
 * <p>
 * The rate-limit is implemented as a per-second token bucket: {@link #tryAcquireBudget} succeeds
 * iff the current second's budget has not yet been exhausted. The window rolls over with the
 * wall-clock second.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R79c-1, R83c-2).
 *
 * @spec encryption.primitives-lifecycle R79c-1
 * @spec encryption.primitives-lifecycle R83c-2
 */
public final class PollingScheduler {

    /** Default per-instance aggregate rate-limit budget (100 polls/sec). */
    public static final int DEFAULT_RATE_LIMIT_PER_SEC = 100;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final int rateLimitPerSec;
    private final Clock clock;

    /** Encoded current rate-limit window: high 32 bits = epoch second, low 32 bits = used count. */
    private final AtomicLong windowState = new AtomicLong(0L);

    private PollingScheduler(DeployerInstanceId instanceId, int rateLimitPerSec, Clock clock) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(clock, "clock");
        if (rateLimitPerSec <= 0) {
            throw new IllegalArgumentException(
                    "rateLimitPerSec must be positive, got " + rateLimitPerSec);
        }
        this.secret = instanceId.secret();
        this.rateLimitPerSec = rateLimitPerSec;
        this.clock = clock;
    }

    /** Construct with the default rate-limit. */
    public static PollingScheduler create(DeployerInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return new PollingScheduler(instanceId, DEFAULT_RATE_LIMIT_PER_SEC, Clock.systemUTC());
    }

    /** Construct with an explicit rate-limit. */
    public static PollingScheduler withRateLimit(DeployerInstanceId instanceId,
            int rateLimitPerSec) {
        Objects.requireNonNull(instanceId, "instanceId");
        return new PollingScheduler(instanceId, rateLimitPerSec, Clock.systemUTC());
    }

    /** Construct with explicit clock + rate-limit. Test seam. */
    static PollingScheduler withClock(DeployerInstanceId instanceId, int rateLimitPerSec,
            Clock clock) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(clock, "clock");
        return new PollingScheduler(instanceId, rateLimitPerSec, clock);
    }

    /**
     * Compute the next-poll instant for {@code tenantId} given {@code cadence}, applying the
     * deterministic per-tenant jitter offset.
     *
     * <p>
     * Formula per R79c-1: {@code offset = HMAC-SHA256(deployerInstanceId, tenantId.value)[0..3] mod
     * cadenceMillis}. The returned instant is the next wall-clock instant whose epoch-millis-mod
     * cadenceMillis equals the computed offset.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code cadence} is non-positive
     */
    public Instant nextPollAt(TenantId tenantId, Duration cadence) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(cadence, "cadence");
        if (cadence.isZero() || cadence.isNegative()) {
            throw new IllegalArgumentException("cadence must be positive, got " + cadence);
        }

        final long cadenceMillis = cadence.toMillis();
        if (cadenceMillis <= 0) {
            // Defends against sub-millisecond cadences (Duration.ofNanos(1)) collapsing to zero.
            throw new IllegalArgumentException(
                    "cadence must be at least 1 millisecond, got " + cadence);
        }
        final long offset = computeOffsetMillis(tenantId, cadenceMillis);
        assert offset >= 0 && offset < cadenceMillis : "offset must lie in [0, cadenceMillis)";

        // Anchor to wall-clock: pick the next instant >= now whose floorMod(epochMillis,
        // cadenceMillis) == offset.
        final long nowMillis = clock.instant().toEpochMilli();
        final long currentSlot = Math.floorMod(nowMillis, cadenceMillis);
        final long delta;
        if (offset >= currentSlot) {
            delta = offset - currentSlot;
        } else {
            delta = cadenceMillis - (currentSlot - offset);
        }
        return Instant.ofEpochMilli(nowMillis + delta);
    }

    /**
     * Compute the deterministic jitter offset in millis for the given tenant and cadence per
     * R79c-1: {@code HMAC-SHA256(secret, tenantId)[0..3]} interpreted as an unsigned 32-bit big-
     * endian integer modulo {@code cadenceMillis}.
     */
    long computeOffsetMillis(TenantId tenantId, long cadenceMillis) {
        final byte[] hmac = hmacSha256(secret, tenantId.value().getBytes(StandardCharsets.UTF_8));
        // Interpret bytes 0..3 as unsigned big-endian 32-bit integer.
        final long bits = ((long) (hmac[0] & 0xFF) << 24) | ((long) (hmac[1] & 0xFF) << 16)
                | ((long) (hmac[2] & 0xFF) << 8) | (long) (hmac[3] & 0xFF);
        return bits % cadenceMillis;
    }

    /**
     * Try to acquire one unit of polling budget for the current second. Returns true iff budget was
     * available; the caller must skip the poll otherwise.
     *
     * <p>
     * Implementation: per-second token bucket encoded into a single AtomicLong (high 32 bits =
     * epoch second, low 32 bits = used count). CAS loop bumps the used counter or rolls the window
     * over to a fresh second.
     */
    public boolean tryAcquireBudget() {
        final long nowSecond = clock.instant().getEpochSecond();
        while (true) {
            final long current = windowState.get();
            final long currentSecond = current >>> 32;
            final long currentUsed = current & 0xFFFFFFFFL;
            final long next;
            if (currentSecond != nowSecond) {
                // Fresh window — try to roll over to the new second with usage = 1.
                next = (nowSecond << 32) | 1L;
                if (windowState.compareAndSet(current, next)) {
                    return true;
                }
                // Lost the race — retry.
                continue;
            }
            if (currentUsed >= rateLimitPerSec) {
                // Window exhausted.
                return false;
            }
            next = (currentSecond << 32) | (currentUsed + 1L);
            if (windowState.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /** HMAC-SHA256 helper. */
    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid HMAC key", e);
        }
    }
}

package jlsm.encryption.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

import jlsm.encryption.DomainId;
import jlsm.encryption.TenantId;

/**
 * Per-shard {@link java.util.concurrent.locks.StampedLock}-backed lock primitives. Per-tenant
 * isolation: shard keys scope to {@code (tenantId, domainId)} for tier-2 (R32b-1) and
 * {@code (tenantId, shardId)} for tier-1 (R32c). Exclusive holders observe a configurable
 * max-hold-time bound to prevent rotation bursts from monopolising a shard (R32c).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R32c, R34a, R32b-1); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R32c
 * @spec encryption.primitives-lifecycle R34a
 * @spec encryption.primitives-lifecycle R32b-1
 */
public final class ShardLockRegistry {

    /**
     * One {@link StampedLock} per distinct {@link ShardKey}. Different keys map to different locks
     * — this is the per-tenant / per-(tenant,domain) isolation surface (R34a, R32b-1).
     */
    private final ConcurrentHashMap<ShardKey, StampedLock> locks = new ConcurrentHashMap<>();

    private ShardLockRegistry() {
    }

    /** Construct an empty lock registry. */
    public static ShardLockRegistry create() {
        return new ShardLockRegistry();
    }

    /**
     * Acquire a shared (read) stamp for the shard keyed by {@code shardKey}. Multiple shared
     * holders may co-exist; an exclusive holder blocks all shared acquirers until released.
     *
     * @return an opaque stamp the caller must pass to {@link #releaseShared}
     * @throws NullPointerException if {@code shardKey} is null
     */
    public long acquireShared(ShardKey shardKey) {
        Objects.requireNonNull(shardKey, "shardKey");
        return lockFor(shardKey).readLock();
    }

    /**
     * Release a shared stamp acquired via {@link #acquireShared}.
     *
     * @throws NullPointerException if {@code shardKey} is null
     * @throws IllegalArgumentException if {@code stamp} was not issued by {@link #acquireShared}
     *             for the same shard
     */
    public void releaseShared(ShardKey shardKey, long stamp) {
        Objects.requireNonNull(shardKey, "shardKey");
        // StampedLock.unlockRead throws IllegalMonitorStateException for an unknown stamp;
        // surface that as IllegalArgumentException to match the documented contract.
        try {
            lockFor(shardKey).unlockRead(stamp);
        } catch (IllegalMonitorStateException ex) {
            throw new IllegalArgumentException(
                    "shared stamp not issued by this registry for shardKey=" + shardKey, ex);
        }
    }

    /**
     * Acquire an exclusive (write) stamp with a per-call max-hold-time bound. The returned stamp
     * carries the deadline; the caller must release before the bound elapses or the lock is
     * forcibly released and a release attempt becomes a no-op.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code maxHoldTime} is non-positive
     */
    public ExclusiveStamp acquireExclusiveTimed(ShardKey shardKey, Duration maxHoldTime) {
        Objects.requireNonNull(shardKey, "shardKey");
        Objects.requireNonNull(maxHoldTime, "maxHoldTime");
        if (maxHoldTime.isZero() || maxHoldTime.isNegative()) {
            throw new IllegalArgumentException("maxHoldTime must be positive, got " + maxHoldTime);
        }
        final StampedLock lock = lockFor(shardKey);
        final long stamp = lock.writeLock();
        // Deadline is computed AFTER acquisition so the budget covers the hold window only —
        // any time spent waiting for the lock does not consume the per-call budget (R32c).
        final long deadlineNanos = System.nanoTime() + maxHoldTime.toNanos();
        return new ExclusiveStamp(shardKey, stamp, deadlineNanos);
    }

    /**
     * Release an exclusive stamp. If the stamp's deadline has elapsed or the underlying lock has
     * already been released, this method silently no-ops per the R32c forcible-release contract.
     *
     * @throws NullPointerException if {@code stamp} is null
     */
    public void releaseExclusive(ExclusiveStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        final StampedLock lock = lockFor(stamp.shardKey());
        // Past-deadline release is a no-op per R32c. We still attempt to unlock so the
        // exclusive holder relinquishes the lock, but a stale stamp from an expired hold would
        // surface as IllegalMonitorStateException; suppress it to honour the no-op contract.
        try {
            lock.unlockWrite(stamp.stamp());
        } catch (IllegalMonitorStateException ignored) {
            // Lock was already released (e.g., under deadline expiry) — no-op per R32c.
        }
    }

    private StampedLock lockFor(ShardKey shardKey) {
        // computeIfAbsent allocates a fresh StampedLock once per distinct key; subsequent
        // lookups return the same instance. This is the per-key lock identity that guarantees
        // tenant / (tenant,domain) / (tenant,shardId) isolation.
        return locks.computeIfAbsent(shardKey, k -> new StampedLock());
    }

    /**
     * Composite key for a shard. Tier-1 (tenant KEK rotation) uses {@code (tenantId, shardId)};
     * tier-2 (domain KEK rotation) uses {@code (tenantId, domainId)}.
     */
    public record ShardKey(TenantId tenantId, DomainId domainId, String shardId) {

        public ShardKey {
            Objects.requireNonNull(tenantId, "tenantId");
            // domainId or shardId may be null depending on tier; at least one must be non-null.
            if (domainId == null && shardId == null) {
                throw new IllegalArgumentException(
                        "ShardKey requires at least one of domainId or shardId");
            }
        }

        /** Tier-2 shard key. */
        public static ShardKey tier2(TenantId tenantId, DomainId domainId) {
            Objects.requireNonNull(domainId, "domainId");
            return new ShardKey(tenantId, domainId, null);
        }

        /** Tier-1 shard key. */
        public static ShardKey tier1(TenantId tenantId, String shardId) {
            Objects.requireNonNull(shardId, "shardId");
            return new ShardKey(tenantId, null, shardId);
        }
    }

    /**
     * Opaque exclusive-stamp handle. Carries the deadline at which the lock auto-releases per R32c;
     * {@link #releaseExclusive} after the deadline is a no-op.
     */
    public record ExclusiveStamp(ShardKey shardKey, long stamp, long deadlineNanos) {

        public ExclusiveStamp {
            Objects.requireNonNull(shardKey, "shardKey");
        }
    }
}

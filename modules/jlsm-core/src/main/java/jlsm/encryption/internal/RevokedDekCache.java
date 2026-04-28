package jlsm.encryption.internal;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jlsm.encryption.DekHandle;

/**
 * In-process cache of permanently-revoked {@link DekHandle}s (R83g). Membership-test only:
 * inserting a handle is monotonic; a revoked handle never becomes un-revoked. Per detection-epoch
 * dedup (R83a) hooks integrate with
 * {@code TenantStateMachine.recordPermanentFailure(detectionEpoch)} so a revocation observation
 * produces at most one state-machine counter increment per detection epoch.
 *
 * <p>
 * The cache is in-process (not durable across restart) per R83g — restarted processes start with an
 * empty cache and re-discover revocations on demand. Membership is tested via
 * {@link ConcurrentHashMap#putIfAbsent}: the call that inserts the handle is the unique winner of
 * the markRevoked race; subsequent concurrent calls return false. This is the per-handle dedup
 * primitive R83a builds on.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83a, R83g).
 *
 * @spec encryption.primitives-lifecycle R83g
 * @spec encryption.primitives-lifecycle R83a
 */
public final class RevokedDekCache {

    /**
     * Membership map: keys are revoked handles. The value is unused — we use the map for its
     * concurrent-set semantics via {@link ConcurrentHashMap#newKeySet()}.
     */
    private final Set<DekHandle> revoked = ConcurrentHashMap.newKeySet();

    private RevokedDekCache() {
    }

    /** Construct an empty revoked-DEK cache. */
    public static RevokedDekCache create() {
        return new RevokedDekCache();
    }

    /**
     * Mark {@code handle} as revoked. Returns true iff this call was the one that recorded the
     * revocation (i.e., the handle was not already present); subsequent concurrent
     * {@code markRevoked} calls return false. Used by the read path to dedup state-machine counter
     * increments per R83a.
     *
     * @throws NullPointerException if {@code handle} is null
     */
    public boolean markRevoked(DekHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return revoked.add(handle);
    }

    /** True iff {@code handle} has been marked revoked. */
    public boolean isRevoked(DekHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return revoked.contains(handle);
    }
}

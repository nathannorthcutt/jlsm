package jlsm.encryption.internal;

import java.util.Objects;
import java.util.Set;

import jlsm.encryption.DekHandle;

/**
 * Durable bounded set of permanently-revoked {@link DekHandle}s. Stored in the
 * {@link KeyRegistryShard}. Default cap of 10K per P4-28; overflow emits the
 * {@code permanentlySkippedSetOverflow} event and transitions the tenant to FAILED.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83g, R83a P4-28).
 *
 * @spec encryption.primitives-lifecycle R83g
 *
 * @param handles immutable set of revoked DEK handles (defensively copied)
 * @param capacity cap on set membership (default 10K)
 */
public record PermanentlyRevokedDeksSet(Set<DekHandle> handles, int capacity) {

    /** Default cap per P4-28. */
    public static final int DEFAULT_CAPACITY = 10_000;

    /** Empty container with the default cap. */
    public static PermanentlyRevokedDeksSet empty() {
        return new PermanentlyRevokedDeksSet(Set.of(), DEFAULT_CAPACITY);
    }

    public PermanentlyRevokedDeksSet {
        Objects.requireNonNull(handles, "handles");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        for (DekHandle h : handles) {
            Objects.requireNonNull(h, "handles must not contain null");
        }
        if (handles.size() > capacity) {
            throw new IllegalArgumentException(
                    "handles size " + handles.size() + " exceeds capacity " + capacity);
        }
        handles = Set.copyOf(handles);
    }

    /**
     * Add {@code handle} and return a new container. If the resulting set would exceed
     * {@link #capacity}, throws {@link IllegalStateException} — the caller must emit the overflow
     * event and FAIL the tenant per P4-28. Re-adding an existing member is idempotent and never
     * throws (the resulting set's size is unchanged).
     */
    public PermanentlyRevokedDeksSet add(DekHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handles.contains(handle)) {
            // Idempotent — the underlying immutable Set already contains this handle, so the
            // resulting set is identical. Returning {@code this} avoids the allocation cost
            // of constructing a new copy.
            return this;
        }
        if (handles.size() >= capacity) {
            throw new IllegalStateException("PermanentlyRevokedDeksSet capacity " + capacity
                    + " exceeded; caller must emit permanentlySkippedSetOverflow event and "
                    + "FAIL the tenant (R83g P4-28)");
        }
        final java.util.Set<DekHandle> next = new java.util.HashSet<>(handles);
        next.add(handle);
        return new PermanentlyRevokedDeksSet(next, capacity);
    }

    /** True iff the set contains {@code handle}. */
    public boolean contains(DekHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return handles.contains(handle);
    }

    /** Size of the set. */
    public long size() {
        return handles.size();
    }

    /** True iff the set has no members. Convenience for callers checking emptiness. */
    public boolean isEmpty() {
        return handles.isEmpty();
    }
}

package jlsm.encryption;

/**
 * Three-state failure machine for a tenant's encryption plane. Transitions are monotonic:
 * {@code HEALTHY → GRACE_READ_ONLY → FAILED} or {@code HEALTHY → FAILED} (R83a, R83c-1).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R76b, R76b-2, R83a, R83c, R83c-1).
 *
 * @spec encryption.primitives-lifecycle R76b
 * @spec encryption.primitives-lifecycle R83a
 * @spec encryption.primitives-lifecycle R83c
 */
public enum TenantState {

    /** Normal operation. Reads and writes proceed against the live KEK chain. */
    HEALTHY,

    /**
     * Read-only grace period: writes are refused, reads proceed against cached/un-revoked DEKs
     * only. Triggered by partial KEK loss; resolves to {@link #HEALTHY} on KEK restore or to
     * {@link #FAILED} on grace timeout.
     */
    GRACE_READ_ONLY,

    /** Permanently failed. All operations refuse with {@link KekRevokedException} or kin. */
    FAILED;
}

package jlsm.engine;

/**
 * Controls the level of diagnostic information captured when a table handle is allocated.
 *
 * <p>
 * Higher tracking levels incur more overhead but provide richer diagnostics in
 * {@link HandleEvictedException}.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 */
public enum AllocationTracking {

    /** No allocation tracking. Minimal overhead. */
    OFF,

    /** Records the caller-supplied source tag at allocation time. */
    CALLER_TAG,

    /** Captures a full stack trace at allocation time. Maximum diagnostic detail. */
    FULL_STACK
}

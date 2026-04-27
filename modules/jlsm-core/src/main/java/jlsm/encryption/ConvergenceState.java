package jlsm.encryption;

/**
 * Convergence state for a (scope, oldDekVersion) registration. Monotonic per R37b-1: REVOKED is
 * terminal; transitions follow {@code PENDING → CONVERGED → REVOKED} or
 * {@code PENDING → TIMED_OUT → REVOKED}.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R37b, R37b-1, R83d).
 *
 * @spec encryption.primitives-lifecycle R37b-1
 * @spec encryption.primitives-lifecycle R83d
 */
public enum ConvergenceState {

    /** No convergence event observed yet. */
    PENDING,

    /** A manifest commit has converged the rotation. */
    CONVERGED,

    /** Convergence did not occur within the configured bound. */
    TIMED_OUT,

    /**
     * Revocation suppresses convergence (R83d). Terminal — no further transitions are allowed once
     * REVOKED is reached.
     */
    REVOKED;
}

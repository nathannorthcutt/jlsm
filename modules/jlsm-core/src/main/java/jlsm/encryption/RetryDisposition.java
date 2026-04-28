package jlsm.encryption;

/**
 * Marker hierarchy used to convey retry disposition to callers without opening up structural
 * subclassing. Module-pinned to {@code jlsm.core} per R83-1 P4-30: callers cannot synthesise their
 * own subclasses to bypass the read-path revocation contract.
 *
 * <p>
 * Use:
 *
 * <pre>{@code
 * try {
 *     // ... read path ...
 * } catch (RetryDisposition.NonRetryable nr) {
 *     // do not retry — propagate to caller
 * } catch (Exception e) {
 *     // transient — retry
 * }
 * }</pre>
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83, R83-1, R83b, R83c).
 *
 * @spec encryption.primitives-lifecycle R83
 * @spec encryption.primitives-lifecycle R83-1
 */
public sealed interface RetryDisposition permits RetryDisposition.NonRetryable {

    /**
     * Marker for exceptions that must NOT be retried. Sealed: the canonical members are
     * {@link KekRevokedException}, {@link RegistryStateException}, {@link DekNotFoundException},
     * {@link TenantKekUnavailableException}.
     */
    sealed interface NonRetryable extends RetryDisposition permits KekRevokedException,
            RegistryStateException, DekNotFoundException, TenantKekUnavailableException {
    }
}

package jlsm.encryption;

/**
 * A KEK has been permanently revoked. Subclassed for tier-1 (tenant) and tier-2 (domain)
 * revocation; this base is sealed so static analysis can enforce that all revocation paths surface
 * a known subtype.
 *
 * <p>
 * Implements {@link RetryDisposition.NonRetryable} — callers must not retry.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83b, R83c).
 *
 * @spec encryption.primitives-lifecycle R83b
 * @spec encryption.primitives-lifecycle R83c
 */
public sealed class KekRevokedException extends KmsPermanentException implements
        RetryDisposition.NonRetryable permits TenantKekRevokedException, DomainKekRevokedException {

    private static final long serialVersionUID = 1L;

    public KekRevokedException(String message) {
        super(message);
    }

    public KekRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}

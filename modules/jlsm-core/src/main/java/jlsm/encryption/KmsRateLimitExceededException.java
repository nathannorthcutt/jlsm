package jlsm.encryption;

/**
 * Subclass of {@link KmsTransientException} surfaced when the KMS rejects a request because the
 * caller is over its rate limit. Kept as a distinct type so observability tooling can count and
 * surface throttling separately from generic transient failures.
 *
 * <p>
 * Governed by: {@code .decisions/kms-integration-model/adr.md}.
 */
public final class KmsRateLimitExceededException extends KmsTransientException {

    private static final long serialVersionUID = 1L;

    public KmsRateLimitExceededException(String message) {
        super(message);
    }

    public KmsRateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}

package jlsm.encryption;

/**
 * A KMS failure that may succeed on retry. Examples: request throttling, socket
 * timeout, 5xx responses from a hosted KMS provider.
 *
 * <p>Non-sealed so observability-focused subclasses (e.g.,
 * {@link KmsRateLimitExceededException}) can further specialise.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R76a.
 */
public non-sealed class KmsTransientException extends KmsException {

    private static final long serialVersionUID = 1L;

    public KmsTransientException(String message) {
        super(message);
    }

    public KmsTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}

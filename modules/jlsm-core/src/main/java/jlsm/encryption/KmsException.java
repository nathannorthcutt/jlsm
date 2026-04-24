package jlsm.encryption;

/**
 * Sealed base class classifying a failure returned from the {@link KmsClient} SPI. The sealed
 * hierarchy partitions errors into {@link KmsTransientException} (retry is permitted) and
 * {@link KmsPermanentException} (retry is futile). Callers that merely want to propagate the
 * failure can catch {@code KmsException}; callers that want to choose a retry policy should
 * pattern-match the sealed subtypes.
 *
 * <p>
 * Modelled as an abstract sealed {@code Exception} rather than an interface because Java's
 * checked-exception plumbing requires a {@link Throwable} subclass; the sealed modifier delivers
 * the "closed set of subtypes" property an interface would have provided.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R76a (permanent vs transient
 * classification).
 */
public abstract sealed class KmsException extends Exception
        permits KmsTransientException, KmsPermanentException {

    private static final long serialVersionUID = 1L;

    protected KmsException(String message) {
        super(message);
    }

    protected KmsException(String message, Throwable cause) {
        super(message, cause);
    }
}

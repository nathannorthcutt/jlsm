package jlsm.encryption;

import java.io.IOException;

/**
 * Sealed base class classifying a failure returned from the {@link KmsClient} SPI. The sealed
 * hierarchy partitions errors into {@link KmsTransientException} (retry is permitted) and
 * {@link KmsPermanentException} (retry is futile). Callers that merely want to propagate the
 * failure can catch {@code KmsException}; callers that want to choose a retry policy should
 * pattern-match the sealed subtypes.
 *
 * <p>
 * Modelled as an abstract sealed subclass of {@link IOException} per R83 — the
 * {@code KmsPermanentException} subtree (and its {@link KekRevokedException} subtree) MUST extend
 * {@code IOException} so the read-path failure type matrix has a uniform checked-exception parent.
 * The sealed modifier delivers the "closed set of subtypes" property an interface would have
 * provided.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R76a (permanent vs transient
 * classification), R83 (IOException parent for the permanent subtree).
 *
 * @spec encryption.primitives-lifecycle R83
 */
public abstract sealed class KmsException extends IOException
        permits KmsTransientException, KmsPermanentException {

    private static final long serialVersionUID = 1L;

    protected KmsException(String message) {
        super(message);
    }

    protected KmsException(String message, Throwable cause) {
        super(message, cause);
    }
}

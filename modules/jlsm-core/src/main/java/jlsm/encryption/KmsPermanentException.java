package jlsm.encryption;

/**
 * A KMS failure that will not succeed on retry. Examples: access denied, key disabled, key not
 * found, malformed request.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R76a.
 */
public final class KmsPermanentException extends KmsException {

    private static final long serialVersionUID = 1L;

    public KmsPermanentException(String message) {
        super(message);
    }

    public KmsPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}

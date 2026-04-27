package jlsm.encryption;

/**
 * A KMS failure that will not succeed on retry. Examples: access denied, key disabled, key not
 * found, malformed request.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R76a.
 */
// WD-03: sealed to permit only the {@link KekRevokedException} subhierarchy. Direct extension
// is forbidden; new permanent-class faults must extend {@link KekRevokedException} (also sealed).
public sealed class KmsPermanentException extends KmsException permits KekRevokedException {

    private static final long serialVersionUID = 1L;

    public KmsPermanentException(String message) {
        super(message);
    }

    public KmsPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}

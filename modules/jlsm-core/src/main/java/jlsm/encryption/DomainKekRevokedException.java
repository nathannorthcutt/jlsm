package jlsm.encryption;

/**
 * A domain-tier KEK has been permanently revoked. R83b.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83b).
 *
 * @spec encryption.primitives-lifecycle R83b
 */
public final class DomainKekRevokedException extends KekRevokedException {

    private static final long serialVersionUID = 1L;

    public DomainKekRevokedException(String message) {
        super(message);
    }

    public DomainKekRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}

package jlsm.encryption;

/**
 * The tenant-tier KEK for a tenant has been permanently revoked. R83c.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83c).
 *
 * @spec encryption.primitives-lifecycle R83c
 */
public final class TenantKekRevokedException extends KekRevokedException {

    private static final long serialVersionUID = 1L;

    public TenantKekRevokedException(String message) {
        super(message);
    }

    public TenantKekRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}

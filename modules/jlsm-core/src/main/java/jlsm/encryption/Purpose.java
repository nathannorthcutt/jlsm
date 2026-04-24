package jlsm.encryption;

/**
 * Closed set of purposes a cryptographic operation may carry. The purpose binds into the
 * {@link EncryptionContext} AAD so that a ciphertext produced for one purpose cannot be
 * reinterpreted for another.
 *
 * <p>
 * Each constant carries an explicit {@link #code()} value that is embedded in the AAD as a 4-byte
 * big-endian integer. These codes are a persistent-format contract: they are pinned and must not
 * change. New constants added in the future must receive a fresh (unused, non-colliding) code;
 * existing codes must never be reassigned or reordered, because ciphertext wrapped under the old
 * code would become undecryptable.
 *
 * <p>
 * The {@code ordinal()} position of these constants is explicitly NOT part of the contract — only
 * {@link #code()} is stable. Reordering declarations here is safe; changing a {@link #code()} value
 * is not.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R80a (closed-set purposes), R80a
 * (context-bound AAD stability across library versions).
 */
public enum Purpose {

    /** Wrapping/unwrapping a tier-2 domain KEK under a tier-1 tenant KEK. */
    DOMAIN_KEK(1),

    /** Wrapping/unwrapping a tier-3 DEK under a tier-2 domain KEK. */
    DEK(2),

    /** Sentinel operation produced during a cascading-lazy-rewrap rotation. */
    REKEY_SENTINEL(3),

    /** Liveness probe against a KMS KEK; produces no durable ciphertext. */
    HEALTH_CHECK(4);

    private final int code;

    Purpose(int code) {
        this.code = code;
    }

    /**
     * The stable, persistent-format integer code for this purpose. Used as the 4-byte BE purpose
     * field in the AAD encoding. Unlike {@link #ordinal()}, this value is pinned and survives
     * reordering of the enum declarations.
     *
     * @return the stable code
     */
    public int code() {
        return code;
    }
}

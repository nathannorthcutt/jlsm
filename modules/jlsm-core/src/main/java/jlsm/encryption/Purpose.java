package jlsm.encryption;

/**
 * Closed set of purposes a cryptographic operation may carry. The purpose binds into the
 * {@link EncryptionContext} AAD so that a ciphertext produced for one purpose cannot be
 * reinterpreted for another.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R80a (closed-set purposes).
 */
public enum Purpose {

    /** Wrapping/unwrapping a tier-2 domain KEK under a tier-1 tenant KEK. */
    DOMAIN_KEK,

    /** Wrapping/unwrapping a tier-3 DEK under a tier-2 domain KEK. */
    DEK,

    /** Sentinel operation produced during a cascading-lazy-rewrap rotation. */
    REKEY_SENTINEL,

    /** Liveness probe against a KMS KEK; produces no durable ciphertext. */
    HEALTH_CHECK
}

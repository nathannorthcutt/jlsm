package jlsm.encryption;

/**
 * Classifies how a tenant's KEK is managed.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R71, R71a;
 * ADR {@code .decisions/kms-integration-model/adr.md}.
 */
public enum TenantFlavor {

    /** No encryption configured for this tenant. */
    NONE,

    /** Tenant KEK is managed by the built-in LocalKmsClient reference impl. */
    LOCAL,

    /** Tenant KEK is managed by an external KMS (AWS KMS, GCP KMS, Vault, ...). */
    EXTERNAL
}

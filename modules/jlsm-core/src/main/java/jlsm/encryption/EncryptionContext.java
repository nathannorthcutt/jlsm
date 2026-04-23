package jlsm.encryption;

import java.util.Map;
import java.util.Objects;

/**
 * Typed, closed-set context record describing an encryption/decryption operation. The
 * context is canonically encoded as Additional Authenticated Data (AAD) on every GCM
 * operation, so an attacker cannot move a ciphertext from one scope to another (e.g.,
 * swap a DEK wrapped for table A into a request to unwrap for table B).
 *
 * <p>Factory methods enforce the required attribute keys for each {@link Purpose}
 * (R80a-1).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R80, R80a, R80a-1.
 *
 * @param purpose the operation classification
 * @param attributes string-keyed string-valued attributes; defensively copied on
 *                   construction
 */
public record EncryptionContext(Purpose purpose, Map<String, String> attributes) {

    /**
     * @throws NullPointerException if either argument is null
     */
    public EncryptionContext {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);
    }

    /**
     * Build a context for wrapping/unwrapping a domain KEK under a tenant KEK.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forDomainKek(TenantId tenantId, DomainId domainId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        return new EncryptionContext(
                Purpose.DOMAIN_KEK,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value()));
    }

    /**
     * Build a context for wrapping/unwrapping a DEK under a domain KEK.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forDek(
            TenantId tenantId, DomainId domainId, TableId tableId, DekVersion dekVersion) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        Objects.requireNonNull(dekVersion, "dekVersion must not be null");
        return new EncryptionContext(
                Purpose.DEK,
                Map.of(
                        "tenantId", tenantId.value(),
                        "domainId", domainId.value(),
                        "tableId", tableId.value(),
                        "dekVersion", Integer.toString(dekVersion.value())));
    }

    /**
     * Build a context for the sentinel operation produced during cascading lazy
     * rewrap.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forRekeySentinel(TenantId tenantId, DomainId domainId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        return new EncryptionContext(
                Purpose.REKEY_SENTINEL,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value()));
    }

    /**
     * Build a context for a KMS liveness probe.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forHealthCheck(TenantId tenantId, DomainId domainId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        return new EncryptionContext(
                Purpose.HEALTH_CHECK,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value()));
    }
}

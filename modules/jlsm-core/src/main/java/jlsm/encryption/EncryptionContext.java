package jlsm.encryption;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, closed-set context record describing an encryption/decryption operation. The context is
 * canonically encoded as Additional Authenticated Data (AAD) on every GCM operation, so an attacker
 * cannot move a ciphertext from one scope to another (e.g., swap a DEK wrapped for table A into a
 * request to unwrap for table B).
 *
 * <p>
 * Factory methods enforce the required attribute keys for each {@link Purpose} (R80a-1). The
 * compact constructor ALSO enforces the same per-Purpose attribute-key invariant, so callers that
 * bypass the factories (reflective construction, record deserializers, explicit
 * {@code new EncryptionContext(...)} calls) cannot produce malformed contexts. The accepted
 * attribute-key sets are:
 * <ul>
 * <li>{@link Purpose#DOMAIN_KEK}: exactly {@code {tenantId, domainId}}</li>
 * <li>{@link Purpose#DEK}: exactly {@code {tenantId, domainId, tableId, dekVersion}}</li>
 * <li>{@link Purpose#REKEY_SENTINEL}: exactly {@code {tenantId, domainId}}</li>
 * <li>{@link Purpose#HEALTH_CHECK}: exactly {@code {tenantId, domainId}}</li>
 * </ul>
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R80, R80a, R80a-1.
 *
 * @param purpose the operation classification
 * @param attributes string-keyed string-valued attributes; defensively copied on construction
 */
public record EncryptionContext(Purpose purpose, Map<String, String> attributes) {

    private static final Set<String> DOMAIN_KEK_ATTRS = Set.of("tenantId", "domainId");
    private static final Set<String> DEK_ATTRS = Set.of("tenantId", "domainId", "tableId",
            "dekVersion");
    private static final Set<String> REKEY_SENTINEL_ATTRS = Set.of("tenantId", "domainId");
    private static final Set<String> HEALTH_CHECK_ATTRS = Set.of("tenantId", "domainId");

    /**
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code attributes}' key set does not exactly match the
     *             required-attribute set for {@code purpose} (R80a-1)
     */
    public EncryptionContext {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        final Set<String> required = switch (purpose) {
            case DOMAIN_KEK -> DOMAIN_KEK_ATTRS;
            case DEK -> DEK_ATTRS;
            case REKEY_SENTINEL -> REKEY_SENTINEL_ATTRS;
            case HEALTH_CHECK -> HEALTH_CHECK_ATTRS;
        };
        if (!attributes.keySet().equals(required)) {
            throw new IllegalArgumentException("attributes key set " + attributes.keySet()
                    + " does not match required " + "attribute-key set " + required
                    + " for Purpose." + purpose.name() + " (R80a-1)");
        }
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
        return new EncryptionContext(Purpose.DOMAIN_KEK,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value()));
    }

    /**
     * Build a context for wrapping/unwrapping a DEK under a domain KEK.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forDek(TenantId tenantId, DomainId domainId, TableId tableId,
            DekVersion dekVersion) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        Objects.requireNonNull(dekVersion, "dekVersion must not be null");
        return new EncryptionContext(Purpose.DEK,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value(), "tableId",
                        tableId.value(), "dekVersion", Integer.toString(dekVersion.value())));
    }

    /**
     * Build a context for the sentinel operation produced during cascading lazy rewrap.
     *
     * @throws NullPointerException if any argument is null
     */
    public static EncryptionContext forRekeySentinel(TenantId tenantId, DomainId domainId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        return new EncryptionContext(Purpose.REKEY_SENTINEL,
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
        return new EncryptionContext(Purpose.HEALTH_CHECK,
                Map.of("tenantId", tenantId.value(), "domainId", domainId.value()));
    }
}

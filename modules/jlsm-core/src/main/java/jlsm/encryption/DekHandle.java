package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque handle identifying a specific DEK version within a
 * {@code (tenant, domain, table)} scope. Handles do not expose raw DEK bytes —
 * derivation of field keys from a DEK is always routed through
 * {@code EncryptionKeyHolder.deriveFieldKey(...)} (R9).
 *
 * <p>Handles are cheap value objects: they can be freely stored, compared, and
 * serialized. They carry no state of their own — resolving a handle to usable key
 * material requires calling back into the facade that produced it.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R81, R81a.
 *
 * @param tenantId the owning tenant
 * @param domainId the owning data domain
 * @param tableId the owning table
 * @param version the DEK version within the scope
 */
public record DekHandle(TenantId tenantId, DomainId domainId, TableId tableId, DekVersion version) {

    /**
     * @throws NullPointerException if any component is null
     */
    public DekHandle {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        Objects.requireNonNull(version, "version must not be null");
    }
}

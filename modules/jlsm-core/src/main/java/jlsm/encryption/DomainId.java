package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque data-domain identifier. Tier-2 scope in the three-tier key hierarchy (tenant → domain →
 * DEK). Value-equal to its wrapped string, deliberately boxed to prevent accidental cross-type use
 * with {@link TenantId} or {@link TableId}.
 *
 * <p>
 * The synthetic {@code "_wal"} domain (per R75) names the Write-Ahead Log's encryption domain; all
 * other domains are caller-chosen. Domain identifiers are opaque to the encryption layer: the
 * facade does not inspect their format beyond the non-null / non-empty rule.
 *
 * <p>
 * Governed by: {@code .decisions/three-tier-key-hierarchy/adr.md}; spec
 * {@code encryption.primitives-lifecycle} R17, R75.
 *
 * @param value non-null, non-empty string identifier
 */
public record DomainId(String value) {

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public DomainId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("DomainId value must not be empty");
        }
    }
}

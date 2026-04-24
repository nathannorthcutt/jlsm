package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque table identifier. Tier-3 scope component (with {@link DekVersion}) for DEK identity in the
 * three-tier key hierarchy. Value-equal to its wrapped string, deliberately boxed to prevent
 * accidental cross-type use with {@link TenantId} or {@link DomainId}.
 *
 * <p>
 * Governed by: {@code .decisions/dek-scoping-granularity/adr.md}; spec
 * {@code encryption.primitives-lifecycle} R19.
 *
 * @param value non-null, non-empty string identifier
 */
public record TableId(String value) {

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public TableId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("TableId value must not be empty");
        }
    }
}

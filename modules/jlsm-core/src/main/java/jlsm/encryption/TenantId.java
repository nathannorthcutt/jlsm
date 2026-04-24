package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque tenant identifier. Value-equal to its wrapped string but deliberately boxed so
 * compile-time errors catch accidental cross-type use between {@link TenantId}, {@link DomainId},
 * and {@link TableId}.
 *
 * <p>
 * Governed by: {@code .decisions/three-tier-key-hierarchy/adr.md}; spec
 * {@code encryption.primitives-lifecycle} R17.
 *
 * @param value non-null, non-empty string identifier
 */
public record TenantId(String value) {

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public TenantId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("TenantId value must not be empty");
        }
    }
}

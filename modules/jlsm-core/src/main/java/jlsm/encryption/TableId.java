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

    /**
     * Returns a redacted string form that does NOT expose the raw {@code value}. Callers that need
     * the raw string must invoke {@link #value()} explicitly; this keeps accidental {@code
     * toString()}-based logging (including transitive paths via {@link DekHandle},
     * {@link WrappedDek}, exception formatters, debuggers, etc.) from leaking caller-embedded PII
     * or secret material that may have been placed inside the opaque identifier.
     *
     * <p>
     * Addresses audit finding {@code F-R1.contract_boundaries.5.3}.
     */
    @Override
    public String toString() {
        return "TableId[value=<redacted:" + value.length() + " chars>]";
    }
}

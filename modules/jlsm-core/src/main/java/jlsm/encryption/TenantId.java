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

    /**
     * Returns a redacted string form that does NOT expose the raw {@code value}. Callers that need
     * the raw string must invoke {@link #value()} explicitly; this keeps accidental {@code
     * toString()}-based logging (including transitive paths via {@link DekHandle},
     * {@link WrappedDek}, exception formatters, debuggers, etc.) from leaking caller-embedded PII
     * or secret material that may have been placed inside the opaque identifier.
     *
     * <p>
     * Addresses audit finding {@code F-R1.contract_boundaries.5.3} — the default record
     * {@code toString()} interpolates the raw {@code value}, which is a privacy concern in
     * multi-tenant deployments even though scope identifiers are not themselves secrets.
     */
    @Override
    public String toString() {
        return "TenantId[value=<redacted:" + value.length() + " chars>]";
    }
}

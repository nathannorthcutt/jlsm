package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque data-domain identifier. Tier-2 scope in the three-tier key hierarchy (tenant → domain →
 * DEK). Value-equal to its wrapped string, deliberately boxed to prevent accidental cross-type use
 * with {@link TenantId} or {@link TableId}.
 *
 * <p>
 * The synthetic {@code "_wal"} domain (per R75) names the Write-Ahead Log's encryption domain. It
 * is reserved: external callers must obtain it via {@link #forWal()} and MUST NOT pass
 * {@code "_wal"} to the public constructor. Any such user-supplied value is rejected with
 * {@link IllegalArgumentException} to prevent registry-scope collisions between user data and WAL
 * state (per R71/R75). All other domain values are caller-chosen. Domain identifiers are opaque to
 * the encryption layer: the facade does not inspect their format beyond the non-null / non-empty
 * rule.
 *
 * <p>
 * Governed by: {@code .decisions/three-tier-key-hierarchy/adr.md}; spec
 * {@code encryption.primitives-lifecycle} R17, R71, R75.
 *
 * @param value non-null, non-empty string identifier; must not equal the reserved {@code "_wal"}
 */
public record DomainId(String value) {

    /** Reserved domain name for the synthetic WAL encryption scope (per R75). */
    static final String WAL_RESERVED_VALUE = "_wal";

    /**
     * Cached singleton instance of the synthetic WAL domain. Constructed exactly once via the
     * package-private factory path that bypasses the {@link #WAL_RESERVED_VALUE} rejection, so
     * {@link #forWal()} returns a canonical, shared instance. Placing this initializer after the
     * canonical ctor means the ctor sees {@link #forWal()} on the stack during init and honors the
     * bypass.
     */
    private static final DomainId WAL_INSTANCE = forWalInternal();

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty, or equals the reserved
     *             {@code "_wal"} synthetic-domain name (R75) — WAL callers must use
     *             {@link #forWal()}
     */
    public DomainId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("DomainId value must not be empty");
        }
        if (WAL_RESERVED_VALUE.equals(value) && !calledFromInternalWalFactory()) {
            throw new IllegalArgumentException(
                    "DomainId value \"_wal\" is reserved for the synthetic WAL encryption domain "
                            + "(R75); use DomainId.forWal() to obtain it");
        }
    }

    /**
     * Returns the synthetic WAL data-domain identifier (R75). This is the ONLY sanctioned way to
     * obtain a {@code DomainId} whose {@code value()} equals {@code "_wal"}. Internal to the
     * encryption layer; callers outside the WAL-encryption path must not use this method.
     *
     * @return the reserved WAL domain (cached singleton)
     */
    public static DomainId forWal() {
        return WAL_INSTANCE;
    }

    /**
     * Construct the {@link #WAL_INSTANCE} singleton. Placed on the call stack so the canonical
     * ctor's {@link #calledFromInternalWalFactory()} guard returns true and the rejection is
     * bypassed for this single, privileged construction site.
     */
    private static DomainId forWalInternal() {
        return new DomainId(WAL_RESERVED_VALUE);
    }

    /**
     * @return {@code true} iff {@link #forWalInternal()} appears on the current call stack (i.e.,
     *         the enclosing {@link DomainId} construction was initiated by the internal singleton
     *         initializer). Uses {@link StackWalker} with reduced scope to minimize overhead.
     */
    private static boolean calledFromInternalWalFactory() {
        return StackWalker.getInstance().walk(
                frames -> frames.anyMatch(f -> f.getClassName().equals(DomainId.class.getName())
                        && f.getMethodName().equals("forWalInternal")));
    }

    /**
     * Returns a redacted string form that does NOT expose the raw caller-supplied {@code value}.
     * Callers that need the raw string must invoke {@link #value()} explicitly; this keeps
     * accidental {@code toString()}-based logging (including transitive paths via
     * {@link DekHandle}, {@link WrappedDek}, {@link WrappedDomainKek}, exception formatters,
     * debuggers, etc.) from leaking caller-embedded PII or secret material that may have been
     * placed inside the opaque identifier.
     *
     * <p>
     * The synthetic WAL domain ({@link #forWal()}) renders as {@code DomainId[_wal]} because its
     * value is a compile-time reserved constant (R75), not caller-supplied — exposing it aids WAL
     * debugging and cannot leak any caller secret.
     *
     * <p>
     * Addresses audit finding {@code F-R1.contract_boundaries.5.3}.
     */
    @Override
    public String toString() {
        if (WAL_RESERVED_VALUE.equals(value)) {
            return "DomainId[" + WAL_RESERVED_VALUE + "]";
        }
        return "DomainId[value=<redacted:" + value.length() + " chars>]";
    }
}

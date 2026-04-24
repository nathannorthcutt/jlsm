package jlsm.encryption;

import java.util.Objects;

/**
 * Thrown when a caller requests a specific {@link DekHandle} version that does not exist in the key
 * registry (R57). Because this signals a logical error in the caller's view of the registry (not an
 * I/O failure), it extends {@link IllegalStateException}.
 *
 * <p>
 * The exception message identifies the scope {@code (tenant, domain, table,
 * version)} but MUST NOT include key bytes.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R24, R57.
 */
public final class DekNotFoundException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public DekNotFoundException(String message) {
        super(message);
    }

    /**
     * Factory that produces a message identifying the missing handle without including any key
     * material.
     *
     * @throws NullPointerException if {@code handle} is null
     */
    public static DekNotFoundException forHandle(DekHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        return new DekNotFoundException("No DEK found for tenantId=" + handle.tenantId().value()
                + ", domainId=" + handle.domainId().value() + ", tableId="
                + handle.tableId().value() + ", version=" + handle.version().value());
    }
}

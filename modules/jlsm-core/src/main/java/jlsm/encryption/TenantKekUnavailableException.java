package jlsm.encryption;

import java.util.Objects;

/**
 * Checked failure signalling that a tenant's tier-1 KEK cannot be reached or used. The facade's
 * three-state failure machine (AVAILABLE / DEGRADED / UNAVAILABLE, R76) translates sustained KMS
 * failures into this exception so callers have a single type to handle for "this tenant's data is
 * unreadable right now."
 *
 * <p>
 * This class ships in WD-01 so downstream work definitions can reference it; the three-state
 * machine logic itself lives in WD-03.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R76.
 */
public final class TenantKekUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    private final TenantId tenantId;

    public TenantKekUnavailableException(TenantId tenantId, String message) {
        super(message);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public TenantKekUnavailableException(TenantId tenantId, String message, Throwable cause) {
        super(message, cause);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public TenantId tenantId() {
        return tenantId;
    }
}

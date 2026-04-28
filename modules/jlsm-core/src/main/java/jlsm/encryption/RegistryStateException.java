package jlsm.encryption;

import java.io.IOException;
import java.util.Objects;

/**
 * A registry-state error from the read path. Implements {@link RetryDisposition.NonRetryable} —
 * callers must not retry. Carries a stable error code from the {@code JLSM-ENC-83B2-*} /
 * {@code JLSM-ENC-83I2-*} families so support and observability tooling can pivot on the code
 * without parsing the message.
 *
 * <p>
 * Codes:
 * <ul>
 * <li>{@code JLSM-ENC-83B2-REGISTRY-DESTROYED}</li>
 * <li>{@code JLSM-ENC-83B2-MANIFEST-REBUILD}</li>
 * <li>{@code JLSM-ENC-83B2-SCAN-TIMEOUT}</li>
 * <li>{@code JLSM-ENC-83I2-SECRET-MISMATCH}</li>
 * <li>{@code JLSM-ENC-83B2-TOKEN-REPLAY}</li>
 * </ul>
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83b-2, R83i-2).
 *
 * @spec encryption.primitives-lifecycle R83b-2
 * @spec encryption.primitives-lifecycle R83i-2
 */
public non-sealed class RegistryStateException extends IOException
        implements RetryDisposition.NonRetryable {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public RegistryStateException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public RegistryStateException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** Stable error code; never null. */
    public final String errorCode() {
        return errorCode;
    }
}

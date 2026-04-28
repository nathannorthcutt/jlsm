package jlsm.encryption;

import java.util.Objects;

/**
 * Classify provider {@link Throwable}s into the {@link ErrorClass} buckets used by the tenant state
 * machine. Implements the R76a-1 mapping table: {@link KmsPermanentException} →
 * {@link ErrorClass#PERMANENT}, {@link KmsTransientException} → {@link ErrorClass#TRANSIENT},
 * everything else → {@link ErrorClass#UNCLASSIFIED} (subject to escalation per R76a-2).
 *
 * <p>
 * Public so plugin authors and adapter implementations can probe their own error-paths against the
 * same classifier the lifecycle layer uses.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R76a-1, R76a-2).
 *
 * @spec encryption.primitives-lifecycle R76a-1
 * @spec encryption.primitives-lifecycle R76a-2
 */
public final class KmsErrorClassifier {

    private KmsErrorClassifier() {
        throw new UnsupportedOperationException("utility class — do not instantiate");
    }

    /**
     * Classify {@code t}. {@link KmsPermanentException} (and subclasses) → PERMANENT,
     * {@link KmsTransientException} (and subclasses) → TRANSIENT, all others → UNCLASSIFIED.
     *
     * @throws NullPointerException if {@code t} is null
     */
    public static ErrorClass classify(Throwable t) {
        Objects.requireNonNull(t, "t");
        if (t instanceof KmsPermanentException) {
            return ErrorClass.PERMANENT;
        }
        if (t instanceof KmsTransientException) {
            return ErrorClass.TRANSIENT;
        }
        return ErrorClass.UNCLASSIFIED;
    }

    /** Closed set of classification buckets. */
    public enum ErrorClass {
        /** A permanent failure — no retry will succeed. Triggers state-machine transition. */
        PERMANENT,
        /** A transient failure — retryable. Does not trigger state transition on its own. */
        TRANSIENT,
        /** Cannot be classified by the table; subject to R76a-2 threshold escalation. */
        UNCLASSIFIED;
    }
}

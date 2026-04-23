package jlsm.encryption;

/**
 * Monotonically-increasing version number for a DEK within its
 * {@code (tenant, domain, table)} scope. Versions are positive integers, starting at
 * {@link #FIRST} and incremented by 1 on each rotation (R18). Reaching
 * {@link Integer#MAX_VALUE} is a hard error (R18a).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R18, R18a, R56.
 *
 * @param value positive integer DEK version number
 */
public record DekVersion(int value) {

    /** The first DEK version for any scope. */
    public static final DekVersion FIRST = new DekVersion(1);

    /**
     * @throws IllegalArgumentException if {@code value} is not positive (R56: "DEK
     *         versions must be positive integers")
     */
    public DekVersion {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "DekVersion must be a positive integer (R56), got " + value);
        }
    }
}

package jlsm.core.indexing;

/**
 * The floating-point precision used for vector storage in a {@link VectorIndex}.
 *
 * <p>
 * Precision is an explicit builder choice that determines how many bytes each vector component
 * occupies in the backing store. Distance computation always uses {@code float} (32-bit) arithmetic
 * regardless of storage precision — float16 vectors are decoded to float32 before scoring.
 *
 * <ul>
 * <li>{@link #FLOAT32} — IEEE 754 binary32, 4 bytes per component (default).</li>
 * <li>{@link #FLOAT16} — IEEE 754 binary16, 2 bytes per component (~50% storage reduction).</li>
 * </ul>
 */
public enum VectorPrecision {

    /** IEEE 754 binary32 — 4 bytes per vector component. */
    FLOAT32(4),

    /** IEEE 754 binary16 — 2 bytes per vector component. */
    FLOAT16(2);

    private final int bytesPerComponent;

    VectorPrecision(int bytesPerComponent) {
        this.bytesPerComponent = bytesPerComponent;
    }

    /**
     * Returns the number of bytes each vector component occupies in the serialized form.
     *
     * @return 4 for {@link #FLOAT32}, 2 for {@link #FLOAT16}
     */
    public int bytesPerComponent() {
        return bytesPerComponent;
    }
}

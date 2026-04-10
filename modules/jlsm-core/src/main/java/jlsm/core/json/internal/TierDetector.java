package jlsm.core.json.internal;

/**
 * Detects the best available SIMD tier for JSON structural indexing at class-load time.
 *
 * <p>
 * Tier selection is performed once and cached in the {@link #TIER} field:
 * <ul>
 * <li><b>Tier 1</b> — Panama FFM + CLMUL (PCLMULQDQ/PMULL carry-less multiply)</li>
 * <li><b>Tier 2</b> — Vector API ({@code jdk.incubator.vector})</li>
 * <li><b>Tier 3</b> — Scalar byte-by-byte fallback</li>
 * </ul>
 *
 * <p>
 * Detection catches all exceptions and falls through gracefully: if tier 1 detection throws, tier 2
 * is attempted; if tier 2 throws, tier 3 is used.
 *
 * @spec F15.R20 — three-tier detection
 * @spec F15.R21 — graceful fallback
 * @spec F15.R22 — Panama FFM tier 1
 */
public final class TierDetector {

    /** Tier 1: Panama FFM + CLMUL. */
    public static final int TIER_1_PANAMA = 1;

    /** Tier 2: Vector API. */
    public static final int TIER_2_VECTOR = 2;

    /** Tier 3: Scalar fallback. */
    public static final int TIER_3_SCALAR = 3;

    /**
     * The detected SIMD tier for this JVM instance. One of {@link #TIER_1_PANAMA},
     * {@link #TIER_2_VECTOR}, or {@link #TIER_3_SCALAR}.
     */
    public static final int TIER = detectTier();

    /**
     * Detects the best available SIMD tier. Catches all exceptions and falls through gracefully to
     * the scalar tier.
     *
     * @return the detected tier (1, 2, or 3)
     */
    @SuppressWarnings("unused")
    static int detectTier() {
        // Tier 1: Panama FFM + native linker availability
        try {
            // Probe that java.lang.foreign.Linker is available and functional
            Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
            var nativeLinkerMethod = linkerClass.getMethod("nativeLinker");
            Object linker = nativeLinkerMethod.invoke(null);
            if (linker != null) {
                // Also probe PanamaStage1 class to ensure it loads cleanly
                Class.forName("jlsm.core.json.internal.PanamaStage1");
                return TIER_1_PANAMA;
            }
        } catch (Throwable _) {
            // Fall through to tier 2
        }

        // Tier 2: Vector API with species width >= 16
        try {
            Class<?> byteVectorClass = Class.forName("jdk.incubator.vector.ByteVector");
            var speciesField = byteVectorClass.getField("SPECIES_PREFERRED");
            Object species = speciesField.get(null);
            var lengthMethod = species.getClass().getMethod("length");
            int width = (int) lengthMethod.invoke(species);
            if (width >= 16) {
                // Also probe VectorStage1 class to ensure it loads
                Class.forName("jlsm.core.json.internal.VectorStage1");
                return TIER_2_VECTOR;
            }
        } catch (Throwable _) {
            // Fall through to tier 3
        }

        return TIER_3_SCALAR;
    }

    private TierDetector() {
        // Static utility class
    }
}

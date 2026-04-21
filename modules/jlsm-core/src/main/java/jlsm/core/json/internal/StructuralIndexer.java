package jlsm.core.json.internal;

import java.util.Objects;

/**
 * Stage 1 orchestrator that dispatches to the active tier's structural scanner and produces a
 * {@link StructuralIndex}.
 *
 * @spec serialization.simd-jsonl.R18 — SIMD character classification for structural indexing
 * @spec serialization.simd-jsonl.R19 — backslash-parity for escaped quote detection
 * @spec serialization.simd-jsonl.R20 — three tiers: Panama FFM, Vector API, scalar
 * @spec serialization.simd-jsonl.R21 — tier detected once at class-load, stored in static final
 */
public final class StructuralIndexer {

    /**
     * Builds a structural index from the given JSON input bytes.
     *
     * @param input the raw JSON bytes; must not be null
     * @return a structural index suitable for stage 2 parsing
     * @throws NullPointerException if input is null
     */
    public static StructuralIndex index(byte[] input) {
        Objects.requireNonNull(input, "input must not be null");

        int[] positions = switch (TierDetector.TIER) {
            case TierDetector.TIER_1_PANAMA -> PanamaStage1.scan(input);
            case TierDetector.TIER_2_VECTOR -> VectorStage1.scan(input);
            default -> ScalarStage1.scan(input);
        };

        return new StructuralIndex(input, positions);
    }

    private StructuralIndexer() {
        // Static utility class
    }

    /**
     * The result of stage 1 structural indexing: an ordered set of byte positions corresponding to
     * structural characters outside of string literals.
     *
     * @param input the original input bytes
     * @param positions the structural character positions in ascending order
     */
    public record StructuralIndex(byte[] input, int[] positions) {

        /**
         * Creates a structural index.
         *
         * @param input the original input bytes; must not be null
         * @param positions the structural positions; must not be null
         */
        public StructuralIndex {
            Objects.requireNonNull(input, "input must not be null");
            Objects.requireNonNull(positions, "positions must not be null");
        }
    }
}

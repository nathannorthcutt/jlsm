package jlsm.core.indexing;

/**
 * Describes how similarity between two float vectors is computed and compared.
 *
 * <p>
 * All functions are oriented so that <strong>higher scores mean more similar</strong>:
 * <ul>
 * <li>{@link #COSINE} — normalized dot product; range [−1, 1].</li>
 * <li>{@link #DOT_PRODUCT} — raw inner product; unbounded.</li>
 * <li>{@link #EUCLIDEAN} — negated Euclidean distance {@code −√Σ(aᵢ−bᵢ)²}; range (−∞, 0]. Identical
 * vectors score 0.0; less negative values are more similar.</li>
 * </ul>
 */
public enum SimilarityFunction {
    COSINE, EUCLIDEAN, DOT_PRODUCT
}

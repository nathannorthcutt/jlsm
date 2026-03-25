package jlsm.core.indexing;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * A similarity-search index that stores float vectors keyed by document IDs and supports
 * approximate nearest-neighbour queries.
 *
 * <p>
 * Sub-interfaces are partitioned by <em>algorithm</em>; the input vector type is always
 * {@code float[]}:
 * <ul>
 * <li>{@link IvfFlat} — Inverted File with Flat (exhaustive) re-scoring within clusters.</li>
 * <li>{@link Hnsw} — Hierarchical Navigable Small World graph traversal.</li>
 * </ul>
 *
 * <p>
 * Score direction follows the {@link SimilarityFunction} convention: higher scores are always more
 * similar.
 *
 * @param <D> the document ID type
 */
public sealed interface VectorIndex<D> extends Closeable
        permits VectorIndex.IvfFlat, VectorIndex.Hnsw {

    /**
     * Indexes {@code vector} under {@code docId}. If {@code docId} was previously indexed, the old
     * vector is replaced.
     *
     * @param docId the document identifier; must not be null
     * @param vector the float vector; must not be null and must match the configured dimensions
     * @throws IOException if an I/O error occurs writing to backing storage
     */
    void index(D docId, float[] vector) throws IOException;

    /**
     * Removes the vector associated with {@code docId} from the index. If {@code docId} is not
     * present, this is a no-op.
     *
     * @param docId the document identifier; must not be null
     * @throws IOException if an I/O error occurs writing to backing storage
     */
    void remove(D docId) throws IOException;

    /**
     * Returns the top-{@code k} most similar documents to {@code query}.
     *
     * @param query the query vector; must not be null and must match the configured dimensions
     * @param topK the maximum number of results; must be &gt; 0
     * @return a list of up to {@code topK} results in descending similarity order; never null
     * @throws IOException if an I/O error occurs reading from backing storage
     */
    List<SearchResult<D>> search(float[] query, int topK) throws IOException;

    /**
     * Returns the precision used for vector storage in this index.
     *
     * @return the configured {@link VectorPrecision}; never null
     */
    VectorPrecision precision();

    @Override
    void close() throws IOException;

    /**
     * A single result from a {@link #search} call.
     *
     * @param docId the matching document identifier
     * @param score the similarity score (higher = more similar)
     * @param <D> the document ID type
     */
    record SearchResult<D>(D docId, float score) {

        /**
         * Validates that the search result has a non-null document ID and a non-NaN score.
         *
         * @throws NullPointerException if {@code docId} is null
         * @throws IllegalArgumentException if {@code score} is NaN
         */
        public SearchResult {
            Objects.requireNonNull(docId, "docId must not be null");
            if (Float.isNaN(score)) {
                throw new IllegalArgumentException("score must not be NaN");
            }
        }
    }

    /** Inverted File with Flat re-scoring similarity search. */
    non-sealed interface IvfFlat<D> extends VectorIndex<D> {
    }

    /** Hierarchical Navigable Small World graph similarity search. */
    non-sealed interface Hnsw<D> extends VectorIndex<D> {
    }
}

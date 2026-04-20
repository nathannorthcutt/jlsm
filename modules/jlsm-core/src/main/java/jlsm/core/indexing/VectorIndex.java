package jlsm.core.indexing;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
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
     * SPI for producing {@link VectorIndex} instances keyed by {@code (tableName, fieldName)}.
     *
     * <p>
     * The factory is the module-boundary contract that lets higher-level modules (e.g.
     * {@code jlsm-table}) obtain a vector index implementation without a static dependency on the
     * module that owns the concrete implementation (e.g. {@code jlsm-vector}). Each call to
     * {@link #create} returns a fresh index that the caller owns and must close.
     *
     * <p>
     * The factory picks the algorithm (e.g. IvfFlat vs Hnsw) as part of its construction —
     * different factory instances can produce different algorithm flavours. The
     * {@code (dimensions, elementPrecision, similarityFunction)} triple comes from the table schema
     * and index definition and must be respected by the implementation.
     *
     * <p>
     * Implementations should isolate each {@code (tableName, fieldName)} pair on its own backing
     * storage so multiple indices on the same root directory do not share state.
     */
    // @spec F10.R85,R86,R87,R88,R89,R90 — factory SPI that bridges jlsm-core and jlsm-vector
    // without a static jlsm-table -> jlsm-vector dependency; resolves OBL-F10-vector.
    interface Factory {

        /**
         * Creates a vector index for the given table and field.
         *
         * @param tableName the name of the table owning the index; must not be null or blank
         * @param fieldName the name of the field being indexed; must not be null or blank
         * @param dimensions the fixed number of components per vector; must be positive
         * @param precision the component precision (FLOAT32 or FLOAT16); must not be null
         * @param similarityFunction the similarity function; must not be null
         * @return a new {@link VectorIndex} instance keyed by primary-key bytes
         * @throws IOException if the backing storage cannot be created
         */
        VectorIndex<MemorySegment> create(String tableName, String fieldName, int dimensions,
                VectorPrecision precision, SimilarityFunction similarityFunction)
                throws IOException;
    }

    /**
     * Indexes {@code vector} under {@code docId}. If {@code docId} was previously indexed, the old
     * vector is replaced.
     *
     * <p>
     * Re-indexing caveat for graph-based implementations (HNSW): calling {@code index} on a
     * {@code docId} that is already present, without a prior {@link #remove} call, replaces the
     * stored vector but does not retire the bidirectional edges that former neighbors still hold
     * pointing at the old vector content. Those stale backlinks may degrade graph quality and
     * recall over time. For accuracy-sensitive workloads, call {@code remove(docId)} before
     * re-indexing a document. This degradation does not affect correctness of individual search
     * results — it only reduces recall.
     *
     * @param docId the document identifier; must not be null
     * @param vector the float vector; must not be null and must match the configured dimensions
     * @throws IOException if an I/O error occurs writing to backing storage
     */
    // @spec F01.R23 — document re-indexing graph-quality degradation in public API
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
    // @spec F01.R2 — precision is queryable after construction and never null
    // @spec F01.R4 — precision is chosen at build time and does not change after construction
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
    // @spec F01.R25,R25a — SearchResult accepts Infinity scores; Infinity exclusion is
    // enforced by candidate-accumulation filtering in implementations (see LsmVectorIndex
    // IvfFlat.search and Hnsw.search), not by this record.
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

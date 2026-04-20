package jlsm.core.indexing;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Map;

/**
 * Higher-level full-text search index that layers tokenization and a composable query DSL on top of
 * a term-based inverted index.
 *
 * <p>
 * Documents are indexed as a map of field names to raw text. The implementation tokenizes each
 * field's text and stores the resulting tokens.
 *
 * <p>
 * Re-indexing the same document with the same content is idempotent — the underlying LSM put is
 * idempotent.
 *
 * @param <D> the document ID type
 */
public interface FullTextIndex<D> extends Closeable {

    /**
     * SPI for producing {@link FullTextIndex} instances keyed by {@code (tableName, fieldName)}.
     *
     * <p>
     * The factory is the module-boundary contract that lets higher-level modules (e.g.
     * {@code jlsm-table}) obtain a full-text index implementation without a static dependency on
     * the module that owns the concrete implementation (e.g. {@code jlsm-indexing}). Each call to
     * {@link #create(String, String)} returns a fresh index that the caller owns and must close.
     *
     * <p>
     * Implementations should isolate each {@code (tableName, fieldName)} pair on its own backing
     * storage so multiple indices on the same root directory do not share state.
     */
    interface Factory {

        /**
         * Creates a full-text index for the given table and field.
         *
         * @param tableName the name of the table owning the index; must not be null or blank
         * @param fieldName the name of the field being indexed; must not be null or blank
         * @return a new {@link FullTextIndex} instance keyed by primary-key bytes
         * @throws IOException if the backing storage cannot be created
         */
        FullTextIndex<MemorySegment> create(String tableName, String fieldName) throws IOException;
    }

    /**
     * Indexes {@code docId} under all tokens extracted from each field's text.
     *
     * @param docId the document ID to index; must not be null
     * @param fields map of field name to raw text; must not be null
     * @throws IOException if the underlying store cannot be written to
     */
    void index(D docId, Map<String, String> fields) throws IOException;

    /**
     * Removes the index entries for {@code docId} in each field. Callers must supply the same text
     * that was passed to {@link #index} so the implementation can re-tokenize and produce the
     * correct term keys.
     *
     * @param docId the document ID to remove; must not be null
     * @param fields map of field name to the same raw text used during indexing; must not be null
     * @throws IOException if the underlying store cannot be written to
     */
    void remove(D docId, Map<String, String> fields) throws IOException;

    /**
     * Returns an iterator over all document IDs matching {@code query}.
     *
     * @param query the query to evaluate; must not be null
     * @return an iterator over matching document IDs; empty if nothing matches
     * @throws IOException if the underlying store cannot be read
     */
    Iterator<D> search(Query query) throws IOException;
}

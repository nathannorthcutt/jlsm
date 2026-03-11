package jlsm.core.indexing;

import java.io.Closeable;
import java.io.IOException;
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

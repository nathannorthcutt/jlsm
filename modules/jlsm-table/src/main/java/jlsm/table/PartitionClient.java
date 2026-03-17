package jlsm.table;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * SPI for dispatching operations to a single partition.
 *
 * <p>
 * Contract: Abstracts the communication channel between the {@link PartitionedTable} coordinator
 * and a partition. The in-process implementation wraps a {@link JlsmTable.StringKeyed} directly.
 * Future remote implementations will serialize operations over a network transport.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — remote-capable interface designed for future
 * network transport.
 */
public interface PartitionClient extends Closeable {

    /**
     * Returns the descriptor for this partition.
     *
     * @return the partition descriptor
     */
    PartitionDescriptor descriptor();

    /**
     * Creates a document in this partition.
     *
     * @param key the document key
     * @param doc the document to create
     * @throws IOException if the write fails
     * @throws DuplicateKeyException if the key already exists
     */
    void create(String key, JlsmDocument doc) throws IOException;

    /**
     * Retrieves a document by key from this partition.
     *
     * @param key the document key
     * @return the document, or empty if not found
     * @throws IOException if the read fails
     */
    Optional<JlsmDocument> get(String key) throws IOException;

    /**
     * Updates a document in this partition.
     *
     * @param key the document key
     * @param doc the updated document
     * @param mode replace or patch
     * @throws IOException if the write fails
     * @throws KeyNotFoundException if the key does not exist
     */
    void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException;

    /**
     * Deletes a document from this partition.
     *
     * @param key the document key
     * @throws IOException if the write fails
     */
    void delete(String key) throws IOException;

    /**
     * Returns entries in this partition within the given key range.
     *
     * @param fromKey inclusive lower bound
     * @param toKey exclusive upper bound
     * @return iterator over matching entries
     * @throws IOException if the read fails
     */
    Iterator<TableEntry<String>> getRange(String fromKey, String toKey) throws IOException;

    /**
     * Executes a predicate query against this partition, returning matching entries.
     *
     * <p>
     * For vector and full-text predicates, the partition executes the search locally against its
     * co-located indices and returns local top-k results.
     *
     * @param predicate the query predicate (may include vector, full-text, or property predicates)
     * @param limit maximum results to return from this partition
     * @return matching entries with scores (for ranked queries)
     * @throws IOException if the query fails
     */
    List<ScoredEntry<String>> query(Predicate predicate, int limit) throws IOException;
}

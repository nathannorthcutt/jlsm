package jlsm.table;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
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
 * Null-rejection: All parameters are validated at the interface level via default methods that
 * delegate to abstract {@code doXxx} methods. Implementations override the {@code doXxx} methods
 * and receive guaranteed non-null arguments.
 *
 * <p>
 * Governed by: .decisions/table-partitioning/adr.md — remote-capable interface designed for future
 * network transport.
 */
// @spec F11.R39,R40,R47,R107 — public interface in jlsm.table extending Closeable (R39);
// descriptor()
// exposes the partition (R40); method signatures use only serializable-friendly types (R47);
// default
// methods enforce null-rejection before delegating to doXxx hooks (R107)
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
     * @param key the document key; must not be null
     * @param doc the document to create; must not be null
     * @throws NullPointerException if key or doc is null
     * @throws IOException if the write fails
     * @throws DuplicateKeyException if the key already exists
     */
    // @spec F11.R41 — create throws IOException on write failure, DuplicateKeyException if exists
    default void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        doCreate(key, doc);
    }

    /**
     * Implementation hook for {@link #create(String, JlsmDocument)}. Called after null-checking;
     * both parameters are guaranteed non-null.
     */
    void doCreate(String key, JlsmDocument doc) throws IOException;

    /**
     * Retrieves a document by key from this partition.
     *
     * @param key the document key; must not be null
     * @return the document, or empty if not found
     * @throws NullPointerException if key is null
     * @throws IOException if the read fails
     */
    // @spec F11.R42 — get returns Optional<JlsmDocument> (empty if missing); IOException on read
    // failure
    default Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        return doGet(key);
    }

    /**
     * Implementation hook for {@link #get(String)}. Called after null-checking; key is guaranteed
     * non-null.
     */
    Optional<JlsmDocument> doGet(String key) throws IOException;

    /**
     * Updates a document in this partition.
     *
     * @param key the document key; must not be null
     * @param doc the updated document; must not be null
     * @param mode replace or patch; must not be null
     * @throws NullPointerException if any parameter is null
     * @throws IOException if the write fails
     * @throws KeyNotFoundException if the key does not exist
     */
    // @spec F11.R43 — update throws IOException on write failure, KeyNotFoundException if missing
    default void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        doUpdate(key, doc, mode);
    }

    /**
     * Implementation hook for {@link #update(String, JlsmDocument, UpdateMode)}. Called after
     * null-checking; all parameters are guaranteed non-null.
     */
    void doUpdate(String key, JlsmDocument doc, UpdateMode mode) throws IOException;

    /**
     * Deletes a document from this partition.
     *
     * @param key the document key; must not be null
     * @throws NullPointerException if key is null
     * @throws IOException if the write fails
     */
    // @spec F11.R44 — delete throws IOException on write failure
    default void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        doDelete(key);
    }

    /**
     * Implementation hook for {@link #delete(String)}. Called after null-checking; key is
     * guaranteed non-null.
     */
    void doDelete(String key) throws IOException;

    /**
     * Returns entries in this partition within the given key range.
     *
     * @param fromKey inclusive lower bound; must not be null
     * @param toKey exclusive upper bound; must not be null
     * @return iterator over matching entries
     * @throws NullPointerException if fromKey or toKey is null
     * @throws IOException if the read fails
     */
    // @spec F11.R45 — getRange returns Iterator over [fromKey, toKey); IOException on read failure
    default Iterator<TableEntry<String>> getRange(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        return doGetRange(fromKey, toKey);
    }

    /**
     * Implementation hook for {@link #getRange(String, String)}. Called after null-checking; both
     * parameters are guaranteed non-null.
     */
    Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) throws IOException;

    /**
     * Returns entries in this partition within the given key range asynchronously.
     *
     * <p>
     * Contract: Remote implementations should return a future that completes when the
     * transport-layer response arrives, without blocking the calling thread. Default implementation
     * wraps the synchronous {@link #getRange(String, String)} result in a completed future —
     * suitable for local/in-process clients where no async benefit exists.
     *
     * <p>
     * Delivers: F04.R77 — scatter-gather must use the transport's asynchronous request mechanism
     * for parallel fanout.
     *
     * <p>
     * Governed by: {@code .decisions/scatter-gather-query-execution/adr.md}
     *
     * @param fromKey inclusive lower bound; must not be null
     * @param toKey exclusive upper bound; must not be null
     * @return future completing with the entry iterator, or exceptionally on failure; never null
     * @throws NullPointerException if fromKey or toKey is null
     */
    default java.util.concurrent.CompletableFuture<Iterator<TableEntry<String>>> getRangeAsync(
            String fromKey, String toKey) {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        try {
            return java.util.concurrent.CompletableFuture
                    .completedFuture(doGetRange(fromKey, toKey));
        } catch (IOException e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            // H-CB-9 — RuntimeException from doGetRange must be wrapped in a failed future, not
            // thrown synchronously. Only null-check NPE above is permitted to escape sync.
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Executes a predicate query against this partition, returning matching entries.
     *
     * <p>
     * For vector and full-text predicates, the partition executes the search locally against its
     * co-located indices and returns local top-k results.
     *
     * @param predicate the query predicate (may include vector, full-text, or property predicates);
     *            must not be null
     * @param limit maximum results to return from this partition; must be positive
     * @return matching entries with scores (for ranked queries)
     * @throws NullPointerException if predicate is null
     * @throws IllegalArgumentException if limit is less than 1
     * @throws IOException if the query fails
     */
    default List<ScoredEntry<String>> query(Predicate predicate, int limit) throws IOException {
        Objects.requireNonNull(predicate, "predicate must not be null");
        return doQuery(predicate, limit);
    }

    /**
     * Implementation hook for {@link #query(Predicate, int)}. Called after null-checking; predicate
     * is guaranteed non-null.
     */
    List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) throws IOException;
}

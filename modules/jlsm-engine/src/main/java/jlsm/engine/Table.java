package jlsm.engine;

import jlsm.table.DuplicateKeyException;
import jlsm.table.JlsmDocument;
import jlsm.table.KeyNotFoundException;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * A tracked table handle providing data operations and query pass-through.
 *
 * <p>
 * Each call to {@link Engine#getTable(String)} or {@link Engine#createTable} returns a new tracked
 * handle. The handle must be closed when no longer needed. Operations on a closed or evicted handle
 * throw {@link HandleEvictedException}.
 *
 * <p>
 * <b>Sealed type hierarchy.</b> The two permitted implementations
 * ({@code jlsm.engine.internal.CatalogTable} for single-node and
 * {@code jlsm.engine.cluster.internal.CatalogClusteredTable} for clustered) both live in
 * non-exported internal packages and are constructed exclusively by their respective engine
 * factories. External consumers of {@code jlsm-engine} cannot implement {@code Table} — the Java
 * compiler rejects any non-permitted subtype. This enforces at compile time that any handle
 * reaching the encryption read path is catalog-mediated and that its {@link #metadata()} encryption
 * value is authoritative — not an attacker-constructed Table whose {@code metadata()} returns
 * forged scope.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md},
 * {@code .decisions/table-handle-scope-exposure/adr.md} v2.
 *
 * @spec sstable.footer-encryption-scope.R8e
 * @spec sstable.footer-encryption-scope.R6c
 */
public sealed interface Table extends AutoCloseable permits jlsm.engine.internal.CatalogTable,
        jlsm.engine.cluster.internal.CatalogClusteredTable {

    /**
     * Creates a new entry with the given key and document.
     *
     * @param key the entry key; must not be null
     * @param doc the document to store; must not be null
     * @throws DuplicateKeyException if an entry with the given key already exists
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    void create(String key, JlsmDocument doc) throws IOException;

    /**
     * Returns the document associated with the given key, or empty if not found.
     *
     * @param key the entry key; must not be null
     * @return an Optional containing the document, or empty if absent
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    Optional<JlsmDocument> get(String key) throws IOException;

    /**
     * Updates an existing entry.
     *
     * @param key the entry key; must not be null
     * @param doc the new document; must not be null
     * @param mode the update mode (REPLACE or PATCH)
     * @throws KeyNotFoundException if no entry with the given key exists
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException;

    /**
     * Deletes the entry with the given key.
     *
     * @param key the entry key; must not be null
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    void delete(String key) throws IOException;

    /**
     * Inserts a document using its schema-defined primary key.
     *
     * @param doc the document to insert; must not be null
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    void insert(JlsmDocument doc) throws IOException;

    /**
     * Returns a fluent query builder bound to this table.
     *
     * @return a new query builder; never null
     * @throws HandleEvictedException if this handle has been evicted or closed
     */
    TableQuery<String> query();

    /**
     * Returns an iterator over entries in the key range [{@code fromKey}, {@code toKey}).
     *
     * @param fromKey the inclusive lower bound; must not be null
     * @param toKey the exclusive upper bound; must not be null
     * @return an iterator of table entries
     * @throws HandleEvictedException if this handle has been evicted or closed
     * @throws IOException on I/O failure
     */
    Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException;

    /**
     * Returns metadata for the table this handle references.
     *
     * @return the table metadata; never null
     */
    TableMetadata metadata();

    /**
     * Closes this handle, releasing its tracked registration.
     */
    @Override
    void close();
}

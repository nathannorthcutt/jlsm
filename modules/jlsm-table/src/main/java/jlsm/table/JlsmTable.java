package jlsm.table;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * A typed document table backed by an LSM-Tree.
 *
 * <p>
 * Two key types are provided via the nested sealed sub-interfaces: {@link StringKeyed} for string
 * keys and {@link LongKeyed} for long keys.
 */
public sealed interface JlsmTable extends Closeable
        permits JlsmTable.StringKeyed, JlsmTable.LongKeyed {

    /** A {@link JlsmTable} keyed by {@link String}. */
    non-sealed interface StringKeyed extends JlsmTable {

        /**
         * Creates a new entry. Throws {@link DuplicateKeyException} if the key exists.
         *
         * @param key the key; must not be null
         * @param doc the document to store; must not be null
         * @throws DuplicateKeyException if an entry with the given key already exists
         * @throws IOException on I/O failure
         */
        void create(String key, JlsmDocument doc) throws IOException;

        /**
         * Returns the document associated with the given key, or empty if not found.
         *
         * @param key the key; must not be null
         * @return an Optional containing the document, or empty if absent
         * @throws IOException on I/O failure
         */
        Optional<JlsmDocument> get(String key) throws IOException;

        /**
         * Updates an existing entry. Throws {@link KeyNotFoundException} if not found.
         *
         * @param key the key; must not be null
         * @param doc the new document; must not be null
         * @param mode the update mode (REPLACE or PATCH)
         * @throws KeyNotFoundException if no entry with the given key exists
         * @throws IOException on I/O failure
         */
        void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException;

        /**
         * Deletes the entry with the given key.
         *
         * @param key the key; must not be null
         * @throws IOException on I/O failure
         */
        void delete(String key) throws IOException;

        /**
         * Returns an iterator over all entries with keys in the range [{@code from}, {@code to}).
         *
         * @param from the inclusive lower bound; must not be null
         * @param to the exclusive upper bound; must not be null
         * @return an iterator of {@link TableEntry} values
         * @throws IOException on I/O failure
         */
        Iterator<TableEntry<String>> getAllInRange(String from, String to) throws IOException;

        /**
         * Returns a fluent {@link TableQuery} for this table. The default implementation returns an
         * unbound query — its {@link TableQuery#execute()} throws
         * {@link UnsupportedOperationException}. Production table implementations override this
         * method to return a query bound to the table's schema, primary storage, and any registered
         * secondary indices.
         *
         * @return a {@link TableQuery} for building and executing predicate queries
         */
        // @spec F05.R37, F10.R37 — query() must return a functional TableQuery wired to the
        // underlying table when the table has a schema; unbound otherwise.
        default TableQuery<String> query() {
            return TableQuery.unbound();
        }

        /** Builder for {@link StringKeyed} tables. */
        interface Builder {
            JlsmTable.StringKeyed build();
        }
    }

    /** A {@link JlsmTable} keyed by {@code long}. */
    non-sealed interface LongKeyed extends JlsmTable {

        /**
         * Creates a new entry. Throws {@link DuplicateKeyException} if the key exists.
         *
         * @param key the key
         * @param doc the document to store; must not be null
         * @throws DuplicateKeyException if an entry with the given key already exists
         * @throws IOException on I/O failure
         */
        void create(long key, JlsmDocument doc) throws IOException;

        /**
         * Returns the document associated with the given key, or empty if not found.
         *
         * @param key the key
         * @return an Optional containing the document, or empty if absent
         * @throws IOException on I/O failure
         */
        Optional<JlsmDocument> get(long key) throws IOException;

        /**
         * Updates an existing entry. Throws {@link KeyNotFoundException} if not found.
         *
         * @param key the key
         * @param doc the new document; must not be null
         * @param mode the update mode (REPLACE or PATCH)
         * @throws KeyNotFoundException if no entry with the given key exists
         * @throws IOException on I/O failure
         */
        void update(long key, JlsmDocument doc, UpdateMode mode) throws IOException;

        /**
         * Deletes the entry with the given key.
         *
         * @param key the key
         * @throws IOException on I/O failure
         */
        void delete(long key) throws IOException;

        /**
         * Returns an iterator over all entries with keys in the range [{@code from}, {@code to}).
         *
         * @param from the inclusive lower bound
         * @param to the exclusive upper bound
         * @return an iterator of {@link TableEntry} values
         * @throws IOException on I/O failure
         */
        Iterator<TableEntry<Long>> getAllInRange(long from, long to) throws IOException;

        /** Builder for {@link LongKeyed} tables. */
        interface Builder {
            JlsmTable.LongKeyed build();
        }
    }
}

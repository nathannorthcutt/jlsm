package jlsm.engine;

import jlsm.table.JlsmSchema;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;

/**
 * Engine lifecycle and table management interface.
 *
 * <p>
 * Manages the lifecycle of multiple named tables within a single root directory. Each table is
 * backed by its own subdirectory containing WAL, SSTable, and metadata files. The engine handles
 * catalog persistence, lazy table initialization, and handle tracking.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 */
public interface Engine extends Closeable {

    /**
     * Creates a new table with the given name and schema.
     *
     * @param name the table name; must not be null or empty
     * @param schema the table schema; must not be null
     * @return a handle to the newly created table
     * @throws IllegalArgumentException if name is null or empty, or schema is null
     * @throws IOException if a table with the given name already exists, or on I/O failure
     */
    Table createTable(String name, JlsmSchema schema) throws IOException;

    /**
     * Returns a handle to an existing table.
     *
     * @param name the table name; must not be null or empty
     * @return a handle to the table
     * @throws IllegalArgumentException if name is null or empty
     * @throws IOException if no table with the given name exists, or on I/O failure
     */
    Table getTable(String name) throws IOException;

    /**
     * Drops an existing table, removing its catalog entry and storage.
     *
     * @param name the table name; must not be null or empty
     * @throws IllegalArgumentException if name is null or empty
     * @throws IOException if no table with the given name exists, or on I/O failure
     */
    void dropTable(String name) throws IOException;

    /**
     * Returns metadata for all known tables.
     *
     * @return an unmodifiable collection of table metadata; never null
     */
    Collection<TableMetadata> listTables();

    /**
     * Returns metadata for a specific table, or null if not found.
     *
     * @param name the table name; must not be null or empty
     * @return the table metadata, or null if no table with the given name exists
     */
    TableMetadata tableMetadata(String name);

    /**
     * Returns a snapshot of engine-wide metrics.
     *
     * @return the current engine metrics; never null
     */
    EngineMetrics metrics();

    /**
     * Closes the engine, force-invalidating all outstanding table handles and flushing all tables.
     *
     * @throws IOException if an I/O error occurs during shutdown
     */
    @Override
    void close() throws IOException;
}

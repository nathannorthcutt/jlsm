package jlsm.engine;

import jlsm.encryption.TableScope;
import jlsm.engine.internal.LocalEngine;
import jlsm.table.JlsmSchema;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

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
// @spec engine.in-process-database-engine.R76 — public API surface uses only exported types (Table,
// TableMetadata, EngineMetrics, JlsmSchema); internal classes (LocalEngine, HandleTracker) are
// never
// exposed in parameter or return positions.
public interface Engine extends Closeable {

    /**
     * Returns a new builder for constructing a local (in-process) {@link Engine}.
     *
     * @return a new builder; never null
     */
    // @spec engine.in-process-database-engine.R1 — public factory exposed from the exported
    // jlsm.engine package
    static Builder builder() {
        return new Builder();
    }

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
     * Creates a new encrypted table with the given name, schema, and scope. The table's
     * {@link TableMetadata#encryption()} is set to {@code Optional.of(EncryptionMetadata(scope))}
     * at creation; subsequent SSTable writers in this table emit format magic v6 with the scope
     * section in the footer.
     *
     * <p>
     * The default method throws {@link UnsupportedOperationException} so test stubs implementing
     * {@code Engine} need not override it. Production implementations
     * ({@link jlsm.engine.internal.LocalEngine}, {@link jlsm.engine.cluster.ClusteredEngine})
     * override with the 5-step protocol body landed by WU-3.
     *
     * @param name the table name; must not be null or empty
     * @param schema the table schema; must not be null
     * @param scope the table's encryption scope; must not be null
     * @return a handle to the newly created encrypted table
     * @throws IllegalArgumentException if name is null or empty, or schema is null
     * @throws NullPointerException if scope is null
     * @throws IOException if a table with the given name already exists, or on I/O failure
     *
     * @spec sstable.footer-encryption-scope.R7
     */
    default Table createEncryptedTable(String name, JlsmSchema schema, TableScope scope)
            throws IOException {
        throw new UnsupportedOperationException(
                "createEncryptedTable is not implemented by this Engine impl (R7)");
    }

    /**
     * Enables encryption on an existing unencrypted table by atomically transitioning its
     * {@link TableMetadata#encryption()} from {@code Optional.empty()} to
     * {@code Optional.of(EncryptionMetadata(scope))}. Encryption is one-way: there is no symmetric
     * {@code disableEncryption}; callers who need to plaintext a table must use future
     * {@code copyTable} + {@code dropTable} primitives.
     *
     * <p>
     * The default method throws {@link UnsupportedOperationException} so test stubs implementing
     * {@code Engine} need not override it. Production implementations override with the 5-step
     * protocol body landed by WU-3.
     *
     * @param name the table name; must not be null or empty
     * @param scope the table's encryption scope; must not be null
     * @throws IllegalArgumentException if name is null or empty
     * @throws NullPointerException if scope is null
     * @throws IllegalStateException if the table is already encrypted
     * @throws IOException if no table with the given name exists, or on I/O failure
     *
     * @spec sstable.footer-encryption-scope.R7b
     */
    default void enableEncryption(String name, TableScope scope) throws IOException {
        throw new UnsupportedOperationException(
                "enableEncryption is not implemented by this Engine impl (R7b)");
    }

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

    /**
     * Builder for a local (in-process) {@link Engine} backed by a root directory on the local
     * filesystem. Delegates to the internal {@link LocalEngine.Builder}; the indirection keeps the
     * implementation class out of the exported public API surface.
     */
    // @spec engine.in-process-database-engine.R1 — public Builder type exposed from the exported
    // jlsm.engine package
    final class Builder {

        private final LocalEngine.Builder delegate;

        private Builder() {
            this.delegate = LocalEngine.builder();
        }

        /**
         * Sets the root directory that will hold all tables managed by this engine.
         *
         * @param path the absolute root directory; must not be null and must be absolute
         * @return this builder
         * @throws NullPointerException if {@code path} is null
         */
        public Builder rootDirectory(Path path) {
            delegate.rootDirectory(Objects.requireNonNull(path, "path must not be null"));
            return this;
        }

        public Builder maxHandlesPerSourcePerTable(int max) {
            delegate.maxHandlesPerSourcePerTable(max);
            return this;
        }

        public Builder maxHandlesPerTable(int max) {
            delegate.maxHandlesPerTable(max);
            return this;
        }

        public Builder maxTotalHandles(int max) {
            delegate.maxTotalHandles(max);
            return this;
        }

        public Builder allocationTracking(AllocationTracking tracking) {
            delegate.allocationTracking(tracking);
            return this;
        }

        public Builder memTableFlushThresholdBytes(long bytes) {
            delegate.memTableFlushThresholdBytes(bytes);
            return this;
        }

        /**
         * Constructs the engine. Validates configuration, scans the root directory for existing
         * tables, and returns a ready engine.
         *
         * @return a new {@link Engine}
         * @throws IOException if the root directory cannot be read or is corrupt
         * @throws IllegalArgumentException if any configuration value is invalid (including a
         *             non-absolute root directory — R3)
         * @throws IllegalStateException if required configuration has not been set
         */
        public Engine build() throws IOException {
            return delegate.build();
        }
    }
}

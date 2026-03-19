package jlsm.engine.internal;

import jlsm.engine.TableMetadata;
import jlsm.table.JlsmSchema;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages per-table directory layout, metadata persistence, and lazy table loading.
 *
 * <p>
 * Each table lives in its own subdirectory under the engine root. The catalog scans the root
 * directory on {@link #open()} to discover existing tables and builds lightweight metadata handles.
 * Full table initialization is deferred until first access.
 *
 * <p>
 * Governed by: {@code .decisions/table-catalog-persistence/adr.md}
 */
final class TableCatalog implements Closeable {

    private static final String METADATA_FILE = "table.meta";
    private static final int MAGIC = 0x4A4C534D; // "JLSM"

    private final Path rootDir;
    private final ConcurrentHashMap<String, TableMetadata> tables = new ConcurrentHashMap<>();
    private volatile boolean loading = true;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructs a new TableCatalog for the given root directory.
     *
     * @param rootDir the engine root directory; must not be null
     */
    TableCatalog(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir must not be null");
    }

    /**
     * Opens the catalog by scanning the root directory for existing table subdirectories. Builds
     * lightweight metadata handles without fully initializing tables. Creates the root directory if
     * it does not exist.
     *
     * @throws IOException if the root directory cannot be read or is corrupt
     */
    void open() throws IOException {
        if (!Files.exists(rootDir)) {
            Files.createDirectories(rootDir);
        }

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (final Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                final String tableName = entry.getFileName().toString();
                final Path metadataPath = entry.resolve(METADATA_FILE);

                if (!Files.exists(metadataPath)) {
                    // Partial creation cleanup: directory exists but no metadata file
                    deleteDirectoryTree(entry);
                    continue;
                }

                try {
                    final TableMetadata metadata = readMetadata(tableName, metadataPath);
                    tables.put(tableName, metadata);
                } catch (IOException e) {
                    // Corrupt metadata — clean up
                    deleteDirectoryTree(entry);
                }
            }
        }

        loading = false;
    }

    /**
     * Registers a new table by creating its subdirectory and metadata file.
     *
     * @param name the table name; must not be null or empty
     * @param schema the table schema; must not be null
     * @return the metadata for the newly registered table
     * @throws IOException if the table already exists or the directory cannot be created
     */
    TableMetadata register(String name, JlsmSchema schema) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        if (tables.containsKey(name)) {
            throw new IOException("Table already exists: " + name);
        }

        final Path tableDir = rootDir.resolve(name);
        Files.createDirectories(tableDir);

        final Instant createdAt = Instant.now();
        final TableMetadata metadata = new TableMetadata(name, schema, createdAt,
                TableMetadata.TableState.READY);

        writeMetadata(tableDir.resolve(METADATA_FILE), metadata);

        final TableMetadata previous = tables.putIfAbsent(name, metadata);
        if (previous != null) {
            // Concurrent registration — clean up and throw
            deleteDirectoryTree(tableDir);
            throw new IOException("Table already exists (concurrent registration): " + name);
        }

        return metadata;
    }

    /**
     * Unregisters a table by removing its subdirectory and all contained files.
     *
     * @param name the table name; must not be null or empty
     * @throws IOException if the table does not exist or cannot be removed
     */
    void unregister(String name) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        final TableMetadata removed = tables.remove(name);
        if (removed == null) {
            throw new IOException("Table does not exist: " + name);
        }

        final Path tableDir = rootDir.resolve(name);
        if (Files.exists(tableDir)) {
            deleteDirectoryTree(tableDir);
        }
    }

    /**
     * Returns metadata for a specific table, or empty if not registered.
     *
     * @param name the table name; must not be null
     * @return an Optional containing the metadata, or empty if not found
     */
    Optional<TableMetadata> get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(tables.get(name));
    }

    /**
     * Returns metadata for all known tables.
     *
     * @return an unmodifiable collection of table metadata; never null
     */
    Collection<TableMetadata> list() {
        return Collections.unmodifiableCollection(tables.values());
    }

    /**
     * Returns the directory path for a given table.
     *
     * @param name the table name; must not be null
     * @return the table's subdirectory path; never null
     */
    Path tableDirectory(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return rootDir.resolve(name);
    }

    /**
     * Returns true if the catalog is still loading (scanning the root directory).
     *
     * @return true if loading is in progress
     */
    boolean isLoading() {
        return loading;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            tables.clear();
        }
    }

    // ---- Private helpers ----

    /**
     * Writes table metadata to a binary file. Format: magic (4 bytes) | schema-name (UTF) |
     * schema-version (int) | field-count (int) | created-at-millis (long)
     */
    private static void writeMetadata(Path path, TableMetadata metadata) throws IOException {
        assert metadata != null : "metadata must not be null";
        try (final DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(MAGIC);
            out.writeUTF(metadata.schema().name());
            out.writeInt(metadata.schema().version());
            out.writeInt(metadata.schema().fields().size());
            out.writeLong(metadata.createdAt().toEpochMilli());
        }
    }

    /**
     * Reads minimal metadata from a binary file. Returns a TableMetadata with LOADING state and a
     * skeleton JlsmSchema (name and version only, no fields).
     */
    private static TableMetadata readMetadata(String tableName, Path path) throws IOException {
        assert tableName != null : "tableName must not be null";
        assert path != null : "path must not be null";
        try (final DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            final int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid metadata magic: 0x" + Integer.toHexString(magic));
            }
            final String schemaName = in.readUTF();
            final int schemaVersion = in.readInt();
            final int fieldCount = in.readInt(); // read but not used for reconstruction
            final long createdAtMillis = in.readLong();

            // Build skeleton schema — name and version only
            final JlsmSchema skeleton = JlsmSchema.builder(schemaName, schemaVersion).build();
            final Instant createdAt = Instant.ofEpochMilli(createdAtMillis);

            return new TableMetadata(tableName, skeleton, createdAt,
                    TableMetadata.TableState.LOADING);
        }
    }

    /**
     * Recursively deletes a directory and all its contents. Uses an iterative approach (depth-first
     * via walkFileTree).
     */
    private static void deleteDirectoryTree(Path root) throws IOException {
        assert root != null : "root must not be null";
        if (!Files.exists(root)) {
            return;
        }
        // Walk the tree and collect paths, then delete in reverse order (children first)
        final java.util.List<Path> paths = new java.util.ArrayList<>();
        try (final var walker = Files.walk(root)) {
            walker.forEach(paths::add);
        }
        // Delete in reverse order so children are deleted before parents
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }
}

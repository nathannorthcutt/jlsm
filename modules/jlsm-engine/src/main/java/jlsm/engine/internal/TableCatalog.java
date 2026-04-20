package jlsm.engine.internal;

import jlsm.engine.TableMetadata;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
    // @spec F05.R4,R5,R52,R53,R55,R57,R58,R59,R60,R85,R86 — startup scan + recovery
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
                    // Corrupt or unreadable metadata — preserve data, mark as ERROR
                    final JlsmSchema errorSchema = JlsmSchema.builder(tableName, 0).build();
                    final TableMetadata errorMetadata = new TableMetadata(tableName, errorSchema,
                            Instant.EPOCH, TableMetadata.TableState.ERROR);
                    tables.put(tableName, errorMetadata);
                }
            }
        }

        loading = false;
    }

    /**
     * Throws {@link IllegalStateException} if the catalog has been closed.
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TableCatalog is closed");
        }
    }

    /**
     * Throws {@link IllegalStateException} if the catalog is still loading or has been closed.
     */
    private void ensureReady() {
        ensureOpen();
        if (loading) {
            throw new IllegalStateException("TableCatalog is still loading");
        }
    }

    /**
     * Registers a new table by creating its subdirectory and metadata file.
     *
     * @param name the table name; must not be null or empty
     * @param schema the table schema; must not be null
     * @return the metadata for the newly registered table
     * @throws IOException if the table already exists or the directory cannot be created
     */
    // @spec F05.R14,R15,R16,R17,R56,R67,R84,R87,R88 — register is the catalog write contract
    TableMetadata register(String name, JlsmSchema schema) throws IOException {
        ensureReady();
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        final Instant createdAt = Instant.now();
        final TableMetadata metadata = new TableMetadata(name, schema, createdAt,
                TableMetadata.TableState.READY);

        // Atomically claim the name before any I/O — prevents TOCTOU race where
        // two threads both pass a containsKey check, both create directories,
        // and the loser's cleanup deletes the winner's directory.
        final TableMetadata previous = tables.putIfAbsent(name, metadata);
        if (previous != null) {
            throw new IOException("Table already exists: " + name);
        }

        try {
            final Path tableDir = rootDir.resolve(name);
            Files.createDirectories(tableDir);
            writeMetadata(tableDir.resolve(METADATA_FILE), metadata);
        } catch (IOException e) {
            // Roll back the map entry on I/O failure
            tables.remove(name, metadata);
            // Clean up the orphan directory created by Files.createDirectories()
            try {
                final Path tableDir = rootDir.resolve(name);
                deleteDirectoryTree(tableDir);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        return metadata;
    }

    /**
     * Unregisters a table by removing its subdirectory and all contained files. Used only for
     * creation rollback (the table was never served) — for user-initiated drop, see
     * {@link #markDropped(String)}.
     *
     * @param name the table name; must not be null or empty
     * @throws IOException if the table does not exist or cannot be removed
     */
    void unregister(String name) throws IOException {
        ensureOpen();
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
     * Drops a table by atomically transitioning its persisted metadata to DROPPED state. The
     * metadata file is preserved as a tombstone; all other files under the table's subdirectory are
     * removed on a best-effort basis.
     *
     * <p>
     * On restart, the DROPPED tombstone tells the engine the table was explicitly dropped (vs.
     * never created), so {@link LocalEngine#getTable(String)} can distinguish the two.
     *
     * @param name the table name; must not be null or empty
     * @throws IOException if the table does not exist or the DROPPED-state write fails
     */
    // @spec F05.R26,R27 — persist DROPPED via atomic write-then-rename before any deletion
    void markDropped(String name) throws IOException {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        final TableMetadata existing = tables.get(name);
        if (existing == null) {
            throw new IOException("Table does not exist: " + name);
        }

        final TableMetadata tombstone = new TableMetadata(existing.name(), existing.schema(),
                existing.createdAt(), TableMetadata.TableState.DROPPED);

        final Path metaPath = rootDir.resolve(name).resolve(METADATA_FILE);
        writeMetadata(metaPath, tombstone);
        tables.put(name, tombstone);

        // @spec F05.R31 — best-effort cleanup; the tombstone must remain even if deletion fails
        final Path tableDir = rootDir.resolve(name);
        deleteDataFilesPreservingTombstone(tableDir, metaPath);
    }

    /**
     * Walks the table directory and deletes every file except the tombstone metadata. Swallows
     * IOExceptions individually so that a single un-deletable file does not prevent other files
     * from being cleaned up and does not propagate failure to the caller.
     */
    private static void deleteDataFilesPreservingTombstone(Path tableDir, Path tombstone) {
        if (!Files.exists(tableDir)) {
            return;
        }
        final List<Path> victims = new ArrayList<>();
        try (final var walker = Files.walk(tableDir)) {
            walker.forEach(p -> {
                if (!p.equals(tombstone) && !p.equals(tableDir)) {
                    victims.add(p);
                }
            });
        } catch (IOException ignored) {
            // walk itself failed — best-effort, nothing to clean up
            return;
        }
        // Delete deepest entries first so directories become empty before we try to remove them
        victims.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        for (final Path v : victims) {
            try {
                Files.deleteIfExists(v);
            } catch (IOException ignored) {
                // swallow per R31 — cleanup is best-effort
            }
        }
    }

    /**
     * Returns metadata for a specific table, or empty if not registered.
     *
     * @param name the table name; must not be null
     * @return an Optional containing the metadata, or empty if not found
     */
    Optional<TableMetadata> get(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(tables.get(name));
    }

    /**
     * Returns metadata for all known tables, including DROPPED / ERROR / LOADING. Used by callers
     * that need the full catalog view. External consumers should prefer {@link #listReady()}.
     *
     * @return an unmodifiable collection of table metadata; never null
     */
    Collection<TableMetadata> list() {
        ensureOpen();
        return Collections.unmodifiableCollection(tables.values());
    }

    /**
     * Returns an independent snapshot of tables whose state is
     * {@link TableMetadata.TableState#READY}. The returned collection does not reflect subsequent
     * catalog mutations.
     *
     * @return an unmodifiable snapshot of READY tables; never null
     */
    // @spec F05.R20 — READY-only snapshot copy (not a live view)
    Collection<TableMetadata> listReady() {
        ensureOpen();
        final List<TableMetadata> snapshot = new ArrayList<>();
        for (final TableMetadata m : tables.values()) {
            if (m.state() == TableMetadata.TableState.READY) {
                snapshot.add(m);
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Returns the directory path for a given table.
     *
     * @param name the table name; must not be null
     * @return the table's subdirectory path; never null
     */
    Path tableDirectory(String name) {
        ensureOpen();
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

    // ---- Field type discriminator tags for binary serialization ----
    private static final byte TYPE_PRIMITIVE = 0;
    private static final byte TYPE_ARRAY = 1;
    private static final byte TYPE_OBJECT = 2;
    private static final byte TYPE_VECTOR = 3;
    private static final byte TYPE_BOUNDED_STRING = 4;

    /**
     * Writes table metadata to a binary file atomically via write-then-rename. Format: magic (4
     * bytes) | schema-name (UTF) | schema-version (int) | field-count (int) | field-definitions
     * (variable) | created-at-millis (long) | state-ordinal (int).
     *
     * <p>
     * A sibling temp file is written fully, then moved into place with ATOMIC_MOVE +
     * REPLACE_EXISTING. Falls back to a non-atomic REPLACE_EXISTING move on filesystems where
     * atomic rename is not supported.
     */
    // @spec F05.R54 — write-then-rename atomic metadata write
    private static void writeMetadata(Path path, TableMetadata metadata) throws IOException {
        assert metadata != null : "metadata must not be null";
        final Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (final DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
            out.writeInt(MAGIC);
            out.writeUTF(metadata.schema().name());
            out.writeInt(metadata.schema().version());
            out.writeInt(metadata.schema().fields().size());
            for (final FieldDefinition field : metadata.schema().fields()) {
                out.writeUTF(field.name());
                writeFieldType(out, field.type());
            }
            out.writeLong(metadata.createdAt().toEpochMilli());
            out.writeInt(metadata.state().ordinal());
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Surface the rename failure but try to clean up the temp file to avoid clutter
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static void writeFieldType(DataOutputStream out, FieldType type) throws IOException {
        switch (type) {
            case FieldType.Primitive p -> {
                out.writeByte(TYPE_PRIMITIVE);
                out.writeInt(p.ordinal());
            }
            case FieldType.ArrayType a -> {
                out.writeByte(TYPE_ARRAY);
                writeFieldType(out, a.elementType());
            }
            case FieldType.ObjectType o -> {
                out.writeByte(TYPE_OBJECT);
                out.writeInt(o.fields().size());
                for (final FieldDefinition nested : o.fields()) {
                    out.writeUTF(nested.name());
                    writeFieldType(out, nested.type());
                }
            }
            case FieldType.VectorType v -> {
                out.writeByte(TYPE_VECTOR);
                out.writeInt(v.elementType().ordinal());
                out.writeInt(v.dimensions());
            }
            case FieldType.BoundedString b -> {
                out.writeByte(TYPE_BOUNDED_STRING);
                out.writeInt(b.maxLength());
            }
        }
    }

    /**
     * Reads table metadata from a binary file, reconstructing the full schema including field
     * definitions and persisted table state.
     */
    // @spec F05.R55,R84 — read full schema + state from a single per-table metadata file
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
            final int fieldCount = in.readInt();

            // Reconstruct field definitions from persisted data
            final JlsmSchema.Builder builder = JlsmSchema.builder(schemaName, schemaVersion);
            for (int i = 0; i < fieldCount; i++) {
                final String fieldName = in.readUTF();
                final FieldType fieldType = readFieldType(in);
                builder.field(fieldName, fieldType);
            }

            final long createdAtMillis = in.readLong();
            final int stateOrdinal = in.readInt();
            final TableMetadata.TableState[] states = TableMetadata.TableState.values();
            if (stateOrdinal < 0 || stateOrdinal >= states.length) {
                throw new IOException("Invalid table state ordinal: " + stateOrdinal);
            }
            final JlsmSchema schema = builder.build();
            final Instant createdAt = Instant.ofEpochMilli(createdAtMillis);

            return new TableMetadata(tableName, schema, createdAt, states[stateOrdinal]);
        }
    }

    private static FieldType readFieldType(DataInputStream in) throws IOException {
        final byte tag = in.readByte();
        return switch (tag) {
            case TYPE_PRIMITIVE -> {
                final int ordinal = in.readInt();
                final FieldType.Primitive[] values = FieldType.Primitive.values();
                if (ordinal < 0 || ordinal >= values.length) {
                    throw new IOException("Invalid primitive type ordinal: " + ordinal);
                }
                yield values[ordinal];
            }
            case TYPE_ARRAY -> {
                final FieldType elementType = readFieldType(in);
                yield new FieldType.ArrayType(elementType);
            }
            case TYPE_OBJECT -> {
                final int nestedCount = in.readInt();
                final List<FieldDefinition> nestedFields = new ArrayList<>(nestedCount);
                for (int i = 0; i < nestedCount; i++) {
                    final String nestedName = in.readUTF();
                    final FieldType nestedType = readFieldType(in);
                    nestedFields.add(new FieldDefinition(nestedName, nestedType));
                }
                yield new FieldType.ObjectType(nestedFields);
            }
            case TYPE_VECTOR -> {
                final int elemOrdinal = in.readInt();
                final FieldType.Primitive[] values = FieldType.Primitive.values();
                if (elemOrdinal < 0 || elemOrdinal >= values.length) {
                    throw new IOException("Invalid vector element type ordinal: " + elemOrdinal);
                }
                final int dimensions = in.readInt();
                yield new FieldType.VectorType(values[elemOrdinal], dimensions);
            }
            case TYPE_BOUNDED_STRING -> {
                final int maxLength = in.readInt();
                yield new FieldType.BoundedString(maxLength);
            }
            default -> throw new IOException("Unknown field type tag: " + tag);
        };
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

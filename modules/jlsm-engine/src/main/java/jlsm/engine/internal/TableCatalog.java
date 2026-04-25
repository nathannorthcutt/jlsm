package jlsm.engine.internal;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.internal.IdentifierValidator;
import jlsm.engine.EncryptionMetadata;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    /**
     * Pre-encryption {@code table.meta} format — the original layout starting directly with
     * {@code MAGIC}. Inferred from the first byte of the file at load time.
     *
     * @spec sstable.footer-encryption-scope.R8
     */
    private static final int FORMAT_PRE_ENCRYPTION = 0;
    /**
     * Encryption-aware {@code table.meta} format — leading version byte {@code 0x02} followed by
     * the existing layout, then an {@code [hasEncryption:byte]} flag and (if true) the encryption
     * block.
     *
     * @spec sstable.footer-encryption-scope.R9a
     */
    private static final int FORMAT_ENCRYPTION_AWARE = 2;

    private final Path rootDir;
    private final ConcurrentHashMap<String, TableMetadata> tables = new ConcurrentHashMap<>();
    private volatile boolean loading = true;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * Persistent (tableName -> meta_format_version_highwater) index used as the R9a-mono cold-start
     * defence. Lazily-initialised on {@link #open()} so a missing index file at cold-start is
     * treated as "no tables exist".
     */
    private volatile CatalogIndex catalogIndex;

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
    // @spec engine.in-process-database-engine.R4,R5,R52,R53,R55,R57,R58,R59,R60,R85,R86 — startup
    // scan + recovery
    void open() throws IOException {
        if (!Files.exists(rootDir)) {
            Files.createDirectories(rootDir);
        }

        // R9a-mono — load (or initialise) the catalog index BEFORE scanning per-table dirs so
        // every load decision can consult the high-water.
        this.catalogIndex = new CatalogIndex(rootDir);

        // Track directories whose table.meta exists but whose in-memory catalog-index entry
        // is absent. These are deferred for a single re-read of the on-disk catalog-index
        // after the scan completes — closes the cross-process register-during-open race
        // (a peer JVM that persisted a setHighwater between this JVM's CatalogIndex
        // construction at line 102 and the directory scan would otherwise be silently
        // skipped). The R9a-mono cold-start defence is preserved: any directory whose entry
        // is still absent on the fresh re-read is still treated as "table does not exist".
        // @spec sstable.footer-encryption-scope.R9a-mono — cross-JVM race-resolution defence
        final List<Path> deferredUnindexed = new ArrayList<>();

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (final Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                final String tableName = entry.getFileName().toString();
                // Skip the catalog's reserved subdirectories (locks, etc.).
                if (tableName.startsWith("_")) {
                    continue;
                }
                final Path metadataPath = entry.resolve(METADATA_FILE);

                if (!Files.exists(metadataPath)) {
                    // Partial creation cleanup: directory exists but no metadata file
                    deleteDirectoryTree(entry);
                    continue;
                }

                // R9a-mono cold-start: a table.meta file with no catalog-index entry must NOT
                // be loaded. This guards the cold-start downgrade attack (an attacker drops an
                // older table.meta into a fresh-looking root after the index file was deleted).
                final Optional<Integer> indexVersion = catalogIndex.highwater(tableName);
                if (indexVersion.isEmpty()) {
                    // Defer until after the scan — re-read the on-disk index once and re-check
                    // before treating as "not-existent". Cross-process register-during-open
                    // race: a peer JVM may have persisted this entry between line 102 and now.
                    deferredUnindexed.add(entry);
                    continue;
                }

                loadEntry(entry, tableName, metadataPath, indexVersion.get());
            }
        }

        if (!deferredUnindexed.isEmpty()) {
            // Cross-process race-resolution: re-read on-disk catalog-index ONCE and re-check
            // each deferred directory. If a peer JVM's setHighwater committed during this
            // JVM's open(), the entry is now present and the table is loaded normally.
            // R9a-mono cold-start defence is preserved: an unindexed-on-fresh-read directory
            // is still treated as "table does not exist".
            this.catalogIndex = new CatalogIndex(rootDir);
            for (final Path entry : deferredUnindexed) {
                final String tableName = entry.getFileName().toString();
                final Path metadataPath = entry.resolve(METADATA_FILE);
                final Optional<Integer> indexVersion = catalogIndex.highwater(tableName);
                if (indexVersion.isEmpty()) {
                    continue; // still not in the index after fresh read → not-existent
                }
                loadEntry(entry, tableName, metadataPath, indexVersion.get());
            }
        }

        loading = false;
    }

    /**
     * Reads a table's metadata and publishes it into the {@code tables} map. Corrupt or unreadable
     * metadata is recorded as an ERROR-state entry rather than aborting the catalog open. Also runs
     * R10e partial-SSTable cleanup on the directory.
     */
    private void loadEntry(Path entry, String tableName, Path metadataPath, int indexVersion) {
        try {
            final TableMetadata metadata = readMetadata(tableName, metadataPath, indexVersion);
            tables.put(tableName, metadata);
        } catch (IOException e) {
            // Corrupt or unreadable metadata — preserve data, mark as ERROR
            final JlsmSchema errorSchema = JlsmSchema.builder(tableName, 0).build();
            final TableMetadata errorMetadata = new TableMetadata(tableName, errorSchema,
                    Instant.EPOCH, TableMetadata.TableState.ERROR);
            tables.put(tableName, errorMetadata);
        }
        // R10e — restart cleanup: any *.partial.<writerId> SSTable tmp file in this
        // table's directory belongs to a writer that crashed mid-finish; deleting them
        // is mandatory before any reader touches the directory.
        cleanupPartialSstFiles(entry);
    }

    /**
     * Deletes any {@code *.partial.<writerId>} files in {@code tableDir}. Writers are not
     * restart-resumable per R10e — surviving partial SSTable tmp files are uncommitted state and
     * must be removed before any reader touches the directory.
     *
     * @spec sstable.footer-encryption-scope.R10e
     */
    private static void cleanupPartialSstFiles(Path tableDir) {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(tableDir)) {
            for (final Path p : stream) {
                final String name = p.getFileName().toString();
                if (name.contains(".partial.")) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best-effort; surface via the table state on next mutation if needed.
                    }
                }
            }
        } catch (IOException ignored) {
            // Best-effort directory walk.
        }
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
    // @spec engine.in-process-database-engine.R14,R15,R16,R17,R56,R67,R84,R87,R88 — register is the
    // catalog write contract
    TableMetadata register(String name, JlsmSchema schema) throws IOException {
        return registerInternal(name, schema, Optional.empty());
    }

    /**
     * Registers a new encrypted table (R7). Persists encryption metadata to {@code table.meta} and
     * bumps the {@link CatalogIndex} high-water to the encryption-aware format version.
     *
     * @spec sstable.footer-encryption-scope.R7
     * @spec sstable.footer-encryption-scope.R8a
     * @spec sstable.footer-encryption-scope.R9a-mono
     */
    TableMetadata registerEncrypted(String name, JlsmSchema schema, EncryptionMetadata encryption)
            throws IOException {
        Objects.requireNonNull(encryption, "encryption must not be null");
        return registerInternal(name, schema, Optional.of(encryption));
    }

    private TableMetadata registerInternal(String name, JlsmSchema schema,
            Optional<EncryptionMetadata> encryption) throws IOException {
        ensureReady();
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(encryption, "encryption must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        final Instant createdAt = Instant.now();
        // Stage a LOADING placeholder to atomically claim the name (TOCTOU defence) without
        // exposing a fully-published READY entry whose on-disk state has not yet been written.
        // Concurrent readers may observe LOADING (and treat the table as not-yet-usable);
        // they must not observe READY before the directory and table.meta are durable on disk.
        // @spec engine.in-process-database-engine.R56,R57 — registered tables are fully formed
        final TableMetadata loadingMetadata = new TableMetadata(name, schema, createdAt,
                TableMetadata.TableState.LOADING, encryption);
        final TableMetadata readyMetadata = new TableMetadata(name, schema, createdAt,
                TableMetadata.TableState.READY, encryption);

        // Atomically claim the name before any I/O — prevents TOCTOU race where
        // two threads both pass a containsKey check, both create directories,
        // and the loser's cleanup deletes the winner's directory.
        final TableMetadata previous = tables.putIfAbsent(name, loadingMetadata);
        if (previous != null) {
            throw new IOException("Table already exists: " + name);
        }

        final int targetVersion = encryption.isPresent() ? FORMAT_ENCRYPTION_AWARE
                : FORMAT_PRE_ENCRYPTION;
        try {
            final Path tableDir = rootDir.resolve(name);
            Files.createDirectories(tableDir);
            writeMetadata(tableDir.resolve(METADATA_FILE), readyMetadata, targetVersion);
            // R9a-mono — bump the high-water atomically with the metadata write completing.
            catalogIndex.setHighwater(name, targetVersion);
            // Publish the READY entry only after disk state is durable. tables.replace ensures
            // we do not clobber any concurrent rollback-driven removal (CAS on the LOADING
            // placeholder we ourselves staged).
            final boolean published = tables.replace(name, loadingMetadata, readyMetadata);
            assert published : "LOADING placeholder for " + name + " was unexpectedly replaced";
        } catch (IOException e) {
            // Roll back the map entry on I/O failure
            tables.remove(name, loadingMetadata);
            // Clean up the orphan directory created by Files.createDirectories()
            try {
                final Path tableDir = rootDir.resolve(name);
                deleteDirectoryTree(tableDir);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            // Roll back the index entry too — the table never existed.
            try {
                catalogIndex.remove(name);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        return readyMetadata;
    }

    /**
     * Atomically transitions {@code table.meta} for an existing plaintext table to the
     * encryption-aware format with {@code encryption} populated, bumping the {@link CatalogIndex}
     * high-water atomically with the metadata change. Implements R7b steps 4–5 of the
     * enableEncryption protocol.
     *
     * @spec sstable.footer-encryption-scope.R7b
     * @spec sstable.footer-encryption-scope.R11a
     * @spec sstable.footer-encryption-scope.R9a-mono
     */
    void updateEncryption(String name, EncryptionMetadata encryption) throws IOException {
        ensureReady();
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(encryption, "encryption must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        final TableMetadata existing = tables.get(name);
        if (existing == null) {
            throw new IOException("Table does not exist: " + name);
        }
        final TableMetadata next = new TableMetadata(existing.name(), existing.schema(),
                existing.createdAt(), existing.state(), Optional.of(encryption));
        final Path metaPath = rootDir.resolve(name).resolve(METADATA_FILE);
        // R11a — write-temp + rename is atomic; readers see either the prior or the new metadata.
        writeMetadata(metaPath, next, FORMAT_ENCRYPTION_AWARE);
        // R7b atomicity — if the catalog-index high-water bump fails after the metadata file
        // has been atomically replaced on disk, the on-disk state would diverge from the
        // in-memory and catalog-index views. Restore the prior on-disk metadata so memory
        // and disk stay in sync; surface any rewrite failure as a suppressed exception on
        // the original IOException.
        try {
            catalogIndex.setHighwater(name, FORMAT_ENCRYPTION_AWARE);
        } catch (IOException | RuntimeException e) {
            final int priorFormatVersion = existing.encryption().isPresent()
                    ? FORMAT_ENCRYPTION_AWARE
                    : FORMAT_PRE_ENCRYPTION;
            try {
                writeMetadata(metaPath, existing, priorFormatVersion);
            } catch (IOException | RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
        tables.put(name, next);
    }

    /**
     * Returns the {@link CatalogIndex} format-version high-water for {@code name}, or
     * {@link Optional#empty()} if no entry exists.
     *
     * @spec sstable.footer-encryption-scope.R9a-mono
     */
    Optional<Integer> indexHighwater(String name) {
        Objects.requireNonNull(name, "name must not be null");
        ensureOpen();
        return catalogIndex.highwater(name);
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
        // R9a-mono — clear the index entry so the table is not loaded on next open.
        if (catalogIndex != null) {
            catalogIndex.remove(name);
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
    // @spec engine.in-process-database-engine.R26,R27 — persist DROPPED via atomic
    // write-then-rename before any deletion
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
                existing.createdAt(), TableMetadata.TableState.DROPPED, existing.encryption());

        final Path metaPath = rootDir.resolve(name).resolve(METADATA_FILE);
        final int targetVersion = existing.encryption().isPresent() ? FORMAT_ENCRYPTION_AWARE
                : FORMAT_PRE_ENCRYPTION;
        writeMetadata(metaPath, tombstone, targetVersion);
        tables.put(name, tombstone);

        // @spec engine.in-process-database-engine.R31 — best-effort cleanup; the tombstone must
        // remain even if deletion fails
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
    // @spec engine.in-process-database-engine.R20 — READY-only snapshot copy (not a live view)
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
    // @spec engine.in-process-database-engine.R54 — write-then-rename atomic metadata write
    // @spec sstable.footer-encryption-scope.R9 R9a R9b R11a — leading format-version byte +
    // optional encryption block; atomic publication via write-temp + rename.
    private static void writeMetadata(Path path, TableMetadata metadata, int formatVersion)
            throws IOException {
        assert metadata != null : "metadata must not be null";
        final Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (final DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
            // R9a — encryption-aware format prepends a single explicit format-version byte. For
            // backward-compat the pre-encryption format writes nothing extra at the head; the
            // loader infers FORMAT_PRE_ENCRYPTION when the first 4 bytes form the legacy MAGIC.
            if (formatVersion != FORMAT_PRE_ENCRYPTION) {
                out.writeByte(formatVersion);
            }
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
            // R9b — encryption-aware format trails with [hasEncryption:byte][encryption block].
            if (formatVersion == FORMAT_ENCRYPTION_AWARE) {
                if (metadata.encryption().isPresent()) {
                    out.writeByte(1);
                    writeEncryptionBlock(out, metadata.encryption().get());
                } else {
                    out.writeByte(0);
                }
            }
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
     *
     * @param indexHighwater the {@link CatalogIndex} high-water for this table, used to enforce the
     *            R9a-mono format-version monotonicity defence (a tampered downgrade rewrites
     *            table.meta to the pre-encryption format; the loader rejects it).
     * @spec sstable.footer-encryption-scope.R9 R9a R9b R9c R9a-mono R12
     */
    // @spec engine.in-process-database-engine.R55,R84 — read full schema + state from a single
    // per-table metadata file
    private static TableMetadata readMetadata(String tableName, Path path, int indexHighwater)
            throws IOException {
        assert tableName != null : "tableName must not be null";
        assert path != null : "path must not be null";
        final byte[] raw = Files.readAllBytes(path);
        if (raw.length < 4) {
            throw new IOException("metadata file is too short to be valid");
        }
        // R9a — detect the format version. The pre-encryption format starts directly with the
        // legacy MAGIC bytes (high byte 0x4A); the encryption-aware format prepends a small
        // version byte. Any other leading byte is unknown and must throw IOException.
        final int firstByte = raw[0] & 0xFF;
        final int formatVersion;
        final int payloadOffset;
        if (firstByte == 0x4A) {
            formatVersion = FORMAT_PRE_ENCRYPTION;
            payloadOffset = 0;
        } else if (firstByte == FORMAT_ENCRYPTION_AWARE) {
            formatVersion = FORMAT_ENCRYPTION_AWARE;
            payloadOffset = 1;
        } else {
            // R12 — do NOT include the offending byte value in the message.
            throw new IOException("unknown metadata format version (R12)");
        }
        // R9a-mono — reject any persisted format below the catalog index's high-water.
        if (formatVersion < indexHighwater) {
            throw new IOException(
                    "metadata format version downgrade rejected for table '" + tableName + "'");
        }
        try (final DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(raw, payloadOffset, raw.length - payloadOffset))) {
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

            // R9b/R9c — encryption block parsing. The loader must not silently degrade malformed
            // bytes to encryption=Optional.empty() — that would let a tampered file disable the
            // scope check.
            Optional<EncryptionMetadata> encryption = Optional.empty();
            if (formatVersion == FORMAT_ENCRYPTION_AWARE) {
                final int hasEncryption = in.readByte() & 0xFF;
                if (hasEncryption == 1) {
                    encryption = Optional.of(readEncryptionBlock(in));
                } else if (hasEncryption != 0) {
                    throw new IOException("encryption flag must be 0 or 1");
                }
                // R9c — reject orphan trailing bytes; a tampered length prefix could silently
                // truncate an identifier and leave plausibly-valid bytes after the block.
                if (in.available() > 0) {
                    throw new IOException(
                            "encryption-aware metadata file has trailing bytes after encryption block");
                }
            }

            return new TableMetadata(tableName, schema, createdAt, states[stateOrdinal],
                    encryption);
        }
    }

    /**
     * Encodes the encryption block using {@link IdentifierValidator}-validated UTF-8 identifier
     * triples. Layout: {@code [tenantLen:u16][tenant][domainLen:u16][domain][tableLen:u16][table]}.
     *
     * @spec sstable.footer-encryption-scope.R9b
     */
    private static void writeEncryptionBlock(DataOutputStream out, EncryptionMetadata encryption)
            throws IOException {
        final TableScope scope = encryption.scope();
        IdentifierValidator.validateForWrite(scope.tenantId().value(), "tenantId");
        IdentifierValidator.validateForWrite(scope.domainId().value(), "domainId");
        IdentifierValidator.validateForWrite(scope.tableId().value(), "tableId");
        final byte[] tenant = scope.tenantId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] domain = scope.domainId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] table = scope.tableId().value().getBytes(StandardCharsets.UTF_8);
        out.writeShort(tenant.length);
        out.write(tenant);
        out.writeShort(domain.length);
        out.write(domain);
        out.writeShort(table.length);
        out.write(table);
    }

    /**
     * Decodes the encryption block. Identifier rules from
     * {@link IdentifierValidator#validateForRead} are enforced; any violation raises
     * {@link IOException} without revealing the offending byte (R12).
     *
     * @spec sstable.footer-encryption-scope.R9b
     * @spec sstable.footer-encryption-scope.R9c
     * @spec sstable.footer-encryption-scope.R12
     */
    private static EncryptionMetadata readEncryptionBlock(DataInputStream in) throws IOException {
        final byte[] tenant = readPrefixedIdentifier(in, "tenantId");
        final byte[] domain = readPrefixedIdentifier(in, "domainId");
        final byte[] table = readPrefixedIdentifier(in, "tableId");
        return new EncryptionMetadata(
                new TableScope(new TenantId(new String(tenant, StandardCharsets.UTF_8)),
                        new DomainId(new String(domain, StandardCharsets.UTF_8)),
                        new TableId(new String(table, StandardCharsets.UTF_8))));
    }

    private static byte[] readPrefixedIdentifier(DataInputStream in, String fieldName)
            throws IOException {
        final int len = in.readUnsignedShort();
        if (len < 1) {
            throw new IOException("encryption block has zero-length " + fieldName);
        }
        final byte[] bytes = new byte[len];
        in.readFully(bytes);
        // R2e — defensive validation of well-formed UTF-8 + identifier rules without revealing
        // byte values.
        IdentifierValidator.validateForRead(bytes, fieldName);
        return bytes;
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

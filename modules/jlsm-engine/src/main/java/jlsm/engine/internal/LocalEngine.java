package jlsm.engine.internal;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.io.MemorySerializer;
import jlsm.core.memtable.MemTable;
import jlsm.core.model.Level;
import jlsm.core.tree.TypedLsmTree;
import jlsm.engine.AllocationTracking;
import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.HandleEvictedException;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.table.DocumentSerializer;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.StandardJlsmTable;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.TypedStandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Embedded (local filesystem) implementation of {@link Engine}.
 *
 * <p>
 * Manages a {@link TableCatalog} for metadata persistence and a {@link HandleTracker} for handle
 * lifecycle. Each table is backed by its own subdirectory containing WAL, SSTable, and metadata
 * files.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md},
 * {@code .decisions/table-catalog-persistence/adr.md}
 */
final class LocalEngine implements Engine {

    private final Path rootDirectory;
    private final TableCatalog catalog;
    private final HandleTracker handleTracker;
    private final long memTableFlushThresholdBytes;

    /** Live tables: tableName -> JlsmTable.StringKeyed. Lazily populated. */
    private final ConcurrentHashMap<String, JlsmTable.StringKeyed> liveTables = new ConcurrentHashMap<>();

    /** Per-table SSTable ID counters. */
    private final ConcurrentHashMap<String, AtomicLong> idCounters = new ConcurrentHashMap<>();

    /** Idempotent close guard. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private LocalEngine(Builder builder) throws IOException {
        this.rootDirectory = Objects.requireNonNull(builder.rootDirectory,
                "rootDirectory must not be null");
        this.memTableFlushThresholdBytes = builder.memTableFlushThresholdBytes;

        this.catalog = new TableCatalog(rootDirectory);
        this.catalog.open();

        this.handleTracker = HandleTracker.builder()
                .maxHandlesPerSourcePerTable(builder.maxHandlesPerSourcePerTable)
                .maxHandlesPerTable(builder.maxHandlesPerTable)
                .maxTotalHandles(builder.maxTotalHandles)
                .allocationTracking(builder.allocationTracking).build();
    }

    /**
     * Returns a new builder for constructing a LocalEngine.
     *
     * @return a new builder; never null
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Throws {@link IllegalStateException} if the engine has been closed.
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Engine is closed");
        }
    }

    @Override
    public Table createTable(String name, JlsmSchema schema) throws IOException {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        // Register in catalog (throws if already exists)
        final TableMetadata metadata = catalog.register(name, schema);

        // Atomically get or create the live table — computeIfAbsent ensures only one
        // thread creates the JlsmTable (WAL + tree) even if a concurrent getTable() races
        // with this createTable(). Using put() would unconditionally overwrite an entry
        // that getTable's computeIfAbsent already inserted, leaking the overwritten table.
        final JlsmTable.StringKeyed jlsmTable;
        try {
            jlsmTable = liveTables.computeIfAbsent(name, tableName -> {
                try {
                    return createJlsmTable(tableName, schema);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            // Rollback: unregister from catalog since table creation failed
            try {
                catalog.unregister(name);
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e.getCause();
        }

        // Register handle — rollback jlsmTable and catalog on failure
        // Use threadId() — unique per thread (including virtual threads). getName() is not
        // unique: virtual threads can share names, causing per-source limits to be shared.
        final String sourceId = String.valueOf(Thread.currentThread().threadId());
        final HandleRegistration registration;
        try {
            registration = handleTracker.register(name, sourceId);
        } catch (Exception e) {
            // Rollback: close the jlsmTable and remove from liveTables
            liveTables.remove(name);
            try {
                jlsmTable.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            // Rollback: unregister from catalog
            try {
                catalog.unregister(name);
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        return new LocalTable(jlsmTable, registration, handleTracker, metadata);
    }

    @Override
    public Table getTable(String name) throws IOException {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        final TableMetadata metadata = catalog.get(name)
                .orElseThrow(() -> new IOException("Table does not exist: " + name));

        // Atomically get or lazily create the live table — computeIfAbsent ensures
        // only one thread creates the JlsmTable (WAL + tree) per table name
        final JlsmTable.StringKeyed jlsmTable;
        try {
            jlsmTable = liveTables.computeIfAbsent(name, tableName -> {
                try {
                    return createJlsmTable(tableName, metadata.schema());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }

        // Register handle — use threadId() for unique per-thread identity
        final String sourceId = String.valueOf(Thread.currentThread().threadId());
        final HandleRegistration registration = handleTracker.register(name, sourceId);

        return new LocalTable(jlsmTable, registration, handleTracker, metadata);
    }

    @Override
    public void dropTable(String name) throws IOException {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }

        // Close the live table if it exists
        final JlsmTable.StringKeyed removed = liveTables.remove(name);
        if (removed != null) {
            removed.close();
        }

        // Invalidate all handles for this table
        handleTracker.invalidateTable(name, HandleEvictedException.Reason.TABLE_DROPPED);

        // Unregister from catalog (deletes directory; throws if not found)
        catalog.unregister(name);

        // Clean up ID counter
        idCounters.remove(name);
    }

    @Override
    public Collection<TableMetadata> listTables() {
        ensureOpen();
        return catalog.list();
    }

    @Override
    public TableMetadata tableMetadata(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name must not be null");
        return catalog.get(name).orElse(null);
    }

    @Override
    public EngineMetrics metrics() {
        ensureOpen();
        final EngineMetrics snapshot = handleTracker.snapshot();
        // Override tableCount with actual catalog count
        final int catalogTableCount = catalog.list().size();
        return new EngineMetrics(catalogTableCount, snapshot.totalOpenHandles(),
                snapshot.handlesPerTable(), snapshot.handlesPerSourcePerTable());
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed — idempotent
        }

        // Invalidate all handles
        handleTracker.invalidateAll(HandleEvictedException.Reason.ENGINE_SHUTDOWN);

        // Close all live tables, accumulating exceptions
        final List<Exception> errors = new ArrayList<>();
        for (final var entry : liveTables.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        liveTables.clear();
        idCounters.clear();

        // Close catalog
        try {
            catalog.close();
        } catch (Exception e) {
            errors.add(e);
        }

        // Close handle tracker
        try {
            handleTracker.close();
        } catch (Exception e) {
            errors.add(e);
        }

        if (!errors.isEmpty()) {
            final IOException primary = errors.getFirst() instanceof IOException io ? io
                    : new IOException("Engine close failed", errors.getFirst());
            for (int i = 1; i < errors.size(); i++) {
                primary.addSuppressed(errors.get(i));
            }
            throw primary;
        }
    }

    // ---- Private helpers ----

    /**
     * Creates the full LSM stack for a named table.
     */
    private JlsmTable.StringKeyed createJlsmTable(String tableName, JlsmSchema schema)
            throws IOException {
        assert tableName != null : "tableName must not be null";
        assert schema != null : "schema must not be null";

        final Path tableDir = catalog.tableDirectory(tableName);

        // WAL — must be closed if subsequent builds fail
        final var wal = LocalWriteAheadLog.builder().directory(tableDir).build();

        final TypedLsmTree.StringKeyed<JlsmDocument> tree;
        try {
            // MemTable factory
            final Supplier<MemTable> memTableFactory = ConcurrentSkipListMemTable::new;

            // SSTable writer/reader factories
            final SSTableWriterFactory writerFactory = TrieSSTableWriter::new;
            final SSTableReaderFactory readerFactory = path -> TrieSSTableReader.open(path,
                    BlockedBloomFilter.deserializer());

            // Per-table ID counter
            final AtomicLong idCounter = idCounters.computeIfAbsent(tableName,
                    k -> new AtomicLong(0));

            // Path function for SSTables within the table directory
            final BiFunction<Long, Level, Path> pathFn = (id, level) -> tableDir
                    .resolve("sst-" + id + "-L" + level.index() + ".sst");

            // Document serializer
            final MemorySerializer<JlsmDocument> codec = DocumentSerializer.forSchema(schema);

            // Build the typed tree
            tree = TypedStandardLsmTree.<JlsmDocument>stringKeyedBuilder().wal(wal)
                    .memTableFactory(memTableFactory).sstableWriterFactory(writerFactory)
                    .sstableReaderFactory(readerFactory).idSupplier(idCounter::getAndIncrement)
                    .pathFn(pathFn).memTableFlushThresholdBytes(memTableFlushThresholdBytes)
                    .valueSerializer(codec).build();
        } catch (Exception e) {
            try {
                wal.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e instanceof IOException io ? io : new IOException("Failed to build table", e);
        }

        // Build the JlsmTable.StringKeyed — close tree on failure
        try {
            return StandardJlsmTable.stringKeyedBuilder().lsmTree(tree).schema(schema).build();
        } catch (Exception e) {
            try {
                tree.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e instanceof IOException io ? io : new IOException("Failed to build table", e);
        }
    }

    /**
     * Builder for {@link LocalEngine}.
     */
    static final class Builder {

        private Path rootDirectory;
        private int maxHandlesPerSourcePerTable = 16;
        private int maxHandlesPerTable = 64;
        private int maxTotalHandles = 1024;
        private AllocationTracking allocationTracking = AllocationTracking.OFF;
        private long memTableFlushThresholdBytes = 64L * 1024 * 1024;

        private Builder() {
        }

        Builder rootDirectory(Path rootDirectory) {
            this.rootDirectory = Objects.requireNonNull(rootDirectory,
                    "rootDirectory must not be null");
            return this;
        }

        Builder maxHandlesPerSourcePerTable(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException(
                        "maxHandlesPerSourcePerTable must be positive: " + max);
            }
            assert max > 0 : "maxHandlesPerSourcePerTable must be positive";
            this.maxHandlesPerSourcePerTable = max;
            return this;
        }

        Builder maxHandlesPerTable(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxHandlesPerTable must be positive: " + max);
            }
            assert max > 0 : "maxHandlesPerTable must be positive";
            this.maxHandlesPerTable = max;
            return this;
        }

        Builder maxTotalHandles(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxTotalHandles must be positive: " + max);
            }
            assert max > 0 : "maxTotalHandles must be positive";
            this.maxTotalHandles = max;
            return this;
        }

        Builder allocationTracking(AllocationTracking tracking) {
            this.allocationTracking = Objects.requireNonNull(tracking, "tracking must not be null");
            return this;
        }

        Builder memTableFlushThresholdBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException(
                        "memTableFlushThresholdBytes must be positive: " + bytes);
            }
            assert bytes > 0 : "memTableFlushThresholdBytes must be positive";
            this.memTableFlushThresholdBytes = bytes;
            return this;
        }

        LocalEngine build() throws IOException {
            if (rootDirectory == null) {
                throw new IllegalStateException("rootDirectory must be set");
            }
            if (maxHandlesPerSourcePerTable > maxHandlesPerTable) {
                throw new IllegalArgumentException("maxHandlesPerSourcePerTable ("
                        + maxHandlesPerSourcePerTable + ") must not exceed maxHandlesPerTable ("
                        + maxHandlesPerTable + ")");
            }
            if (maxHandlesPerTable > maxTotalHandles) {
                throw new IllegalArgumentException("maxHandlesPerTable (" + maxHandlesPerTable
                        + ") must not exceed maxTotalHandles (" + maxTotalHandles + ")");
            }
            return new LocalEngine(this);
        }
    }
}

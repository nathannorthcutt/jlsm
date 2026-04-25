package jlsm.engine.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import jlsm.encryption.TableScope;
import jlsm.engine.EncryptionMetadata;
import jlsm.engine.TableMetadata;
import jlsm.sstable.WriterCommitHook;

/**
 * Engine-side {@link WriterCommitHook} implementation that bridges the jlsm-core
 * {@link jlsm.sstable.TrieSSTableWriter} R10c finish protocol to the engine's per-table
 * {@link CatalogLock} and {@link TableCatalog}. The hook supplies:
 *
 * <ul>
 * <li>Step 2 — exclusive per-table lease via {@link CatalogLock#acquire(String)}.</li>
 * <li>Step 3 — fresh catalog read of the table's encryption scope via {@link TableCatalog#get}
 * performed under the held lease (no caching).</li>
 * </ul>
 *
 * <p>
 * Without this wiring the writer's {@code finish()} would commit using a construction-time snapshot
 * and could persist a plaintext v5 SSTable into an encrypted table that flipped mid-write.
 *
 * @spec sstable.footer-encryption-scope.R10c
 */
final class EngineWriterCommitHook implements WriterCommitHook {

    private final CatalogLock catalogLock;
    private final TableCatalog catalog;

    EngineWriterCommitHook(CatalogLock catalogLock, TableCatalog catalog) {
        this.catalogLock = Objects.requireNonNull(catalogLock, "catalogLock must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    @Override
    // @spec sstable.footer-encryption-scope.R10c
    public Lease acquire(String tableName) throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        final CatalogLock.Handle handle = catalogLock.acquire(tableName);
        return new EngineLease(handle, tableName, catalog);
    }

    /**
     * Lease wrapping a {@link CatalogLock.Handle}. Each call to {@link #freshScope()} re-reads the
     * catalog under the held lock so the writer observes any encryption transition that committed
     * between writer construction and finish().
     */
    private static final class EngineLease implements Lease {

        private final CatalogLock.Handle handle;
        private final String tableName;
        private final TableCatalog catalog;

        EngineLease(CatalogLock.Handle handle, String tableName, TableCatalog catalog) {
            this.handle = handle;
            this.tableName = tableName;
            this.catalog = catalog;
        }

        @Override
        // @spec sstable.footer-encryption-scope.R10c — fresh catalog read; no caching.
        public Optional<TableScope> freshScope() {
            final Optional<TableMetadata> metadata = catalog.get(tableName);
            return metadata.flatMap(TableMetadata::encryption).map(EncryptionMetadata::scope);
        }

        @Override
        public void close() {
            handle.close();
        }
    }
}

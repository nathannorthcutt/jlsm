package jlsm.sstable;

import java.io.IOException;
import java.util.Optional;

import jlsm.encryption.TableScope;

/**
 * SPI plugged into {@link TrieSSTableWriter} so the writer can perform the R10c finish-protocol
 * exclusive-lock acquire + fresh catalog read across the jlsm-core / jlsm-engine module boundary
 * without jlsm-core taking a dependency on jlsm-engine.
 *
 * <p>
 * The engine layer (jlsm-engine) supplies an implementation that wraps the catalog's per-table
 * exclusive lock and exposes a fresh-catalog-read accessor returning the current encryption scope.
 * The writer's {@code finish()} acquires the lease (R10c step 2), reads the fresh scope (R10c step
 * 3), compares to construction-time, and either commits under the held lease (steps 6-7) or
 * transitions to FAILED (step 5).
 *
 * <p>
 * Receives: the table name to lock against (constructor of the lease).<br>
 * Returns: a {@link Lease} whose {@link Lease#close()} releases the lock.<br>
 * Side effects: per-table exclusive lock acquisition.<br>
 * Error conditions: {@link IOException} on lock-file I/O or unrecoverable stale-holder state.<br>
 * Shared state: the underlying catalog lock files.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R10c.
 *
 * @spec sstable.footer-encryption-scope.R10c
 */
public interface WriterCommitHook {

    /**
     * Acquires the exclusive commit lease for {@code tableName}.
     *
     * @param tableName non-null logical table name
     * @return non-null {@link Lease}
     * @throws IOException on lock-file I/O failure
     * @throws NullPointerException if {@code tableName} is null
     */
    Lease acquire(String tableName) throws IOException;

    /**
     * Lease held while the writer commits under the catalog's exclusive lock. Exposes the
     * fresh-read encryption scope of the owning table for the R10c step 4 compare.
     */
    interface Lease extends AutoCloseable {

        /**
         * Returns the freshly-read encryption scope for the owning table, or
         * {@link Optional#empty()} if the table is currently plaintext. The implementation must NOT
         * serve a cached construction-time value — R10c requires a fresh catalog read under the
         * held lease.
         *
         * @return the freshly-read scope; never null
         * @spec sstable.footer-encryption-scope.R10c
         */
        Optional<TableScope> freshScope();

        /**
         * Releases the lease. Idempotent. Must not throw checked exceptions.
         */
        @Override
        void close();
    }
}

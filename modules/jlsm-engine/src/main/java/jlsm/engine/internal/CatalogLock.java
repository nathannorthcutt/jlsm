package jlsm.engine.internal;

import java.io.IOException;

/**
 * Per-table exclusive lock SPI shared by both {@code Engine.enableEncryption} (R7b step 1 catalog
 * mutation) and the SSTable writer finish protocol (R10c step 2 atomic publish). The same lock
 * instance is acquired in both paths so a writer rename never races with a catalog mutation that
 * flips the format-version high-water of the same table.
 *
 * <p>
 * Lock-holder liveness is recovered via a PID + bounded reclaim window or filesystem lease
 * (file-lock) so a crashed holder cannot wedge the catalog indefinitely.
 *
 * <p>
 * Receives: a non-null table name.<br>
 * Returns: a {@link Handle} whose {@link Handle#close()} releases the lock; close is
 * idempotent.<br>
 * Side effects: filesystem lock-file creation / acquisition.<br>
 * Error conditions: {@link IOException} on lock-file I/O failure or unrecoverable stale-holder
 * state.<br>
 * Shared state: per-table lock files under the catalog root.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R7b step 1, R10c step 2, R11b,
 * lock-holder liveness recovery.
 *
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R10c
 * @spec sstable.footer-encryption-scope.R11b
 */
interface CatalogLock {

    /**
     * Closeable handle representing exclusive ownership of a table's catalog lock. {@link #close()}
     * is required to be idempotent and must not throw checked exceptions — release errors are
     * surfaced via the returned-by-acquire contract or logged, never re-raised through
     * {@code close}.
     */
    interface Handle extends AutoCloseable {

        /**
         * Releases the lock. Idempotent: a second invocation is a no-op.
         */
        @Override
        void close();
    }

    /**
     * Acquires the per-table exclusive lock, blocking up to an implementation-defined timeout if a
     * peer holds it. Reclaims a stale lock if the prior holder is no longer live (PID-based or
     * lease-based, per implementation).
     *
     * @param tableName non-null logical table name
     * @return non-null lock {@link Handle}
     * @throws IOException on lock-file I/O failure or unrecoverable stale-holder state
     * @throws NullPointerException if {@code tableName} is null
     * @spec sstable.footer-encryption-scope.R7b
     * @spec sstable.footer-encryption-scope.R10c
     */
    Handle acquire(String tableName) throws IOException;
}

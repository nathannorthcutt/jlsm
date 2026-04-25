package jlsm.engine.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package-private factory exposing the file-based {@link CatalogLock} implementation. The factory
 * exists so tests in the same package and the {@link LocalEngine} / {@link TableCatalog}
 * collaboration can both obtain a single canonical lock instance keyed by catalog root, while the
 * concrete implementation class remains an internal detail.
 *
 * <p>
 * The factory caches one {@link FileBasedCatalogLock} instance per catalog root so all callers
 * within the same JVM share the same in-memory mutex map (otherwise per-call new façades would skip
 * JVM-level mutual exclusion and rely solely on {@code FileChannel.tryLock}, which may not detect
 * intra-process recursion).
 *
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R10c
 * @spec sstable.footer-encryption-scope.R11b
 */
final class CatalogLockFactory {

    private static final ConcurrentHashMap<Path, FileBasedCatalogLock> CACHE = new ConcurrentHashMap<>();

    private CatalogLockFactory() {
    }

    /**
     * Returns the file-based catalog lock rooted at {@code rootDir}. All callers using the same
     * root share the same underlying lock instance (and therefore mutual exclusion).
     *
     * @param rootDir non-null catalog root directory (lock files live alongside table dirs)
     * @return non-null catalog lock
     */
    static CatalogLock fileBased(Path rootDir) {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        final Path canonical = rootDir.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(canonical, p -> {
            try {
                return new FileBasedCatalogLock(p);
            } catch (IOException e) {
                throw new RuntimeException("failed to initialise catalog lock under " + p, e);
            }
        });
    }

    /**
     * Test hook: drop a synthetic stale lock file claiming ownership by {@code pid}. Used by tests
     * to drive the R11b liveness-recovery code path.
     *
     * @param rootDir non-null catalog root directory
     * @param tableName non-null logical table name
     * @param pid PID to embed in the lock file
     * @throws IOException on I/O failure
     */
    static void writeStaleLockFile(Path rootDir, String tableName, int pid) throws IOException {
        FileBasedCatalogLock.writeStaleLockFile(rootDir, tableName, pid);
    }

    /**
     * Test-only hook: drop the cached lock for {@code rootDir} so a fresh open can rebuild
     * in-memory state (used by tests that wipe the catalog directory between runs).
     */
    static void invalidate(Path rootDir) {
        CACHE.remove(rootDir.toAbsolutePath().normalize());
    }
}

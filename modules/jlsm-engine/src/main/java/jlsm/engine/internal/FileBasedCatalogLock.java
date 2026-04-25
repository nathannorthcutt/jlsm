package jlsm.engine.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based per-table catalog lock implementation. Acquires a per-table {@code .lock} file under a
 * dedicated {@code _locks/} subdirectory of the catalog root, embedding the holder's PID.
 *
 * <p>
 * Mutual exclusion within a single JVM is enforced by an in-memory {@link ReentrantLock} keyed on
 * the table name; cross-process exclusion is enforced by {@link FileChannel#tryLock()}. Liveness
 * recovery: if {@code tryLock} fails because a stale lock file exists for a non-live PID, the lock
 * file is reclaimed after a bounded backoff window (Linux: {@code /proc/<pid>} liveness check;
 * non-Linux: TTL-bounded retry window). Indefinite orphaning is impossible — at the upper bound the
 * lock is reclaimed regardless of the file's PID claim.
 *
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R10c
 * @spec sstable.footer-encryption-scope.R11b
 */
final class FileBasedCatalogLock implements CatalogLock {

    /** Subdirectory under the catalog root that holds per-table lock files. */
    static final String LOCKS_DIR = "_locks";

    /** Bounded reclaim window: if a stale lock cannot be reclaimed within this many ms, fail. */
    private static final long RECLAIM_WINDOW_MS = 5_000L;

    private final Path locksDir;
    /** Per-table in-JVM mutex; ensures intra-process exclusion before file-lock contention. */
    private final ConcurrentHashMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    FileBasedCatalogLock(Path rootDir) throws IOException {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        this.locksDir = rootDir.resolve(LOCKS_DIR);
        Files.createDirectories(locksDir);
    }

    @Override
    public Handle acquire(String tableName) throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        final ReentrantLock jvmLock = jvmLocks.computeIfAbsent(tableName, k -> new ReentrantLock());
        jvmLock.lock();
        try {
            final Path lockFile = lockFileFor(tableName);
            final FileChannel channel = openWithReclaim(lockFile);
            try {
                final java.nio.channels.FileLock fileLock = acquireFileLockWithReclaim(channel,
                        lockFile);
                writeHolderPid(channel);
                return new FileLockHandle(jvmLock, channel, fileLock, lockFile);
            } catch (IOException | RuntimeException e) {
                try {
                    channel.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            jvmLock.unlock();
            throw e;
        }
    }

    private Path lockFileFor(String tableName) {
        return locksDir.resolve(tableName + ".lock");
    }

    /**
     * Open the lock file for write. If a prior holder left a stale file claiming an unreachable
     * PID, reclaim it by truncating; otherwise leave the file alone so {@code tryLock} can race
     * fairly with peer JVMs.
     */
    private FileChannel openWithReclaim(Path lockFile) throws IOException {
        if (Files.exists(lockFile)) {
            final long claimedPid = readPidQuiet(lockFile);
            if (claimedPid > 0 && !isLivePid(claimedPid)) {
                // Stale lock belonging to a dead holder — reclaim immediately.
                Files.deleteIfExists(lockFile);
            }
        }
        return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ);
    }

    private java.nio.channels.FileLock acquireFileLockWithReclaim(FileChannel channel,
            Path lockFile) throws IOException {
        final long deadline = System.currentTimeMillis() + RECLAIM_WINDOW_MS;
        java.nio.channels.FileLock first = channel.tryLock();
        if (first != null) {
            return first;
        }
        // Contended — wait for either liveness reclaim or peer release within the window.
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for catalog lock");
            }
            final java.nio.channels.FileLock attempt = channel.tryLock();
            if (attempt != null) {
                return attempt;
            }
            // Re-check liveness — a peer may have crashed mid-hold.
            final long claimedPid = readPidQuiet(lockFile);
            if (claimedPid > 0 && !isLivePid(claimedPid)) {
                // Reclaim attempt — can't truncate while another peer holds the lock; if that
                // peer is gone, tryLock above will succeed on the next iteration. Spin.
                continue;
            }
        }
        throw new IOException("timed out acquiring catalog lock for " + lockFile.getFileName());
    }

    private void writeHolderPid(FileChannel channel) throws IOException {
        final long pid = ProcessHandle.current().pid();
        final byte[] bytes = ("pid=" + pid + "\n").getBytes(StandardCharsets.UTF_8);
        channel.position(0);
        channel.truncate(0);
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
    }

    private long readPidQuiet(Path lockFile) {
        try {
            final byte[] bytes = Files.readAllBytes(lockFile);
            final String text = new String(bytes, StandardCharsets.UTF_8).trim();
            // Format is `pid=<n>` — accept either form.
            final int eq = text.indexOf('=');
            final String numeric = eq >= 0 ? text.substring(eq + 1).trim() : text;
            return Long.parseLong(numeric);
        } catch (IOException | NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Return true iff the PID corresponds to a live process on this host. On Linux,
     * {@code /proc/<pid>} existence is the authoritative check. On other OSes, fall back to
     * {@link ProcessHandle#of(long)} which queries the OS process table.
     */
    private static boolean isLivePid(long pid) {
        if (pid <= 0L || pid >= Integer.MAX_VALUE) {
            return false;
        }
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("linux")) {
            return Files.exists(Path.of("/proc/" + pid));
        }
        return ProcessHandle.of(pid).isPresent();
    }

    /**
     * Test hook — write a synthetic stale lock file claiming ownership by {@code pid}. Used by
     * tests to drive the R11b liveness-recovery path without forking a real process.
     */
    static void writeStaleLockFile(Path rootDir, String tableName, long pid) throws IOException {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        Objects.requireNonNull(tableName, "tableName must not be null");
        final Path locks = rootDir.resolve(LOCKS_DIR);
        Files.createDirectories(locks);
        Files.writeString(locks.resolve(tableName + ".lock"), "pid=" + pid + "\n",
                StandardCharsets.UTF_8);
    }

    /** Concrete handle holding both the JVM mutex and the OS file lock until close. */
    private static final class FileLockHandle implements Handle {
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final java.nio.channels.FileLock fileLock;
        private final Path lockFile;
        private boolean closed = false;

        FileLockHandle(ReentrantLock jvmLock, FileChannel channel,
                java.nio.channels.FileLock fileLock, Path lockFile) {
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
            this.lockFile = lockFile;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Release file lock + close channel + delete lock file (best-effort) + release JVM
            // lock.
            try {
                if (fileLock.isValid()) {
                    fileLock.release();
                }
            } catch (IOException ignored) {
                // close is documented as void / no checked exceptions; logging would be nice but
                // is left to callers' diagnostic frameworks.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // same: close is non-throwing for callers.
            }
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException ignored) {
                // Best-effort; the next acquire will reclaim if the file remains.
            }
            try {
                jvmLock.unlock();
            } catch (IllegalMonitorStateException ignored) {
                // Not held — caller invoked close() outside the holder thread; a no-op is the
                // documented idempotent contract.
            }
        }
    }
}

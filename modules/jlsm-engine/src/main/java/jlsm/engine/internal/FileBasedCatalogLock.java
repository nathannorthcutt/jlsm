package jlsm.engine.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * file is reclaimed after a bounded backoff window. PID liveness is determined via
 * {@link ProcessHandle#of(long)} which queries the OS process table portably across platforms,
 * including Linux containers/chroots without {@code /proc} mounted. Indefinite orphaning is
 * impossible — at the upper bound the lock is reclaimed regardless of the file's PID claim.
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

    /**
     * Bounded reclaim window in nanoseconds — derived from {@link #RECLAIM_WINDOW_MS}. The deadline
     * computation uses {@link System#nanoTime()} (monotonic) rather than
     * {@link System#currentTimeMillis()} (wall-clock) so NTP adjustments or admin clock steps
     * cannot extend or short-circuit the bounded R11b reclaim window.
     */
    private static final long RECLAIM_WINDOW_NS = RECLAIM_WINDOW_MS * 1_000_000L;

    private final Path locksDir;
    /**
     * Per-table in-JVM mutex; ensures intra-process exclusion before file-lock contention.
     *
     * <p>
     * Each entry holds a {@link ReentrantLock} plus a reference count tracking the number of
     * acquirers that have observed the entry. The reference count is incremented atomically inside
     * {@link ConcurrentHashMap#compute} during {@link #acquire} and decremented atomically during
     * {@link FileLockHandle#close}; when the count drops to zero the entry is removed from the map.
     * This bounds map growth to the live working set rather than the historical cardinality of
     * distinct table names ever passed to {@link #acquire} — a long-running JVM that creates and
     * drops many tables (per-tenant ephemeral tables, test harnesses) does not leak a permanent
     * {@code ReentrantLock} per name.
     */
    private final ConcurrentHashMap<String, RefCountedLock> jvmLocks = new ConcurrentHashMap<>();

    /**
     * Refcount-bearing wrapper around a {@link ReentrantLock}. The refcount is mutated only inside
     * {@link ConcurrentHashMap#compute} — i.e., under the bin lock for the table-name key — so the
     * acquire/release add-then-remove sequence is atomic with respect to other acquirers.
     */
    private static final class RefCountedLock {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    FileBasedCatalogLock(Path rootDir) throws IOException {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        this.locksDir = rootDir.resolve(LOCKS_DIR);
        Files.createDirectories(locksDir);
    }

    @Override
    public Handle acquire(String tableName) throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        // Reserve a refcounted entry atomically: either find-and-bump-refs or create-with-refs=1.
        // The refcount keeps the entry pinned in the map for the duration of this acquirer's work;
        // close() will decrement and remove when the count returns to zero. This bounds jvmLocks
        // to the live working set rather than the historical cardinality of distinct table names.
        final RefCountedLock entry = jvmLocks.compute(tableName, (k, existing) -> {
            final RefCountedLock e = (existing != null) ? existing : new RefCountedLock();
            e.refs++;
            return e;
        });
        final ReentrantLock jvmLock = entry.lock;
        jvmLock.lock();
        // The catalog lock is NOT re-entrant despite the per-table jvmLock being a ReentrantLock.
        // Acquiring a second OS file lock from this JVM on the same channel would surface
        // OverlappingFileLockException (an unchecked IllegalStateException subclass) — surprising
        // behaviour for callers. Detect intra-JVM re-entry up-front and reject it cleanly. We
        // unlock once to restore the prior hold count so the original holder's close() works, and
        // decrement the refcount so the bookkeeping reflects this acquirer leaving.
        if (jvmLock.getHoldCount() > 1) {
            jvmLock.unlock();
            releaseRefCount(tableName);
            throw new IllegalStateException(
                    "catalog lock for table '" + tableName + "' is not re-entrant — close the "
                            + "outstanding handle before re-acquiring");
        }
        try {
            final Path lockFile = lockFileFor(tableName);
            final FileChannel channel = openWithReclaim(lockFile);
            try {
                final java.nio.channels.FileLock fileLock = acquireFileLockWithReclaim(channel,
                        lockFile);
                writeHolderPid(channel);
                return new FileLockHandle(this, tableName, jvmLock, channel, fileLock);
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
            releaseRefCount(tableName);
            throw e;
        }
    }

    /**
     * Decrement the refcount for {@code tableName}; if it falls to zero, remove the entry from
     * {@link #jvmLocks}. Called from {@link FileLockHandle#close} and from any {@link #acquire}
     * failure path where this acquirer no longer needs the entry pinned. Atomic w.r.t. concurrent
     * acquirers because the mutation runs inside {@link ConcurrentHashMap#compute}.
     */
    private void releaseRefCount(String tableName) {
        jvmLocks.compute(tableName, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            existing.refs--;
            return existing.refs == 0 ? null : existing;
        });
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
        // Use System.nanoTime() (monotonic) — System.currentTimeMillis() is wall-clock and can
        // be stepped by NTP/admin, extending or short-circuiting the bounded R11b window.
        // Compare via `now - deadline < 0` per System.nanoTime javadoc, which is overflow-safe.
        final long deadline = System.nanoTime() + RECLAIM_WINDOW_NS;
        java.nio.channels.FileLock first = channel.tryLock();
        if (first != null) {
            return first;
        }
        // Contended — wait for either liveness reclaim or peer release within the window.
        while (System.nanoTime() - deadline < 0L) {
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
     * Return true iff the PID corresponds to a live process on this host. Uses
     * {@link ProcessHandle#of(long)} which queries the OS process table portably across platforms,
     * including Linux containers/chroots where {@code /proc} may not be mounted. The previous
     * implementation short-circuited on Linux with {@code Files.exists("/proc/<pid>")} — that check
     * returns {@code false} unconditionally when {@code /proc} is masked, causing
     * {@code openWithReclaim} to misclassify a live peer JVM as dead and unlink the still-valid
     * lock file. {@code ProcessHandle.of} consults the OS process table directly and avoids that
     * environment-sensitivity failure mode.
     */
    private static boolean isLivePid(long pid) {
        if (pid <= 0L || pid >= Integer.MAX_VALUE) {
            return false;
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
        private final FileBasedCatalogLock owner;
        private final String tableName;
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final java.nio.channels.FileLock fileLock;
        private boolean closed = false;

        FileLockHandle(FileBasedCatalogLock owner, String tableName, ReentrantLock jvmLock,
                FileChannel channel, java.nio.channels.FileLock fileLock) {
            this.owner = owner;
            this.tableName = tableName;
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            if (closed) {
                // Idempotent second close from the holder thread is a documented no-op. We
                // accept second-close from any thread once the resources are released —
                // there is nothing left to release and no invariant left to break.
                return;
            }
            // Reject non-holder close BEFORE releasing anything. If a thread that does not
            // hold the per-table jvmLock invokes the FIRST close(), we must NOT release the
            // OS file lock — doing so would silently break the invariant that JVM-level
            // mutual exclusion is released iff OS-level mutual exclusion is released, and
            // would leave the original holder thread's jvmLock orphaned (subsequent
            // acquire("t") calls in this JVM would block forever on the orphaned mutex
            // while peer JVMs see the OS lock as free). Surface the misuse explicitly.
            if (!jvmLock.isHeldByCurrentThread()) {
                throw new IllegalStateException(
                        "catalog lock handle closed by a thread that does not hold the "
                                + "per-table jvmLock — close() must be called on the same "
                                + "thread that called acquire()");
            }
            closed = true;
            // Release file lock + close channel + release JVM lock.
            //
            // We deliberately do NOT delete the lock file here. Unlinking the directory entry
            // *after* releasing the OS lock opens a TOCTOU window: a peer JVM could have opened
            // the still-existing file between our release and our unlink, taken its OS lock on
            // the same inode, and then we unlink the directory entry — leaving the peer's lock
            // pinned to a now-anonymous inode while a third JVM creates a fresh inode under the
            // same path and successfully locks *that*. Two peers would then believe they hold
            // the catalog lock simultaneously (R11b cross-JVM exclusion silently violated).
            // Leaving the file in place is harmless: openWithReclaim consumes the existing file
            // on the next acquire, and an actually-stale holder is reclaimed via the standard
            // PID-liveness path.
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
            jvmLock.unlock();
            // Decrement the per-table refcount and remove the entry from jvmLocks if no other
            // acquirer is currently holding or waiting on it. Bounds map growth to the live
            // working set rather than the historical cardinality of distinct table names.
            owner.releaseRefCount(tableName);
        }
    }
}

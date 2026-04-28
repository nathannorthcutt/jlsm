package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * Per-tenant durable monotonic counter of artifacts (SSTables + WAL segments) whose wrapping chain
 * depends on a given retired {@link KekRef}. Persisted alongside the shard. The
 * {@code (tenantId, kekRef)} → count map is updated atomically on SSTable creation,
 * compaction-completion, and WAL segment retirement.
 *
 * <p>
 * Implementation: one tiny file per {@code (tenantId, kekRef)} pair beneath {@code root} so each
 * counter mutation is its own atomic temp-write + rename. The file format is:
 * {@code [MAGIC 4][version 2][count 8][CRC32C 4]} (big-endian). 0600 perms (R70a).
 *
 * <p>
 * Used by:
 * <ul>
 * <li>{@link jlsm.encryption.spi.WalLivenessSource} — read path during R75c grace-period</li>
 * <li>The on-disk witness in R78e — a non-zero count blocks rekey advancement</li>
 * <li>{@link RetiredReferences#eligibleForGc} — a retired ref with non-zero count is not GC'd</li>
 * </ul>
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R75c, R78e, R33a).
 *
 * @spec encryption.primitives-lifecycle R75c
 * @spec encryption.primitives-lifecycle R78e
 */
public final class LivenessWitness {

    /** "LWFL" — Liveness Witness File. */
    private static final byte[] MAGIC = { 'L', 'W', 'F', 'L' };
    private static final short FORMAT_VERSION = 1;
    /** payload size = magic(4) + version(2) + count(8). CRC trailer adds 4. */
    private static final int PAYLOAD_LEN = 4 + 2 + 8;
    private static final int FILE_LEN = PAYLOAD_LEN + 4;

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet
            .of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;
    /** Per-counter lock so concurrent increments / decrements on the same key serialize. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private LivenessWitness(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Open the witness store rooted at {@code root}. Idempotent: existing counters are read on
     * demand; no eager scan is performed.
     */
    public static LivenessWitness open(Path root) {
        Objects.requireNonNull(root, "root");
        return new LivenessWitness(root);
    }

    /**
     * Atomically increment the count for {@code (tenantId, kekRef)} and return the new count.
     *
     * @throws NullPointerException if any argument is null
     * @throws IOException on durable-write failure
     */
    public long increment(TenantId tenantId, KekRef kekRef) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kekRef, "kekRef");
        return mutate(tenantId, kekRef, current -> current + 1);
    }

    /**
     * Atomically decrement and return the new count. Throws if the current count is zero.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if the count would go negative
     * @throws IOException on durable-write failure
     */
    public long decrement(TenantId tenantId, KekRef kekRef) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kekRef, "kekRef");
        return mutate(tenantId, kekRef, current -> {
            if (current <= 0L) {
                throw new IllegalStateException("liveness counter would go negative for tenant="
                        + tenantId + " kekRef=" + kekRef);
            }
            return current - 1;
        });
    }

    /** Read the current count without modifying it. */
    public long count(TenantId tenantId, KekRef kekRef) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kekRef, "kekRef");
        final Path file = counterFile(tenantId, kekRef);
        return readCount(file);
    }

    private long mutate(TenantId tenantId, KekRef kekRef, java.util.function.LongUnaryOperator op)
            throws IOException {
        final String key = lockKey(tenantId, kekRef);
        final ReentrantLock lock = locks.computeIfAbsent(key, _k -> new ReentrantLock());
        lock.lock();
        try {
            final Path file = counterFile(tenantId, kekRef);
            final long current = readCount(file);
            final long next = op.applyAsLong(current);
            writeAtomic(file, next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    private static long readCount(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0L;
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return 0L;
        }
        if (bytes.length == 0) {
            return 0L;
        }
        if (bytes.length != FILE_LEN) {
            throw new IOException(
                    "liveness counter file has unexpected length " + bytes.length + " at " + file);
        }
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, PAYLOAD_LEN);
        final int computed = (int) crc.getValue();
        final int stored = ByteBuffer.wrap(bytes, PAYLOAD_LEN, 4).order(ByteOrder.BIG_ENDIAN)
                .getInt();
        if (computed != stored) {
            throw new IOException("CRC-32C mismatch on liveness counter file " + file);
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes, 0, PAYLOAD_LEN).order(ByteOrder.BIG_ENDIAN);
        final byte[] magic = new byte[4];
        buf.get(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("magic mismatch on liveness counter file at " + file);
            }
        }
        final short version = buf.getShort();
        if (version != FORMAT_VERSION) {
            throw new IOException(
                    "unsupported liveness counter format version " + version + " at " + file);
        }
        final long count = buf.getLong();
        if (count < 0L) {
            throw new IOException("negative count " + count + " at " + file);
        }
        return count;
    }

    private static void writeAtomic(Path file, long count) throws IOException {
        final Path parent = file.getParent();
        assert parent != null : "counter file path must have parent";
        Files.createDirectories(parent);
        final boolean isPosix = isPosix(parent);

        // Build the on-disk payload + CRC.
        final ByteBuffer buf = ByteBuffer.allocate(FILE_LEN).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(FORMAT_VERSION);
        buf.putLong(count);
        // Compute CRC over the payload prefix (magic + version + count).
        final CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, PAYLOAD_LEN);
        buf.putInt((int) crc.getValue());
        final byte[] data = buf.array();

        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final Path tmp = parent.resolve(file.getFileName() + "." + suffix + ".tmp");
        if (isPosix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(tmp);
        }
        boolean committed = false;
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                final ByteBuffer outBuf = ByteBuffer.wrap(data);
                while (outBuf.hasRemaining()) {
                    ch.write(outBuf);
                }
                ch.force(true);
            }
            if (isPosix) {
                Files.setPosixFilePermissions(tmp, OWNER_ONLY);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            if (isPosix) {
                try (FileChannel dirCh = FileChannel.open(parent, StandardOpenOption.READ)) {
                    dirCh.force(true);
                }
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            }
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    private Path counterFile(TenantId tenantId, KekRef kekRef) {
        final String tenantDir = encodeIdentifier(tenantId.value());
        final String kekDir = encodeIdentifier(kekRef.value());
        return root.resolve("liveness").resolve(tenantDir).resolve(kekDir + ".bin");
    }

    /**
     * Stable per-counter key for serializing concurrent mutators on the same (tenant, kek) pair.
     */
    private static String lockKey(TenantId tenantId, KekRef kekRef) {
        return tenantId.value() + ' ' + kekRef.value();
    }

    /**
     * Encode an identifier so it is filesystem-safe and bounded by 64 chars. Uses base64-url of a
     * SHA-256 prefix to avoid collisions with structured identifiers (e.g., AWS ARNs).
     */
    private static String encodeIdentifier(String value) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isPosix(Path p) {
        return Files.getFileAttributeView(p, PosixFileAttributeView.class) != null;
    }
}

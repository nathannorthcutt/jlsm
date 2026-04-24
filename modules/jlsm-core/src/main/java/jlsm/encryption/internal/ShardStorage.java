package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Durable per-tenant shard storage. Each tenant's {@link KeyRegistryShard} is persisted to a single
 * file under {@code registryRoot}. Reads verify a CRC-32C trailer (R19a); writes go through a
 * temp-file + fsync + rename sequence (R20) so that a crash mid-write either preserves the previous
 * shard or leaves a recoverable temp file. Orphan temp files are swept by
 * {@link #recoverOrphanTemps} (R20a).
 *
 * <p>
 * POSIX permissions on both the temp file and the final shard are 0600 (owner read/write only, R70,
 * R70a). On non-POSIX filesystems (Windows) the implementation logs a warning and falls back to
 * best-effort ACL narrowing.
 *
 * <p>
 * <b>Binary format.</b> All multi-byte integers are big-endian. All length-prefixed strings are
 * UTF-8 with a 4-byte big-endian length. A single "null sentinel" length {@code 0xFFFFFFFF}
 * represents an absent optional string.
 *
 * <pre>
 * [MAGIC 4B "KRSH"]
 * [version 2B = 1]
 * [tenantId : length-prefixed UTF-8]
 * [hkdfSalt : 4B BE length + bytes]
 * [activeTenantKekRef : length-prefixed UTF-8, or 0xFFFFFFFF null sentinel]
 * [num domain KEKs 4B BE]
 *   for each domain KEK:
 *     [domainId : length-prefixed UTF-8]
 *     [version 4B BE]
 *     [wrappedBytes : 4B BE length + bytes]
 *     [tenantKekRef : length-prefixed UTF-8]
 * [num DEKs 4B BE]
 *   for each DEK:
 *     [tenantId : length-prefixed UTF-8]
 *     [domainId : length-prefixed UTF-8]
 *     [tableId  : length-prefixed UTF-8]
 *     [dekVersion 4B BE]
 *     [wrappedBytes : 4B BE length + bytes]
 *     [domainKekVersion 4B BE]
 *     [tenantKekRef : length-prefixed UTF-8]
 *     [createdAt epochMilli 8B BE]
 * [CRC-32C 4B BE  — covers all preceding bytes]
 * </pre>
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R19a, R20, R20a, R70, R70a.
 */
public final class ShardStorage {

    private static final System.Logger LOG = System.getLogger(ShardStorage.class.getName());

    /** 4-byte magic identifier: "KRSH" = Key Registry SHard. */
    private static final byte[] MAGIC = { 'K', 'R', 'S', 'H' };
    private static final short FORMAT_VERSION = 1;
    /** 0xFFFFFFFF as signed int — null sentinel for optional length-prefixed strings. */
    private static final int NULL_SENTINEL = -1;

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet
            .of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path registryRoot;

    /**
     * @throws NullPointerException if {@code registryRoot} is null
     */
    public ShardStorage(Path registryRoot) {
        this.registryRoot = Objects.requireNonNull(registryRoot, "registryRoot must not be null");
    }

    /**
     * Load a tenant's shard, verifying its CRC-32C trailer.
     *
     * @return empty if no shard file exists for the tenant
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on I/O error or CRC-32C mismatch
     */
    public Optional<KeyRegistryShard> loadShard(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenantId);
        if (!Files.exists(shardPath)) {
            return Optional.empty();
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(shardPath);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (bytes.length == 0) {
            // Empty shard file — treat as absent. A zero-byte file is not a valid shard.
            return Optional.empty();
        }
        return Optional.of(deserializeWithCrc(bytes, shardPath));
    }

    /**
     * Atomically persist {@code shard} for {@code tenantId}: temp write, fsync, POSIX 0600,
     * rename-over.
     *
     * @throws NullPointerException if either argument is null
     * @throws IOException on I/O failure
     */
    public void writeShard(TenantId tenantId, KeyRegistryShard shard) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(shard, "shard must not be null");
        if (!shard.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("shard.tenantId (" + shard.tenantId()
                    + ") does not match tenantId argument (" + tenantId + ")");
        }

        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenantId);
        Files.createDirectories(shardPath.getParent());

        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final Path tempPath = ShardPathResolver.tempPath(shardPath, suffix);
        final boolean isPosix = isPosix(tempPath);

        // Create the temp file with owner-only perms from the start.
        if (isPosix) {
            Files.createFile(tempPath, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(tempPath);
            narrowAclBestEffort(tempPath);
        }

        // Acquire the per-tenant sibling-lock BEFORE beginning the write. Orphan recovery
        // acquires the same lock across its tryLoad+Files.move window, so the writer's atomic
        // rename onto shardPath cannot interleave with a recovery promotion. This closes the
        // TOCTOU on shardPath (F-R1.concurrency.3.2). The per-temp advisory lock below is
        // preserved so the F-R1.concurrency.3.1 single-file-lock guarantee still holds for
        // external callers that probe an in-flight temp directly.
        boolean committed = false;
        try (FileChannel lockCh = openTenantLock(shardPath);
                java.nio.channels.FileLock tenantLock = lockCh.lock()) {
            assert tenantLock.isValid() : "tenant lock must be valid before write begins";
            final byte[] payload = serialize(shard);
            final byte[] withCrc = appendCrc32c(payload);
            // Hold an OS-level exclusive advisory lock across the entire write window. Orphan
            // recovery probes each temp file with tryLock; if the lock is held, recovery skips
            // the file as "in flight" (F-R1.concurrency.3.1). The channel is closed (releasing
            // the lock) only after fsync completes, just before Files.move.
            try (FileChannel ch = FileChannel.open(tempPath, StandardOpenOption.WRITE);
                    java.nio.channels.FileLock lock = ch.lock()) {
                assert lock.isValid() : "writer lock must be valid before payload write";
                final ByteBuffer buf = ByteBuffer.wrap(withCrc);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            // Re-assert perms on the temp file (some platforms may widen on write).
            if (isPosix) {
                Files.setPosixFilePermissions(tempPath, OWNER_ONLY);
            }
            // Atomic rename.
            Files.move(tempPath, shardPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            // Set perms on the final shard (rename preserves temp perms on most POSIX systems,
            // but make it explicit).
            if (isPosix) {
                Files.setPosixFilePermissions(shardPath, OWNER_ONLY);
            } else {
                narrowAclBestEffort(shardPath);
            }
            committed = true;
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Resolve the per-tenant sibling-lock path for {@code shardPath}. The lock file is empty and
     * exists solely as a rendezvous point for an OS-level advisory lock shared between writers and
     * orphan recovery so they serialize on the final shard path (F-R1.concurrency.3.2). The file
     * name ends with {@code .lock} (not {@code .tmp}) so the orphan-recovery scanner ignores it.
     */
    private static Path tenantLockPath(Path shardPath) {
        final Path fileName = shardPath.getFileName();
        assert fileName != null : "shardPath must have a file name";
        return shardPath.resolveSibling(fileName + ".lock");
    }

    /**
     * Open (creating if necessary) the per-tenant sibling-lock channel. The channel is opened with
     * {@code CREATE, WRITE} and owner-only perms where supported so the lock file cannot be used as
     * a privilege-escalation vector.
     */
    private static FileChannel openTenantLock(Path shardPath) throws IOException {
        final Path lockPath = tenantLockPath(shardPath);
        if (isPosix(lockPath)) {
            // Create with 0600 on POSIX systems on first open.
            if (!Files.exists(lockPath)) {
                try {
                    Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another concurrent caller won the race; safe to proceed.
                }
            }
        }
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /**
     * Recover orphan temp files left by a prior crashed write: verify CRC, compare against existing
     * shard, promote if strictly newer and valid, delete if invalid or older than existing shard
     * (R20a). "Strictly newer" is determined by comparing the maximum DEK version present in the
     * orphan vs the current shard (R20a explicitly: by shard contents, NOT file timestamps).
     *
     * @throws IOException on I/O failure
     */
    public void recoverOrphanTemps() throws IOException {
        final Path shardsRoot = registryRoot.resolve("shards");
        if (!Files.isDirectory(shardsRoot)) {
            return;
        }
        // Iterate all tenant directories.
        try (DirectoryStream<Path> tenantDirs = Files.newDirectoryStream(shardsRoot)) {
            for (Path tenantDir : tenantDirs) {
                if (!Files.isDirectory(tenantDir)) {
                    continue;
                }
                recoverOrphansInTenantDir(tenantDir);
            }
        }
    }

    private void recoverOrphansInTenantDir(Path tenantDir) throws IOException {
        final Path shardPath = tenantDir.resolve("shard.bin");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tenantDir,
                p -> p.getFileName().toString().endsWith(".tmp"))) {
            for (Path orphan : entries) {
                handleOrphan(orphan, shardPath);
            }
        }
    }

    /**
     * Probe a temp file with an OS-level exclusive advisory lock. If acquiring the lock fails
     * because another process/thread holds it, the file is the target of an in-flight
     * {@link #writeShard} and must not be touched (F-R1.concurrency.3.1). Returns {@code null} if
     * the file is in flight; otherwise returns an open {@link FileChannel} that the caller must
     * close. The returned channel holds the exclusive lock until closed, preventing a new writer
     * from racing us.
     */
    private static FileChannel acquireOrphanLockOrNull(Path orphan) throws IOException {
        FileChannel ch = null;
        try {
            ch = FileChannel.open(orphan, StandardOpenOption.READ, StandardOpenOption.WRITE);
            final java.nio.channels.FileLock lock = ch.tryLock();
            if (lock == null) {
                // Another process holds the lock — likely an in-flight writer.
                ch.close();
                return null;
            }
            final FileChannel locked = ch;
            ch = null; // prevent close-in-finally
            return locked;
        } catch (java.nio.channels.OverlappingFileLockException inFlightSameJvm) {
            // Same-JVM writer holds the lock on this path.
            return null;
        } catch (NoSuchFileException gone) {
            // File was removed out from under us (e.g., writer's failure-cleanup); nothing to
            // do.
            return null;
        } finally {
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    private void handleOrphan(Path orphan, Path shardPath) throws IOException {
        // Acquire an exclusive advisory lock before reading/mutating the orphan. If a
        // concurrent writeShard holds the lock, this returns null and we leave the temp alone
        // — touching it would race-delete an in-flight write (F-R1.concurrency.3.1). The lock
        // remains held until we close the channel in finally, which prevents a new writer
        // from starting on this exact path between our CRC check and our delete/move.
        final FileChannel guard = acquireOrphanLockOrNull(orphan);
        if (guard == null) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Skipping orphan — locked by in-flight writer: " + orphan);
            return;
        }
        try {
            // Additionally acquire the per-tenant sibling lock across the tryLoad+Files.move
            // window. writeShard holds the same lock across its own atomic-rename window, so
            // this blocks until any in-flight writer has committed (or returned). Without
            // this the existing-load-vs-move TOCTOU lets an older orphan stomp a
            // just-committed newer shard (F-R1.concurrency.3.2).
            try (FileChannel tenantLockCh = openTenantLock(shardPath);
                    java.nio.channels.FileLock tenantLock = tenantLockCh.lock()) {
                assert tenantLock.isValid() : "tenant lock must be valid before orphan promote";
                final byte[] bytes;
                try {
                    bytes = Files.readAllBytes(orphan);
                } catch (NoSuchFileException e) {
                    return;
                }
                final KeyRegistryShard candidate;
                try {
                    candidate = deserializeWithCrc(bytes, orphan);
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Deleting orphan with invalid CRC: " + orphan);
                    Files.deleteIfExists(orphan);
                    return;
                }
                // If final shard is missing or strictly older, promote; else delete.
                final Optional<KeyRegistryShard> existing = Files.exists(shardPath)
                        ? tryLoad(shardPath)
                        : Optional.empty();
                if (existing.isEmpty() || isStrictlyNewer(candidate, existing.get())) {
                    LOG.log(System.Logger.Level.DEBUG, "Promoting orphan shard: " + orphan);
                    Files.move(orphan, shardPath, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    if (isPosix(shardPath)) {
                        Files.setPosixFilePermissions(shardPath, OWNER_ONLY);
                    }
                } else {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Deleting orphan not newer than current shard: " + orphan);
                    Files.deleteIfExists(orphan);
                }
            }
        } finally {
            try {
                guard.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private Optional<KeyRegistryShard> tryLoad(Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserializeWithCrc(bytes, path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * "Strictly newer" := the candidate's maximum DEK version across all handles exceeds the
     * existing shard's maximum DEK version. If both are empty, the candidate is not newer. We also
     * consider domain KEK versions for completeness, since a domain rotation without a DEK rotation
     * should still be promoted.
     */
    private static boolean isStrictlyNewer(KeyRegistryShard candidate, KeyRegistryShard existing) {
        final int cDek = maxDekVersion(candidate);
        final int eDek = maxDekVersion(existing);
        if (cDek > eDek) {
            return true;
        }
        if (cDek < eDek) {
            return false;
        }
        final int cDomain = maxDomainKekVersion(candidate);
        final int eDomain = maxDomainKekVersion(existing);
        return cDomain > eDomain;
    }

    private static int maxDekVersion(KeyRegistryShard shard) {
        int max = 0;
        for (DekHandle h : shard.deks().keySet()) {
            max = Math.max(max, h.version().value());
        }
        return max;
    }

    private static int maxDomainKekVersion(KeyRegistryShard shard) {
        int max = 0;
        for (WrappedDomainKek dk : shard.domainKeks().values()) {
            max = Math.max(max, dk.version());
        }
        return max;
    }

    // --- serialization ---------------------------------------------------

    private static byte[] serialize(KeyRegistryShard shard) {
        // First pass: compute required capacity conservatively (2x the strings + sum of byte arrays
        // + headers). Then allocate and write.
        final int estimated = estimateSize(shard);
        final ByteBuffer buf = ByteBuffer.allocate(estimated).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(FORMAT_VERSION);
        putLpString(buf, shard.tenantId().value());
        putLpBytes(buf, shard.hkdfSalt());
        if (shard.activeTenantKekRef() == null) {
            buf.putInt(NULL_SENTINEL);
        } else {
            putLpString(buf, shard.activeTenantKekRef().value());
        }
        final Map<DomainId, WrappedDomainKek> dKeks = shard.domainKeks();
        buf.putInt(dKeks.size());
        for (Map.Entry<DomainId, WrappedDomainKek> e : dKeks.entrySet()) {
            final WrappedDomainKek dk = e.getValue();
            putLpString(buf, dk.domainId().value());
            buf.putInt(dk.version());
            putLpBytes(buf, dk.wrappedBytes());
            putLpString(buf, dk.tenantKekRef().value());
        }
        final Map<DekHandle, WrappedDek> deks = shard.deks();
        buf.putInt(deks.size());
        for (Map.Entry<DekHandle, WrappedDek> e : deks.entrySet()) {
            final WrappedDek d = e.getValue();
            putLpString(buf, d.handle().tenantId().value());
            putLpString(buf, d.handle().domainId().value());
            putLpString(buf, d.handle().tableId().value());
            buf.putInt(d.handle().version().value());
            putLpBytes(buf, d.wrappedBytes());
            buf.putInt(d.domainKekVersion());
            putLpString(buf, d.tenantKekRef().value());
            buf.putLong(d.createdAt().toEpochMilli());
        }
        buf.flip();
        final byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static int estimateSize(KeyRegistryShard shard) {
        // Conservative: 4 + 2 + 4 + len(tenantId)*4 + 4 + len(salt) + 4 + len(ref)*4 + 4 + ...
        int size = 4 + 2; // magic + version
        size += 4 + shard.tenantId().value().getBytes(StandardCharsets.UTF_8).length;
        size += 4 + shard.hkdfSalt().length;
        size += 4 + (shard.activeTenantKekRef() == null ? 0
                : shard.activeTenantKekRef().value().getBytes(StandardCharsets.UTF_8).length);
        size += 4; // numDomainKeks
        for (WrappedDomainKek dk : shard.domainKeks().values()) {
            size += 4 + dk.domainId().value().getBytes(StandardCharsets.UTF_8).length;
            size += 4; // version
            size += 4 + dk.wrappedBytes().length;
            size += 4 + dk.tenantKekRef().value().getBytes(StandardCharsets.UTF_8).length;
        }
        size += 4; // numDeks
        for (WrappedDek d : shard.deks().values()) {
            size += 4 + d.handle().tenantId().value().getBytes(StandardCharsets.UTF_8).length;
            size += 4 + d.handle().domainId().value().getBytes(StandardCharsets.UTF_8).length;
            size += 4 + d.handle().tableId().value().getBytes(StandardCharsets.UTF_8).length;
            size += 4; // dek version
            size += 4 + d.wrappedBytes().length;
            size += 4; // domainKekVersion
            size += 4 + d.tenantKekRef().value().getBytes(StandardCharsets.UTF_8).length;
            size += 8; // createdAt
        }
        size += 4; // CRC trailer
        // Add small slack to avoid resize corner cases.
        return size + 16;
    }

    private static void putLpString(ByteBuffer buf, String s) {
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.putInt(b.length);
        buf.put(b);
    }

    private static void putLpBytes(ByteBuffer buf, byte[] b) {
        buf.putInt(b.length);
        buf.put(b);
    }

    private static byte[] appendCrc32c(byte[] payload) {
        final CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        final int crcValue = (int) crc.getValue();
        final byte[] out = new byte[payload.length + 4];
        System.arraycopy(payload, 0, out, 0, payload.length);
        ByteBuffer.wrap(out, payload.length, 4).order(ByteOrder.BIG_ENDIAN).putInt(crcValue);
        return out;
    }

    private static KeyRegistryShard deserializeWithCrc(byte[] bytes, Path path) throws IOException {
        if (bytes.length < 4) {
            throw new IOException("shard file too short to contain CRC trailer: " + path);
        }
        // Split payload and CRC.
        final int payloadLen = bytes.length - 4;
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, payloadLen);
        final int computed = (int) crc.getValue();
        final int stored = ByteBuffer.wrap(bytes, payloadLen, 4).order(ByteOrder.BIG_ENDIAN)
                .getInt();
        if (computed != stored) {
            throw new IOException("CRC-32C mismatch on shard file (" + path + "): expected 0x"
                    + Integer.toHexString(stored) + ", computed 0x"
                    + Integer.toHexString(computed));
        }
        return deserialize(ByteBuffer.wrap(bytes, 0, payloadLen).order(ByteOrder.BIG_ENDIAN), path);
    }

    private static KeyRegistryShard deserialize(ByteBuffer buf, Path path) throws IOException {
        try {
            requireRemaining(buf, 4 + 2, path);
            final byte[] magic = new byte[4];
            buf.get(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("shard file magic mismatch at " + path);
                }
            }
            final short version = buf.getShort();
            if (version != FORMAT_VERSION) {
                throw new IOException(
                        "unsupported shard format version " + version + " at " + path);
            }
            final String tenantIdValue = readLpString(buf, path);
            final byte[] hkdfSalt = readLpBytes(buf, path);
            final int refLen = buf.getInt();
            final KekRef activeRef;
            if (refLen == NULL_SENTINEL) {
                activeRef = null;
            } else if (refLen < 0) {
                throw new IOException(
                        "invalid length-prefix for activeTenantKekRef: " + refLen + " at " + path);
            } else {
                requireRemaining(buf, refLen, path);
                final byte[] refBytes = new byte[refLen];
                buf.get(refBytes);
                activeRef = new KekRef(new String(refBytes, StandardCharsets.UTF_8));
            }
            final int numDomainKeks = buf.getInt();
            if (numDomainKeks < 0) {
                throw new IOException("invalid domain KEK count: " + numDomainKeks + " at " + path);
            }
            final Map<DomainId, WrappedDomainKek> dKeks = new HashMap<>(numDomainKeks);
            for (int i = 0; i < numDomainKeks; i++) {
                final DomainId did = new DomainId(readLpString(buf, path));
                final int dVersion = buf.getInt();
                final byte[] wrapped = readLpBytes(buf, path);
                final KekRef ref = new KekRef(readLpString(buf, path));
                dKeks.put(did, new WrappedDomainKek(did, dVersion, wrapped, ref));
            }
            final int numDeks = buf.getInt();
            if (numDeks < 0) {
                throw new IOException("invalid DEK count: " + numDeks + " at " + path);
            }
            final Map<DekHandle, WrappedDek> deks = new HashMap<>(numDeks);
            for (int i = 0; i < numDeks; i++) {
                final TenantId t = new TenantId(readLpString(buf, path));
                final DomainId d = new DomainId(readLpString(buf, path));
                final TableId tbl = new TableId(readLpString(buf, path));
                final DekVersion dv = new DekVersion(buf.getInt());
                final byte[] wrapped = readLpBytes(buf, path);
                final int domainKekVersion = buf.getInt();
                final KekRef ref = new KekRef(readLpString(buf, path));
                final Instant createdAt = Instant.ofEpochMilli(buf.getLong());
                final DekHandle handle = new DekHandle(t, d, tbl, dv);
                deks.put(handle, new WrappedDek(handle, wrapped, domainKekVersion, ref, createdAt));
            }
            return new KeyRegistryShard(new TenantId(tenantIdValue), deks, dKeks, activeRef,
                    hkdfSalt);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException
                | NullPointerException e) {
            throw new IOException("malformed shard file at " + path + ": " + e.getMessage(), e);
        }
    }

    private static String readLpString(ByteBuffer buf, Path path) throws IOException {
        final int len = buf.getInt();
        if (len < 0) {
            throw new IOException("negative length prefix: " + len + " at " + path);
        }
        requireRemaining(buf, len, path);
        final byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readLpBytes(ByteBuffer buf, Path path) throws IOException {
        final int len = buf.getInt();
        if (len < 0) {
            throw new IOException("negative length prefix: " + len + " at " + path);
        }
        requireRemaining(buf, len, path);
        final byte[] b = new byte[len];
        buf.get(b);
        return b;
    }

    private static void requireRemaining(ByteBuffer buf, int n, Path path) throws IOException {
        if (buf.remaining() < n) {
            throw new IOException("unexpected end of shard file at " + path + ": needed " + n
                    + " bytes, have " + buf.remaining());
        }
    }

    // --- FS helpers ------------------------------------------------------

    private static boolean isPosix(Path p) {
        // A POSIX-capable FS answers the PosixFileAttributeView. We query on the parent (always
        // exists by this point because writeShard calls createDirectories first) so the probe
        // works whether or not the file itself has been materialized yet.
        final Path probe = p.getParent() != null ? p.getParent() : p;
        return Files.getFileAttributeView(probe, PosixFileAttributeView.class) != null;
    }

    private static void narrowAclBestEffort(Path path) {
        // On non-POSIX filesystems (Windows), fall back to a narrowest-feasible approach.
        // R70a requires that we must not silently skip permission enforcement. We log at WARNING so
        // operators can see the deviation. The actual ACL narrowing on NTFS requires
        // AclFileAttributeView which varies by JDK distribution; we log and rely on the user's
        // choice of registryRoot being on a locked-down parent directory.
        LOG.log(System.Logger.Level.WARNING,
                "Non-POSIX filesystem: POSIX 0600 cannot be enforced on " + path
                        + ". Ensure the parent directory is ACL-restricted to the owning principal.");
    }

    Path registryRoot() {
        return registryRoot;
    }
}

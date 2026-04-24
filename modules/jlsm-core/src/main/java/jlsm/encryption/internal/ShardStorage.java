package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
 *     [createdAt epochSeconds 8B BE][createdAt nanosOfSecond 4B BE]
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

    /**
     * Minimum serialized bytes for a single domain-KEK entry. Used to bound reader-side HashMap
     * allocation against attacker-controlled count prefixes. A domain-KEK entry serializes as:
     * [domainId 4B len + &gt;=1B] + [version 4B] + [wrappedBytes 4B len + 0B] + [tenantKekRef 4B
     * len + &gt;=1B] = 18 bytes. Non-empty string values are enforced by the DomainId and KekRef
     * compact constructors.
     */
    private static final int MIN_DOMAIN_KEK_BYTES = 18;

    /**
     * Minimum serialized bytes for a single DEK entry. A DEK entry serializes as: [tenantId 4B len
     * + &gt;=1B] + [domainId 4B len + &gt;=1B] + [tableId 4B len + &gt;=1B] + [dekVersion 4B] +
     * [wrappedBytes 4B len + 0B] + [domainKekVersion 4B] + [tenantKekRef 4B len + &gt;=1B] +
     * [createdAt epochSeconds 8B + nanosOfSecond 4B] = 44 bytes. Non-empty string values are
     * enforced by the TenantId, DomainId, TableId, and KekRef compact constructors.
     */
    private static final int MIN_DEK_BYTES = 44;

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
            // Atomic rename — once this returns, the new shard is observable on disk at
            // shardPath and the previous shard (if any) is overwritten. Flip `committed`
            // immediately so the caller's view aligns with FS state: any post-rename
            // failure below must NOT retrigger the temp-cleanup branch (the temp was
            // renamed away — deleteIfExists is a no-op), and the caller must not observe
            // a misleading "failed" IOException for a write the filesystem has already
            // accepted (R20 caller-view-matches-FS-state; F-R1.resource_lifecycle.C2.5).
            Files.move(tempPath, shardPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            // Parent-directory fsync (R20 crash-durability; F-R1.shared_state.3.1).
            // ATOMIC_MOVE guarantees the rename is atomic with respect to other processes,
            // but the directory-entry change (`shard.bin.<uuid>.tmp` → `shard.bin`) is a
            // metadata update on the parent directory — not persisted to the on-disk
            // journal until the parent dentry is fsync'd. On POSIX filesystems without
            // metadata-ordering defaults (ext4 data=writeback, xfs without sync-metadata,
            // many network filesystems) a crash between the rename and the OS's
            // background metadata flush reverts the shard to its prior on-disk state,
            // violating the Javadoc's "atomically persist" postcondition. The caller has
            // already observed a successful return and may have triggered side-effects
            // (cluster announce, key-version claim). Open the parent directory for READ
            // and force(true) to persist the dentry change. POSIX permits opening a
            // directory as a FileChannel only in READ mode; force(true) includes metadata.
            // On non-POSIX platforms (Windows) directory-channel opens fail — we fall
            // back to a best-effort skip because the NTFS USN journal handles directory-
            // entry durability differently and the equivalent guarantee is already
            // provided by the filesystem driver.
            if (isPosix) {
                final Path parentDir = shardPath.getParent();
                assert parentDir != null : "shardPath must have a parent directory";
                try (FileChannel dirCh = FileChannel.open(parentDir, StandardOpenOption.READ)) {
                    dirCh.force(true);
                }
            }
            // Re-assert perms on the final shard (rename preserves temp perms on most
            // POSIX systems, but make it explicit). The temp file already carried
            // OWNER_ONLY perms from line 175 (POSIX) or best-effort ACL narrowing (non-
            // POSIX) before the rename, so this is a defense-in-depth re-assert. A
            // failure here still surfaces to the caller so they can retry or alert, but
            // `committed = true` is already set — the finally branch will NOT attempt
            // cleanup of a file that is already published at shardPath.
            if (isPosix) {
                Files.setPosixFilePermissions(shardPath, OWNER_ONLY);
            } else {
                narrowAclBestEffort(shardPath);
            }
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
     * should still be promoted. Finally, when both version pairs tie, a change in
     * {@code activeTenantKekRef} is itself a legitimate update (tier-1 KEK rotation without a
     * DEK/domain-KEK bump) and must also cause the candidate to be promoted — otherwise R20a's
     * "latest valid write wins" contract silently drops KEK-rotation writes that crash between
     * temp-write and rename (F-R1.contract_boundaries.3.4).
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
        if (cDomain > eDomain) {
            return true;
        }
        if (cDomain < eDomain) {
            return false;
        }
        // Both version pairs tie: a differing activeTenantKekRef indicates a tier-1 KEK
        // rotation that committed to the orphan without bumping any DEK or domain-KEK
        // version. Treat it as newer so orphan recovery promotes the write. Equal refs
        // (or both null) means the candidate is content-identical to existing — not newer.
        return !java.util.Objects.equals(candidate.activeTenantKekRef(),
                existing.activeTenantKekRef());
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

    private static byte[] serialize(KeyRegistryShard shard) throws IOException {
        // First pass: compute required capacity conservatively (2x the strings + sum of byte arrays
        // + headers). Then allocate and write.
        //
        // The accumulator is widened to long inside estimateSize so a shard carrying enough
        // DEKs (or sufficiently-large wrappedBytes) to overflow int does not silently wrap
        // to a negative value. We reject any shard whose serialized size cannot fit into an
        // `int` (the only size ByteBuffer.allocate accepts) with an IOException — honoring
        // writeShard's declared `throws IOException` contract rather than letting an
        // unchecked IllegalArgumentException from ByteBuffer.allocate(negative) leak past
        // callers (F-R1.contract_boundaries.3.3).
        final long estimated = estimateSize(shard);
        if (estimated > Integer.MAX_VALUE) {
            throw new IOException("shard serialized size " + estimated
                    + " bytes exceeds Integer.MAX_VALUE; a ByteBuffer cannot hold it. "
                    + "Split the tenant across multiple shards or reduce wrappedBytes payload.");
        }
        final ByteBuffer buf = ByteBuffer.allocate((int) estimated).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(FORMAT_VERSION);
        putLpString(buf, shard.tenantId().value());
        putLpBytes(buf, shard.hkdfSalt());
        if (shard.activeTenantKekRef() == null) {
            buf.putInt(NULL_SENTINEL);
        } else {
            putLpString(buf, shard.activeTenantKekRef().value());
        }
        // Emit in canonical lexicographic order so identical shards produce identical bytes
        // (and identical CRCs). Map.copyOf returns an ImmutableCollections.MapN whose
        // iteration order is hash-bucket probe order (salted per-JVM and insertion-order-
        // dependent); relying on it would violate R19b byte-for-byte fidelity and break
        // byte-diff tooling / content-addressed shard hashes. Sorting the entries here
        // pins the on-wire order to a pure function of the shard's logical content.
        final Map<DomainId, WrappedDomainKek> dKeks = shard.domainKeks();
        buf.putInt(dKeks.size());
        final java.util.List<Map.Entry<DomainId, WrappedDomainKek>> sortedDKeks = new java.util.ArrayList<>(
                dKeks.entrySet());
        sortedDKeks.sort(java.util.Comparator.comparing(e -> e.getKey().value()));
        for (Map.Entry<DomainId, WrappedDomainKek> e : sortedDKeks) {
            final WrappedDomainKek dk = e.getValue();
            putLpString(buf, dk.domainId().value());
            buf.putInt(dk.version());
            putLpBytes(buf, dk.wrappedBytes());
            putLpString(buf, dk.tenantKekRef().value());
        }
        final Map<DekHandle, WrappedDek> deks = shard.deks();
        buf.putInt(deks.size());
        final java.util.List<Map.Entry<DekHandle, WrappedDek>> sortedDeks = new java.util.ArrayList<>(
                deks.entrySet());
        sortedDeks.sort(java.util.Comparator.<Map.Entry<DekHandle, WrappedDek>, String>comparing(
                e -> e.getKey().tenantId().value())
                .thenComparing(e -> e.getKey().domainId().value())
                .thenComparing(e -> e.getKey().tableId().value())
                .thenComparingInt(e -> e.getKey().version().value()));
        for (Map.Entry<DekHandle, WrappedDek> e : sortedDeks) {
            final WrappedDek d = e.getValue();
            putLpString(buf, d.handle().tenantId().value());
            putLpString(buf, d.handle().domainId().value());
            putLpString(buf, d.handle().tableId().value());
            buf.putInt(d.handle().version().value());
            putLpBytes(buf, d.wrappedBytes());
            buf.putInt(d.domainKekVersion());
            putLpString(buf, d.tenantKekRef().value());
            // Persist createdAt as (epochSeconds, nanosOfSecond) rather than epochMilli.
            // Instant.toEpochMilli() truncates sub-millisecond nanoseconds, producing a
            // reloaded WrappedDek that is record-unequal to the original even though no
            // logical state changed (F-R1.data_transformation.1.01). The 12-byte encoding
            // below is lossless: Instant.ofEpochSecond(seconds, nanos) reconstructs the
            // exact original Instant.
            buf.putLong(d.createdAt().getEpochSecond());
            buf.putInt(d.createdAt().getNano());
        }
        buf.flip();
        final byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static long estimateSize(KeyRegistryShard shard) {
        // Conservative: 4 + 2 + 4 + len(tenantId)*4 + 4 + len(salt) + 4 + len(ref)*4 + 4 + ...
        //
        // Accumulator is long so a shard carrying enough DEKs or a sufficiently-large
        // wrappedBytes payload does not silently overflow Integer.MAX_VALUE to a negative
        // value. Callers compare the return against Integer.MAX_VALUE and reject with
        // IOException before passing to ByteBuffer.allocate (F-R1.contract_boundaries.3.3).
        long size = 4 + 2; // magic + version
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
            size += 12; // createdAt: 8B epochSeconds + 4B nanosOfSecond (lossless Instant)
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
                activeRef = new KekRef(decodeStrictUtf8(refBytes, path));
            }
            final int numDomainKeks = buf.getInt();
            if (numDomainKeks < 0) {
                throw new IOException("invalid domain KEK count: " + numDomainKeks + " at " + path);
            }
            // Bound reader-side allocation against the bytes actually available on disk.
            // An attacker with write access to the shard file could otherwise set this
            // count to Integer.MAX_VALUE; while the per-entry requireRemaining loop would
            // eventually abort, HashMap.put() on the first valid entry allocates a
            // Node[tableSizeFor(numDomainKeks)] backing array sized by the attacker-
            // supplied capacity — amplifying a 4-byte write into a multi-GB heap commit
            // (R19a integrity — malformed-file rejection must not be preceded by
            // adversarially-sized allocations).
            if (numDomainKeks > buf.remaining() / MIN_DOMAIN_KEK_BYTES) {
                throw new IOException("domain KEK count " + numDomainKeks
                        + " exceeds available bytes (" + buf.remaining() + ") at " + path);
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
            // Same bound as numDomainKeks above — see comment for rationale.
            if (numDeks > buf.remaining() / MIN_DEK_BYTES) {
                throw new IOException("DEK count " + numDeks + " exceeds available bytes ("
                        + buf.remaining() + ") at " + path);
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
                // Matched pair to the serialize side: read epochSeconds (8B) + nanosOfSecond
                // (4B). Instant.ofEpochSecond(seconds, nanos) normalizes values where nanos
                // is outside [0, 999_999_999] — validate the nano field explicitly so an
                // adversarial file cannot smuggle in a normalized Instant whose seconds
                // field differs from what the serializer wrote
                // (F-R1.data_transformation.1.01 fix; also hardens the reader against
                // malformed input per data_transformation discipline).
                final long epochSeconds = buf.getLong();
                final int nanosOfSecond = buf.getInt();
                if (nanosOfSecond < 0 || nanosOfSecond > 999_999_999) {
                    throw new IOException(
                            "invalid createdAt nanosOfSecond: " + nanosOfSecond + " at " + path);
                }
                final Instant createdAt = Instant.ofEpochSecond(epochSeconds, nanosOfSecond);
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
        return decodeStrictUtf8(b, path);
    }

    /**
     * Decode {@code bytes} as UTF-8 using a strict decoder that reports malformed input and
     * unmappable characters instead of silently substituting U+FFFD. Shard files that contain
     * invalid UTF-8 in a length-prefixed string region surface as IOException rather than
     * corrupting tenant/domain/table/KekRef identities (F-R1.data_transformation.1.04).
     */
    private static String decodeStrictUtf8(byte[] bytes, Path path) throws IOException {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(
                    "invalid UTF-8 in length-prefixed string at " + path + ": " + e.getMessage(),
                    e);
        }
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

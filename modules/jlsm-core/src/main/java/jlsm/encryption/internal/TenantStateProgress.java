package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

import jlsm.encryption.TenantId;
import jlsm.encryption.TenantState;

/**
 * Durable per-tenant {@code state-progress.bin} file under the registry root. Persists the current
 * tenant state, the transition timestamp, the last emitted event sequence, and the grace-entry
 * timestamp (where applicable).
 *
 * <p>
 * Wire format: same envelope shape as {@code ShardStorage} — magic prefix, version, fields, CRC-32C
 * trailer (R19a). 0600 perms (R70a). Atomic temp-file + rename commit (R20).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R19a, R20, R70a, R76b-2, R83c-1).
 *
 * @spec encryption.primitives-lifecycle R76b-2
 * @spec encryption.primitives-lifecycle R83c-1
 */
public final class TenantStateProgress {

    private static final System.Logger LOG = System.getLogger(TenantStateProgress.class.getName());

    /** 4-byte magic identifier: "TSPF" = Tenant State Progress File. */
    private static final byte[] MAGIC = { 'T', 'S', 'P', 'F' };
    private static final short FORMAT_VERSION = 1;
    /** Sentinel epoch-seconds value indicating an absent {@code gracesEnteredAt}. */
    private static final long GRACE_ABSENT_SECONDS = Long.MIN_VALUE;

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet
            .of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    private TenantStateProgress(Path root) {
        Objects.requireNonNull(root, "root");
        this.root = root;
    }

    /** Open (or create) the progress file directory under {@code root}. */
    public static TenantStateProgress open(Path root) {
        Objects.requireNonNull(root, "root");
        return new TenantStateProgress(root);
    }

    /**
     * Read the persisted state record for {@code tenantId}, or empty if no record exists.
     *
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on I/O failure or CRC mismatch
     */
    public Optional<StateRecord> read(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        final Path file = stateFile(tenantId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (bytes.length == 0) {
            // Empty file — treat as absent (matches ShardStorage convention).
            return Optional.empty();
        }
        return Optional.of(deserialize(bytes, file));
    }

    /**
     * Atomically commit a new state record for {@code tenantId} (temp-write + fsync + rename).
     *
     * @throws NullPointerException if any argument is null
     * @throws IOException on I/O failure
     */
    public void commit(TenantId tenantId, StateRecord record) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(record, "record");

        final Path file = stateFile(tenantId);
        final Path parent = file.getParent();
        assert parent != null : "state file path must have a parent directory";
        Files.createDirectories(parent);

        final boolean isPosix = isPosix(parent);
        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final Path temp = parent.resolve(file.getFileName() + "." + suffix + ".tmp");

        if (isPosix) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(temp);
            narrowAclBestEffort(temp);
        }

        boolean committed = false;
        try {
            final byte[] payload = serialize(record);
            final byte[] withCrc = appendCrc32c(payload);
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                final ByteBuffer buf = ByteBuffer.wrap(withCrc);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            if (isPosix) {
                Files.setPosixFilePermissions(temp, OWNER_ONLY);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            // Parent-directory fsync for dentry durability (matches ShardStorage R20 discipline).
            if (isPosix) {
                try (FileChannel dirCh = FileChannel.open(parent, StandardOpenOption.READ)) {
                    dirCh.force(true);
                }
            }
            if (isPosix) {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            } else {
                narrowAclBestEffort(file);
            }
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Resolve the per-tenant state file path. Lives at
     * {@code <root>/state-progress/<base32-tenant-id>/state-progress.bin}, paralleling the layout
     * of {@link ShardPathResolver} for consistency with R82b-family path derivation.
     */
    private Path stateFile(TenantId tenantId) {
        // Reuse ShardPathResolver's encoding by routing through it indirectly: derive the
        // tenant-shard-dir from the same base32 encoding so a single layout convention applies
        // to all tenant-scoped persistent artifacts. Per R76b-1a spec, the file lives at
        // <registry-root>/<tenant-shard-dir>/state-progress.bin; we use a parallel
        // "state-progress" subdirectory under root so this file does not collide with the
        // registry shard tree if the same root is used for both.
        final Path shardPath = ShardPathResolver.shardPath(root.resolve("state-progress"),
                tenantId);
        return shardPath.resolveSibling("state-progress.bin");
    }

    private static byte[] serialize(StateRecord record) {
        // Layout:
        // [MAGIC 4] [version 2]
        // [stateOrdinal 4]
        // [transitionAt epochSeconds 8] [transitionAt nanos 4]
        // [eventSeq 8]
        // [lastEmittedEventSeq 8]
        // [graceEpochSeconds 8] [graceNanos 4] (graceEpochSeconds == GRACE_ABSENT_SECONDS → null)
        final int size = 4 + 2 + 4 + 8 + 4 + 8 + 8 + 8 + 4;
        final ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(FORMAT_VERSION);
        buf.putInt(record.state().ordinal());
        buf.putLong(record.transitionAt().getEpochSecond());
        buf.putInt(record.transitionAt().getNano());
        buf.putLong(record.eventSeq());
        buf.putLong(record.lastEmittedEventSeq());
        if (record.gracesEnteredAt() == null) {
            buf.putLong(GRACE_ABSENT_SECONDS);
            buf.putInt(0);
        } else {
            buf.putLong(record.gracesEnteredAt().getEpochSecond());
            buf.putInt(record.gracesEnteredAt().getNano());
        }
        return buf.array();
    }

    private static StateRecord deserialize(byte[] bytes, Path file) throws IOException {
        if (bytes.length < 4) {
            throw new IOException("state file too short for CRC trailer at " + file);
        }
        final int payloadLen = bytes.length - 4;
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, payloadLen);
        final int computed = (int) crc.getValue();
        final int stored = ByteBuffer.wrap(bytes, payloadLen, 4).order(ByteOrder.BIG_ENDIAN)
                .getInt();
        if (computed != stored) {
            throw new IOException("CRC-32C mismatch on state file (" + file + "): expected 0x"
                    + Integer.toHexString(stored) + ", computed 0x"
                    + Integer.toHexString(computed));
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes, 0, payloadLen).order(ByteOrder.BIG_ENDIAN);
        try {
            final byte[] magic = new byte[4];
            buf.get(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("magic mismatch on state file at " + file);
                }
            }
            final short version = buf.getShort();
            if (version != FORMAT_VERSION) {
                throw new IOException(
                        "unsupported state-progress format version " + version + " at " + file);
            }
            final int stateOrdinal = buf.getInt();
            final TenantState[] states = TenantState.values();
            if (stateOrdinal < 0 || stateOrdinal >= states.length) {
                throw new IOException("invalid state ordinal " + stateOrdinal + " at " + file);
            }
            final TenantState state = states[stateOrdinal];
            final long transitionSec = buf.getLong();
            final int transitionNano = buf.getInt();
            if (transitionNano < 0 || transitionNano > 999_999_999) {
                throw new IOException(
                        "invalid transitionAt nanos " + transitionNano + " at " + file);
            }
            final Instant transitionAt = Instant.ofEpochSecond(transitionSec, transitionNano);
            final long eventSeq = buf.getLong();
            final long lastEmitted = buf.getLong();
            if (eventSeq < 0 || lastEmitted < 0) {
                throw new IOException("negative event-seq fields at " + file);
            }
            final long graceSec = buf.getLong();
            final int graceNano = buf.getInt();
            final Instant graceAt;
            if (graceSec == GRACE_ABSENT_SECONDS) {
                graceAt = null;
            } else {
                if (graceNano < 0 || graceNano > 999_999_999) {
                    throw new IOException(
                            "invalid graceEntered nanos " + graceNano + " at " + file);
                }
                graceAt = Instant.ofEpochSecond(graceSec, graceNano);
            }
            return new StateRecord(state, transitionAt, eventSeq, lastEmitted, graceAt);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException ex) {
            throw new IOException(
                    "malformed state-progress file at " + file + ": " + ex.getMessage(), ex);
        }
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

    private static boolean isPosix(Path p) {
        return Files.getFileAttributeView(p, PosixFileAttributeView.class) != null;
    }

    private static void narrowAclBestEffort(Path path) {
        LOG.log(System.Logger.Level.WARNING,
                "Non-POSIX filesystem: POSIX 0600 cannot be enforced on " + path
                        + ". Ensure the parent directory is ACL-restricted.");
    }

    /**
     * Durable per-tenant state record.
     *
     * @param state current tenant state
     * @param transitionAt timestamp at which {@code state} was entered
     * @param eventSeq monotonic event sequence at transition time
     * @param lastEmittedEventSeq highest eventSeq successfully emitted to observers
     * @param gracesEnteredAt timestamp of last grace-entry (null if never entered)
     */
    public record StateRecord(TenantState state, Instant transitionAt, long eventSeq,
            long lastEmittedEventSeq, Instant gracesEnteredAt) {

        public StateRecord {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(transitionAt, "transitionAt");
            // gracesEnteredAt may be null
            if (eventSeq < 0) {
                throw new IllegalArgumentException(
                        "eventSeq must be non-negative, got " + eventSeq);
            }
            if (lastEmittedEventSeq < 0) {
                throw new IllegalArgumentException(
                        "lastEmittedEventSeq must be non-negative, got " + lastEmittedEventSeq);
            }
        }
    }
}

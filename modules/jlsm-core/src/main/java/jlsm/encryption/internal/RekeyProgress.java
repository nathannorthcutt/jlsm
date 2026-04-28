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
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * Per-tenant durable {@code rekey-progress.bin} file. Records the rekey continuation token state
 * across process restarts. Atomic temp-write + fsync + rename commit (R20). 0600 perms (R70a).
 * CRC-32C trailer (R19a). Records older than 24h are observable to callers via the record's own
 * {@code startedAt} field — callers compare against wall-clock now and may emit a stale event.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R19a, R20, R70a, R78b, R78c, R78d).
 *
 * @spec encryption.primitives-lifecycle R78b
 * @spec encryption.primitives-lifecycle R78c
 */
public final class RekeyProgress {

    /** "RPFL" — Rekey Progress File. */
    private static final byte[] MAGIC = { 'R', 'P', 'F', 'L' };
    private static final short FORMAT_VERSION = 1;

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet
            .of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    private RekeyProgress(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Open (or create) the progress directory under {@code root}. */
    public static RekeyProgress open(Path root) {
        Objects.requireNonNull(root, "root");
        return new RekeyProgress(root);
    }

    /**
     * Read the persisted rekey progress for {@code tenantId}, or empty if no in-flight rekey.
     *
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on I/O failure or CRC mismatch
     */
    public Optional<ProgressRecord> read(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        final Path file = progressFile(tenantId);
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
            return Optional.empty();
        }
        return Optional.of(deserialize(bytes, file));
    }

    /** Atomically commit a new progress record. */
    public void commit(TenantId tenantId, ProgressRecord record) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(record, "record");
        final Path file = progressFile(tenantId);
        final Path parent = file.getParent();
        assert parent != null : "progress file path must have parent";
        Files.createDirectories(parent);

        final boolean isPosix = isPosix(parent);
        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final Path tmp = parent.resolve(file.getFileName() + "." + suffix + ".tmp");
        if (isPosix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(tmp);
        }
        boolean committed = false;
        try {
            final byte[] payload = serialize(record);
            final byte[] withCrc = appendCrc32c(payload);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                final ByteBuffer outBuf = ByteBuffer.wrap(withCrc);
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

    /** Delete the progress record for {@code tenantId} on rekey completion. */
    public void clear(TenantId tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        final Path file = progressFile(tenantId);
        Files.deleteIfExists(file);
    }

    private Path progressFile(TenantId tenantId) {
        final String tenantDir = encodeIdentifier(tenantId.value());
        return root.resolve("rekey-progress").resolve(tenantDir).resolve("rekey-progress.bin");
    }

    private static byte[] serialize(ProgressRecord rec) {
        // Layout (big-endian):
        // [MAGIC 4][version 2]
        // [oldKekRef len 4][oldKekRef bytes]
        // [newKekRef len 4][newKekRef bytes]
        // [nextShardIndex 4]
        // [startedAt epochSeconds 8][startedAt nanos 4]
        // [rekeyEpoch 8]
        // [lastEmittedEventSeq 8]
        // [permanentlySkipped 8]
        final byte[] oldRef = rec.oldKekRef().value().getBytes(StandardCharsets.UTF_8);
        final byte[] newRef = rec.newKekRef().value().getBytes(StandardCharsets.UTF_8);
        final int size = 4 + 2 + 4 + oldRef.length + 4 + newRef.length + 4 + 8 + 4 + 8 + 8 + 8;
        final ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(FORMAT_VERSION);
        buf.putInt(oldRef.length);
        buf.put(oldRef);
        buf.putInt(newRef.length);
        buf.put(newRef);
        buf.putInt(rec.nextShardIndex());
        buf.putLong(rec.startedAt().getEpochSecond());
        buf.putInt(rec.startedAt().getNano());
        buf.putLong(rec.rekeyEpoch());
        buf.putLong(rec.lastEmittedEventSeq());
        buf.putLong(rec.permanentlySkipped());
        return buf.array();
    }

    private static ProgressRecord deserialize(byte[] bytes, Path file) throws IOException {
        if (bytes.length < 4) {
            throw new IOException("progress file too short for CRC trailer at " + file);
        }
        final int payloadLen = bytes.length - 4;
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, payloadLen);
        final int computed = (int) crc.getValue();
        final int stored = ByteBuffer.wrap(bytes, payloadLen, 4).order(ByteOrder.BIG_ENDIAN)
                .getInt();
        if (computed != stored) {
            throw new IOException("CRC-32C mismatch on rekey-progress file (" + file
                    + "): expected 0x" + Integer.toHexString(stored) + ", computed 0x"
                    + Integer.toHexString(computed));
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes, 0, payloadLen).order(ByteOrder.BIG_ENDIAN);
        try {
            final byte[] magic = new byte[4];
            buf.get(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("magic mismatch on rekey-progress file at " + file);
                }
            }
            final short version = buf.getShort();
            if (version != FORMAT_VERSION) {
                throw new IOException(
                        "unsupported rekey-progress format version " + version + " at " + file);
            }
            final int oldLen = buf.getInt();
            if (oldLen < 0 || oldLen > buf.remaining()) {
                throw new IOException("invalid oldKekRef length " + oldLen + " at " + file);
            }
            final byte[] oldRef = new byte[oldLen];
            buf.get(oldRef);
            final int newLen = buf.getInt();
            if (newLen < 0 || newLen > buf.remaining()) {
                throw new IOException("invalid newKekRef length " + newLen + " at " + file);
            }
            final byte[] newRef = new byte[newLen];
            buf.get(newRef);
            final int nextShardIndex = buf.getInt();
            final long sec = buf.getLong();
            final int nanos = buf.getInt();
            if (nanos < 0 || nanos > 999_999_999) {
                throw new IOException("invalid startedAt nanos " + nanos + " at " + file);
            }
            final Instant startedAt = Instant.ofEpochSecond(sec, nanos);
            final long rekeyEpoch = buf.getLong();
            final long lastEmitted = buf.getLong();
            final long permanentlySkipped = buf.getLong();
            return new ProgressRecord(new KekRef(new String(oldRef, StandardCharsets.UTF_8)),
                    new KekRef(new String(newRef, StandardCharsets.UTF_8)), nextShardIndex,
                    startedAt, rekeyEpoch, lastEmitted, permanentlySkipped);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException ex) {
            throw new IOException(
                    "malformed rekey-progress file at " + file + ": " + ex.getMessage(), ex);
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

    /** Durable per-tenant rekey progress record. */
    public record ProgressRecord(KekRef oldKekRef, KekRef newKekRef, int nextShardIndex,
            Instant startedAt, long rekeyEpoch, long lastEmittedEventSeq, long permanentlySkipped) {

        public ProgressRecord {
            Objects.requireNonNull(oldKekRef, "oldKekRef");
            Objects.requireNonNull(newKekRef, "newKekRef");
            Objects.requireNonNull(startedAt, "startedAt");
            if (nextShardIndex < 0) {
                throw new IllegalArgumentException(
                        "nextShardIndex must be non-negative, got " + nextShardIndex);
            }
            if (rekeyEpoch < 0) {
                throw new IllegalArgumentException(
                        "rekeyEpoch must be non-negative, got " + rekeyEpoch);
            }
            if (lastEmittedEventSeq < 0) {
                throw new IllegalArgumentException(
                        "lastEmittedEventSeq must be non-negative, got " + lastEmittedEventSeq);
            }
            if (permanentlySkipped < 0) {
                throw new IllegalArgumentException(
                        "permanentlySkipped must be non-negative, got " + permanentlySkipped);
            }
        }
    }
}

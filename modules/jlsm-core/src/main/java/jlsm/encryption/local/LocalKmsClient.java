package jlsm.encryption.local;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;
import jlsm.encryption.internal.AesKeyWrap;

/**
 * Reference {@link KmsClient} backed by a local filesystem-resident master key. Used by tests,
 * quickstarts, and development environments where a real KMS is unavailable.
 *
 * @implNote NOT FOR PRODUCTION. This reference implementation supports rotation mechanics for test
 *           rigour but provides no HSM, no audit trail, and no hardware-protected keys. Production
 *           deployments must use a flavor-3 {@code KmsClient} backed by a real KMS (AWS KMS, GCP
 *           KMS, HashiCorp Vault, KMIP, etc.). {@link #isProductionReady} returns {@code false}
 *           specifically so automated startup checks can refuse to boot with this implementation.
 *
 *           <p>
 *           Master key format: exactly 32 raw bytes, stored in a file with POSIX 0600 permissions
 *           (owner read/write only). Non-POSIX filesystems (Windows) skip permission enforcement.
 *           The reference impl wraps under a single master key regardless of the supplied
 *           {@link KekRef}; the {@code kekRef} is passed through on the {@link WrapResult} for
 *           downstream recording but is otherwise opaque.
 *
 *           <p>
 *           AAD context binding: AES-KWP is deterministic and does not carry AAD, so the reference
 *           impl does not bind {@link EncryptionContext} at this tier. The AAD binding is enforced
 *           at tier-3 (AES-GCM wrap of the DEK under the domain KEK) by the
 *           {@code EncryptionKeyHolder} facade. This is acceptable for the reference impl; a
 *           production KMS adapter (e.g., AWS KMS) naturally enforces AAD at the KMS layer.
 *
 *           <p>
 *           Governed by: spec {@code encryption.primitives-lifecycle} R71, R71b.
 */
public final class LocalKmsClient implements KmsClient {

    /** Master key must be exactly this size in bytes. AES-256 wrap key. */
    private static final int MASTER_KEY_SIZE = 32;

    private final Path masterKeyPath;
    private final Arena masterArena;
    private final MemorySegment masterKeySegment;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @throws NullPointerException if {@code masterKeyPath} is null
     * @throws IOException if the file is missing, not 32 bytes, or has permissions wider than 0600
     *             on a POSIX filesystem
     */
    public LocalKmsClient(Path masterKeyPath) throws IOException {
        this.masterKeyPath = Objects.requireNonNull(masterKeyPath,
                "masterKeyPath must not be null");
        if (!Files.exists(masterKeyPath)) {
            throw new IOException("master key file does not exist: " + masterKeyPath);
        }
        final long size = Files.size(masterKeyPath);
        if (size != MASTER_KEY_SIZE) {
            throw new IOException("master key file must be exactly " + MASTER_KEY_SIZE
                    + " bytes (got " + size + "): " + masterKeyPath);
        }
        if (isPosix(masterKeyPath)) {
            final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(masterKeyPath);
            final Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            if (!perms.equals(ownerOnly)
                    && !perms.equals(EnumSet.of(PosixFilePermission.OWNER_READ))) {
                throw new IOException("master key file permissions must be 0600 (owner read/write)"
                        + " or 0400 (owner read), got " + perms + ": " + masterKeyPath);
            }
        }

        final byte[] bytes = Files.readAllBytes(masterKeyPath);
        assert bytes.length == MASTER_KEY_SIZE : "size check must have caught truncation";
        this.masterArena = Arena.ofShared();
        try {
            this.masterKeySegment = masterArena.allocate(MASTER_KEY_SIZE);
            MemorySegment.copy(bytes, 0, masterKeySegment, ValueLayout.JAVA_BYTE, 0,
                    MASTER_KEY_SIZE);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Convenience factory matching the idiomatic static-factory style. */
    public static LocalKmsClient fromMasterKeyFile(Path masterKeyPath) throws IOException {
        return new LocalKmsClient(masterKeyPath);
    }

    /** @return {@code false} — see class Javadoc. */
    public boolean isProductionReady() {
        return false;
    }

    /** Exposed for diagnostics and tests; never returns null. */
    public Path masterKeyPath() {
        return masterKeyPath;
    }

    @Override
    public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef, EncryptionContext context)
            throws KmsException {
        Objects.requireNonNull(plaintextKek, "plaintextKek must not be null");
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(context, "context must not be null");
        requireOpen();
        final byte[] wrapped = AesKeyWrap.wrap(masterKeySegment, plaintextKek);
        return new WrapResult(ByteBuffer.wrap(wrapped), kekRef);
    }

    @Override
    public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, EncryptionContext context)
            throws KmsException {
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(context, "context must not be null");
        requireOpen();
        final byte[] wrappedCopy = new byte[wrappedBytes.remaining()];
        // Preserve caller's buffer position.
        final ByteBuffer dup = wrappedBytes.duplicate();
        dup.get(wrappedCopy);
        if (wrappedCopy.length <= 8) {
            throw new KmsPermanentException(
                    "wrapped bytes too short for AES-KWP: " + wrappedCopy.length);
        }
        // AES-KWP overhead is 8 bytes over the nearest 8-byte-aligned plaintext length;
        // standard DEK plaintexts are 32 bytes → wrapped = 40. We accept variable-length
        // plaintexts here and let the unwrap surface a length mismatch if malformed.
        final int expectedPlaintextLen = wrappedCopy.length - 8;
        final Arena callerArena = Arena.ofShared();
        boolean ok = false;
        try {
            final MemorySegment plaintext = AesKeyWrap.unwrap(masterKeySegment, wrappedCopy,
                    expectedPlaintextLen, callerArena);
            ok = true;
            return new UnwrapResult(plaintext, callerArena);
        } catch (IllegalArgumentException e) {
            throw new KmsPermanentException("AES-KWP unwrap failed", e);
        } finally {
            if (!ok) {
                callerArena.close();
            }
            Arrays.fill(wrappedCopy, (byte) 0);
        }
    }

    @Override
    public boolean isUsable(KekRef kekRef) {
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        return !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Zero the master key segment before releasing the arena.
        try {
            masterKeySegment.fill((byte) 0);
        } catch (RuntimeException ignored) {
            // If the arena is already closing or the segment is no longer accessible,
            // the zeroization is effectively complete on arena release.
        }
        masterArena.close();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("LocalKmsClient is closed");
        }
    }

    private static boolean isPosix(Path p) {
        final Path probe = p.getParent() != null ? p.getParent() : p;
        return Files.getFileAttributeView(probe, PosixFileAttributeView.class) != null;
    }
}

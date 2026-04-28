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
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.Purpose;
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
    // F-R1.shared_state.4.1: serialise close() (writeLock) against in-flight wrap/unwrap
    // (readLock) so that masterKeySegment.fill + masterArena.close cannot run while any
    // wrap is mid-read on the segment. Mirrors OffHeapKeyMaterial.rwLock /
    // EncryptionKeyHolder.deriveGuard. The readLock allows concurrent wraps/unwraps to
    // proceed in parallel — it only serialises them against close.
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

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
        final MemorySegment seg;
        try {
            try {
                seg = masterArena.allocate(MASTER_KEY_SIZE);
                MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, MASTER_KEY_SIZE);
            } catch (RuntimeException | Error e) {
                // On any failure between arena allocation and the successful off-heap copy,
                // `this` never escapes to a caller so no future close() can release the
                // shared Arena. Close it here — Arena.ofShared is not GC-reclaimed, so
                // otherwise the off-heap region leaks permanently. Secondary failures in
                // close are suppressed onto the original cause so the primary failure
                // propagates unmodified.
                try {
                    masterArena.close();
                } catch (RuntimeException | Error secondary) {
                    e.addSuppressed(secondary);
                }
                throw e;
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        this.masterKeySegment = seg;
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
        // R80a closed-set routing: KmsClient wrap/unwrap is valid only for tier-1 operations
        // (DOMAIN_KEK, REKEY_SENTINEL, HEALTH_CHECK). Purpose.DEK wraps go through AES-GCM
        // under the domain KEK, not through KmsClient — a DEK-purpose context reaching this
        // dispatch point is a caller bug that must be surfaced, not silently accepted
        // (F-R1.dispatch_routing.1.02).
        rejectDekPurpose(context);
        // F-R1.shared_state.4.1: hold the readLock across requireOpen + AesKeyWrap.wrap so
        // close() (which takes the writeLock) cannot zero masterKeySegment or release the
        // arena while we are reading from it. Acquiring the readLock first, then checking
        // requireOpen establishes the happens-before edge: if close set closed=true and
        // took the writeLock, we cannot acquire the readLock until close releases it —
        // and by then closed=true so requireOpen will throw KmsPermanentException.
        rwLock.readLock().lock();
        try {
            requireOpen();
            final byte[] wrapped;
            try {
                wrapped = AesKeyWrap.wrap(masterKeySegment, plaintextKek);
            } catch (IllegalStateException | IllegalArgumentException e) {
                // R76a: translate unchecked terminal failures from AesKeyWrap into the sealed
                // KmsException hierarchy so callers can partition permanent vs transient.
                // IllegalStateException signals a non-retryable JCE failure or a closed-segment
                // access. IllegalArgumentException signals caller-controlled invalid input
                // (e.g., zero-length plaintext per R58, plaintext > Integer.MAX_VALUE). Both
                // are permanent failures — the caller's input or the wrap subsystem is in a
                // terminal state that retrying will not recover.
                throw new KmsPermanentException("AES-KWP wrap failed", e);
            }
            return new WrapResult(ByteBuffer.wrap(wrapped), kekRef);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, EncryptionContext context)
            throws KmsException {
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(context, "context must not be null");
        // R80a closed-set routing: see wrapKek for rationale.
        rejectDekPurpose(context);
        // F-R1.shared_state.4.2: hold the readLock across requireOpen + AesKeyWrap.unwrap
        // to serialise against close() taking the writeLock. See wrapKek for the same
        // rationale on happens-before and parallel-reader semantics.
        rwLock.readLock().lock();
        try {
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
            } catch (IllegalArgumentException | IllegalStateException e) {
                // R76a: translate unchecked terminal failures from AesKeyWrap into the sealed
                // KmsException hierarchy so callers can partition permanent vs transient.
                // IllegalArgumentException signals caller-controlled invalid input (malformed
                // ciphertext, length mismatch, invalid KEK size). IllegalStateException signals
                // a non-retryable wrap-subsystem failure (JCE error without encoded-form key, or
                // a closed-segment access on masterKeySegment). Both are permanent — the caller's
                // input or the wrap subsystem is in a terminal state that retrying will not
                // recover. Mirrors the multi-catch pattern in wrapKek (finding 2.1/2.2).
                throw new KmsPermanentException("AES-KWP unwrap failed", e);
            } finally {
                if (!ok) {
                    callerArena.close();
                }
                Arrays.fill(wrappedCopy, (byte) 0);
            }
        } finally {
            rwLock.readLock().unlock();
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
        // F-R1.shared_state.4.1/4.2: take the writeLock to fence out any in-flight wrap or
        // unwrap. In-flight readers acquired the readLock before observing closed=true; we
        // must wait for them to release it before zeroing the segment or closing the
        // arena, otherwise an in-flight AesKeyWrap.wrap could copy partially-zeroed bytes
        // (silent corruption) or hit IllegalStateException on the segment (contract break
        // surfaced as KmsPermanentException). Any wrap/unwrap that enters AFTER the CAS
        // above will fail requireOpen() and not attempt to acquire the readLock in a way
        // that would wait on us — so close cannot deadlock against a new arrival.
        rwLock.writeLock().lock();
        try {
            // Zero the master key segment before releasing the arena.
            try {
                masterKeySegment.fill((byte) 0);
            } catch (RuntimeException ignored) {
                // If the arena is already closing or the segment is no longer accessible,
                // the zeroization is effectively complete on arena release.
            }
            masterArena.close();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // --- WD-03 R71b-1 simulation (test-scope only) -----------------------
    // The methods below simulate KMS revocation/restoration scenarios for WD-03 lifecycle
    // tests. They are package-private to {@code jlsm.encryption.local} and guarded at runtime
    // by a {@link StackWalker} test-scope check that throws {@link UnsupportedOperationException}
    // if invoked from a non-test caller (the implementation pipeline pins the allowed
    // caller-class prefixes; the stub form below rejects all callers).

    /** R71b-1 revocation kind. */
    enum RevocationKind {
        /** Tenant- or domain-tier KEK is permanently revoked. */
        PERMANENT,
        /** KEK is transiently unavailable; restore is possible. */
        TRANSIENT;
    }

    /**
     * Simulate revocation of a tenant-tier {@link KekRef}. Subsequent wrap/unwrap calls against the
     * ref produce the configured failure category.
     *
     * @spec encryption.primitives-lifecycle R71b-1
     */
    void simulateTenantKekRevocation(KekRef kekRef, RevocationKind kind) {
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        requireTestScope();
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Simulate revocation of a domain-tier KEK ref.
     *
     * @spec encryption.primitives-lifecycle R71b-1
     */
    void simulateDomainKekRevocation(KekRef kekRef, RevocationKind kind) {
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        requireTestScope();
        throw new UnsupportedOperationException("not implemented");
    }

    /** Restore a previously-simulated revoked ref to usable state. */
    void restoreKek(KekRef kekRef) {
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        requireTestScope();
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Simulate an unclassified-error path: subsequent wrap/unwrap calls against {@code kekRef}
     * throw the supplied {@link RuntimeException}. Used by WU-4 escalator tests.
     */
    void simulateUnclassifiedException(KekRef kekRef, RuntimeException exception) {
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        Objects.requireNonNull(exception, "exception must not be null");
        requireTestScope();
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Runtime test-scope check via {@link StackWalker}. The stub form rejects all callers; the
     * implementation pipeline pins the allowed caller-class prefixes so production code cannot
     * invoke a simulation method by mistake.
     */
    private static void requireTestScope() {
        // WD-03 stub: reject unconditionally until the implementation pipeline pins the
        // allowed caller-class prefixes.
        throw new UnsupportedOperationException(
                "simulation methods are test-scope only — not implemented");
    }

    private static void rejectDekPurpose(EncryptionContext context) {
        // Purpose.DEK is not a valid KmsClient operation per R80a — DEKs are wrapped under
        // the domain KEK via AES-GCM, not via this SPI. DOMAIN_KEK, REKEY_SENTINEL, and
        // HEALTH_CHECK are all legitimate KmsClient purposes and must pass through.
        if (context.purpose() == Purpose.DEK) {
            throw new IllegalArgumentException(
                    "KmsClient wrap/unwrap is not valid for Purpose.DEK; valid purposes are "
                            + "{DOMAIN_KEK, REKEY_SENTINEL, HEALTH_CHECK} (R80a) "
                            + "(F-R1.dispatch_routing.1.02)");
        }
    }

    private void requireOpen() throws KmsPermanentException {
        if (closed.get()) {
            // R76a: a closed KMS client is a permanent, non-retryable failure. Surface it
            // through the sealed KmsException hierarchy so callers matching on
            // KmsPermanentException / KmsTransientException can classify the failure.
            // Previously this threw unchecked IllegalStateException, which bypassed the
            // SPI's declared `throws KmsException` contract (F-R1.contract_boundaries.2.7).
            throw new KmsPermanentException("LocalKmsClient is closed");
        }
    }

    private static boolean isPosix(Path p) {
        final Path probe = p.getParent() != null ? p.getParent() : p;
        return Files.getFileAttributeView(probe, PosixFileAttributeView.class) != null;
    }
}

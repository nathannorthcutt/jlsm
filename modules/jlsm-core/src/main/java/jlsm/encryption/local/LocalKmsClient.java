package jlsm.encryption.local;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;

/**
 * Reference {@link KmsClient} backed by a local filesystem-resident master key. Used
 * by tests, quickstarts, and development environments where a real KMS is
 * unavailable.
 *
 * @implNote NOT FOR PRODUCTION. A filesystem-resident master key offers no audit
 *           trail, no HSM attestation, no key-access policy enforcement, and no
 *           separation between operator and key-owner roles. Production deployments
 *           MUST plug in a genuine KMS adapter (AWS KMS, GCP KMS, Vault Transit,
 *           etc.). {@link #isProductionReady} returns {@code false} specifically so
 *           automated startup checks can refuse to boot with this implementation.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R71, R71b.
 */
public final class LocalKmsClient implements KmsClient {

    private final Path masterKeyPath;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @throws NullPointerException if {@code masterKeyPath} is null
     */
    public LocalKmsClient(Path masterKeyPath) {
        this.masterKeyPath = Objects.requireNonNull(masterKeyPath, "masterKeyPath must not be null");
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
        throw new UnsupportedOperationException("LocalKmsClient.wrapKek stub — WU-4 scope");
    }

    @Override
    public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, EncryptionContext context)
            throws KmsException {
        throw new UnsupportedOperationException("LocalKmsClient.unwrapKek stub — WU-4 scope");
    }

    @Override
    public boolean isUsable(KekRef kekRef) throws KmsException {
        throw new UnsupportedOperationException("LocalKmsClient.isUsable stub — WU-4 scope");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // idempotent
        }
        // Real impl zeroes the master-key segment here. Stub retains no state.
    }
}

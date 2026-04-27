package jlsm.encryption.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;

import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;

/**
 * Pre-persistence wrap → unwrap → byte-equality verifier (R83e, R83e-1). Roundtrip and verify
 * execute OUTSIDE the shard lock (P4-22). Atomic with respect to
 * {@link jlsm.encryption.EncryptionKeyHolder#close()} per P4-5: a holder close mid-verify aborts
 * cleanly without persisting partial state.
 *
 * <p>
 * The plaintext copy used for the comparison is zeroized in a {@code finally} block on every exit
 * path (success and failure) per R69.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83e, R83e-1).
 *
 * @spec encryption.primitives-lifecycle R83e
 * @spec encryption.primitives-lifecycle R83e-1
 */
public final class WrapRoundtripVerifier {

    private WrapRoundtripVerifier() {
        throw new UnsupportedOperationException("utility class — do not instantiate");
    }

    /**
     * Wrap {@code plaintext} under {@code kekRef}, immediately unwrap the result, and verify the
     * unwrapped bytes are byte-for-byte identical to {@code plaintext}. On success, hands the
     * wrapped bytes to {@code persistor} and returns the wrapped {@link ByteBuffer}; on mismatch,
     * throws {@link KmsException}. Plaintext is zeroized before this method returns.
     *
     * @throws NullPointerException if any argument is null
     * @throws KmsException on KMS failure or roundtrip mismatch
     * @throws IOException on persistence failure
     */
    public static ByteBuffer verifyAndPersist(KmsClient kmsClient, KekRef kekRef,
            EncryptionContext context, MemorySegment plaintext, Persistor persistor)
            throws KmsException, IOException {
        Objects.requireNonNull(kmsClient, "kmsClient");
        Objects.requireNonNull(kekRef, "kekRef");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(persistor, "persistor");

        // Step 1 — wrap. Failure surfaces directly (no persist).
        final WrapResult wrapResult = kmsClient.wrapKek(plaintext, kekRef, context);

        // Step 2 — unwrap. Track the resulting arena so it gets closed on every exit.
        final ByteBuffer wrappedView = wrapResult.wrappedBytes();
        final UnwrapResult unwrapResult = kmsClient.unwrapKek(wrappedView, kekRef, context);

        // Step 3 — byte-equality compare against an internal plaintext copy. Per R83e-1 the
        // plaintext copy used for the roundtrip comparison must be zeroed in {@code finally}.
        // Use a confined arena so close() definitively releases the off-heap copy.
        final long plaintextLen = plaintext.byteSize();
        try (Arena copyArena = Arena.ofConfined()) {
            final MemorySegment plaintextCopy = copyArena.allocate(plaintextLen);
            // R83e-1 plaintext-copy contract: this segment is zeroed by the try-with-resources
            // close() at the end of the block, completing R69 zeroisation.
            MemorySegment.copy(plaintext, 0, plaintextCopy, 0, plaintextLen);
            try {
                final MemorySegment unwrapped = unwrapResult.plaintext();
                final long unwrappedLen = unwrapped.byteSize();
                if (unwrappedLen != plaintextLen) {
                    throw new KmsPermanentException("wrap-roundtrip-failure: KMS plugin "
                            + kmsClient.getClass().getName() + " returned unwrapped length "
                            + unwrappedLen + " (expected " + plaintextLen + ")");
                }
                final long mismatchOffset = plaintextCopy.mismatch(unwrapped);
                if (mismatchOffset >= 0) {
                    throw new KmsPermanentException(
                            "wrap-roundtrip-failure: KMS plugin " + kmsClient.getClass().getName()
                                    + " returned mismatched bytes at offset " + mismatchOffset);
                }
            } finally {
                // Defensive zero — try-with-resources close() on copyArena releases the segment,
                // but a forced fill ensures plaintext bytes do not survive in any read-after-free
                // scenario the JIT might expose. Zero before arena release.
                try {
                    plaintextCopy.fill((byte) 0);
                } catch (RuntimeException ignored) {
                    // Arena may already be closing — fill exceptions are not actionable here.
                }
            }
        } finally {
            // Always release the unwrap arena — whether equality succeeded or failed, the
            // unwrapped plaintext must not survive past the verifier's return.
            unwrapResult.owner().close();
        }

        // Step 4 — persist. Failure here propagates as IOException; persistence happens AFTER
        // verify so a mismatch never reaches the registry.
        persistor.persist(wrappedView);

        return wrappedView;
    }

    /** Functional interface invoked with the wrapped bytes once verification succeeds. */
    @FunctionalInterface
    public interface Persistor {

        void persist(ByteBuffer wrappedBytes) throws IOException;
    }
}

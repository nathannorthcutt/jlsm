package jlsm.encryption.internal;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.RekeySentinel;
import jlsm.encryption.TenantId;
import jlsm.encryption.UnwrapResult;

/**
 * Dual-unwrap verifier for a {@link RekeySentinel} (R78a). Independently invokes
 * {@code unwrapKek(oldSentinel, oldRef, ctx)} and {@code unwrapKek(newSentinel, newRef, ctx)}, then
 * byte-for-byte compares the unwrapped nonces. Rejects sentinels older than the freshness window (5
 * minutes) or whose timestamp is too far in the future (clock-skew defense).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R78a).
 *
 * @spec encryption.primitives-lifecycle R78a
 */
public final class RekeySentinelVerifier {

    /** Maximum age of a sentinel timestamp per R78a. */
    private static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(5);
    /** Maximum future skew tolerated; symmetric with the past window. */
    private static final Duration FUTURE_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private RekeySentinelVerifier() {
        throw new UnsupportedOperationException("utility class — do not instantiate");
    }

    /**
     * Verify {@code sentinel} via the dual-unwrap protocol. On success, returns normally; on
     * mismatch, throws {@link KmsPermanentException}.
     *
     * <p>
     * Uses a placeholder {@link EncryptionContext} bound to the sentinel purpose; the verifier
     * expects the sentinel to have been wrapped under {@link Purpose#REKEY_SENTINEL}.
     *
     * @throws NullPointerException if any argument is null
     * @throws KmsException on KMS failure or sentinel mismatch
     * @throws IllegalArgumentException if {@code sentinel.timestamp()} is outside the 5-minute
     *             freshness window relative to wall-clock now
     */
    public static void verify(KmsClient kmsClient, KekRef oldRef, KekRef newRef,
            RekeySentinel sentinel) throws KmsException {
        Objects.requireNonNull(kmsClient, "kmsClient");
        Objects.requireNonNull(oldRef, "oldRef");
        Objects.requireNonNull(newRef, "newRef");
        Objects.requireNonNull(sentinel, "sentinel");

        final Instant now = Instant.now();
        final Instant ts = sentinel.timestamp();
        if (ts.isBefore(now.minus(FRESHNESS_WINDOW))) {
            throw new IllegalArgumentException("rekey sentinel timestamp " + ts
                    + " is older than the 5-minute freshness window (now=" + now + ")");
        }
        if (ts.isAfter(now.plus(FUTURE_SKEW_TOLERANCE))) {
            throw new IllegalArgumentException("rekey sentinel timestamp " + ts
                    + " is too far in the future (now=" + now + ")");
        }

        // The sentinel context is bound to a placeholder (TenantId, DomainId) — the local KMS
        // does not consume context for the AES-KWP wrap path, but production adapters would
        // bind it. We use a stable placeholder so the wrap and unwrap contexts agree at the
        // SPI boundary.
        final EncryptionContext ctx = EncryptionContext.forRekeySentinel(placeholderTenant(),
                placeholderDomain());

        // Independently invoke both unwraps. R78a: structural inspection alone is insufficient.
        final byte[] oldNonce = unwrapToBytes(kmsClient, sentinel.oldSentinelWrapped(), oldRef,
                ctx);
        final byte[] newNonce = unwrapToBytes(kmsClient, sentinel.newSentinelWrapped(), newRef,
                ctx);

        try {
            // Length mismatch is treated as a content mismatch — same outcome.
            if (oldNonce.length != newNonce.length) {
                throw new KmsPermanentException(
                        "rekey sentinel mismatch: unwrapped nonces have different lengths "
                                + oldNonce.length + " vs " + newNonce.length);
            }
            int differences = 0;
            for (int i = 0; i < oldNonce.length; i++) {
                differences |= (oldNonce[i] ^ newNonce[i]) & 0xFF;
            }
            if (differences != 0) {
                throw new KmsPermanentException(
                        "rekey sentinel mismatch: unwrapped nonces are not byte-equal");
            }
        } finally {
            // Zero unwrapped nonces — they were briefly held in heap arrays.
            for (int i = 0; i < oldNonce.length; i++) {
                oldNonce[i] = 0;
            }
            for (int i = 0; i < newNonce.length; i++) {
                newNonce[i] = 0;
            }
        }
    }

    private static byte[] unwrapToBytes(KmsClient client, java.nio.ByteBuffer wrapped, KekRef ref,
            EncryptionContext ctx) throws KmsException {
        final UnwrapResult unwrapped = client.unwrapKek(wrapped, ref, ctx);
        try {
            final MemorySegment plaintext = unwrapped.plaintext();
            final long lenLong = plaintext.byteSize();
            if (lenLong < 0 || lenLong > Integer.MAX_VALUE) {
                throw new KmsPermanentException(
                        "unwrapped sentinel length out of range: " + lenLong);
            }
            final int len = (int) lenLong;
            final byte[] out = new byte[len];
            MemorySegment.copy(plaintext, 0, MemorySegment.ofArray(out), 0, len);
            return out;
        } finally {
            // Zero the plaintext segment before releasing the arena (R66/R69).
            try {
                unwrapped.plaintext().fill((byte) 0);
            } catch (RuntimeException ignored) {
                // best-effort; arena.close still runs
            }
            unwrapped.owner().close();
        }
    }

    /**
     * Stable placeholder used at the verifier-SPI boundary for the AAD context. The local KMS impl
     * does not consume context for AES-KWP; production adapters that bind context will see the same
     * placeholder on wrap and unwrap. The verifier boundary is the contract — operators generating
     * sentinels must use the same context.
     */
    private static TenantId placeholderTenant() {
        return new TenantId("rekey-sentinel");
    }

    private static DomainId placeholderDomain() {
        return new DomainId("rekey-sentinel");
    }
}

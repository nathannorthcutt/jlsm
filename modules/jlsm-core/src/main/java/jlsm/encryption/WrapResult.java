package jlsm.encryption;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Result of a {@link KmsClient#wrapKek} call: the opaque wrapped key bytes and the {@link KekRef}
 * that records the KEK version used. {@code kekRef} may differ from the value passed in when the
 * KMS resolves an alias to a concrete key version.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R80, R80b.
 *
 * @param wrappedBytes opaque, provider-specific wrapped key material
 * @param kekRef the KEK reference that produced the wrap (may record alias resolution)
 */
public record WrapResult(ByteBuffer wrappedBytes, KekRef kekRef) {

    /**
     * @throws NullPointerException if either argument is null
     */
    public WrapResult {
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(kekRef, "kekRef must not be null");
        // Defensive copy: records are value-like, but ByteBuffer carries mutable cursor
        // state (position/limit). Without isolation, one consumer draining wrappedBytes()
        // would starve another; caller-side mutation to position/limit after construction
        // would silently change the record's logical content. Snapshot the current
        // remaining() bytes into a fresh read-only backing buffer so the record behaves
        // as a stable value (F-R1.contract_boundaries.2.6).
        final ByteBuffer snapshot = wrappedBytes.duplicate();
        final byte[] copy = new byte[snapshot.remaining()];
        snapshot.get(copy);
        wrappedBytes = ByteBuffer.wrap(copy).asReadOnlyBuffer();
    }

    /**
     * Returns a fresh view of the wrapped bytes with an independent position/limit, so multiple
     * consumers can each drain the payload without affecting one another. The underlying storage is
     * shared and read-only.
     */
    @Override
    public ByteBuffer wrappedBytes() {
        return wrappedBytes.duplicate();
    }
}

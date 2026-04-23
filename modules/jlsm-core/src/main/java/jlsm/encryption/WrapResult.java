package jlsm.encryption;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Result of a {@link KmsClient#wrapKek} call: the opaque wrapped key bytes and the
 * {@link KekRef} that records the KEK version used. {@code kekRef} may differ from
 * the value passed in when the KMS resolves an alias to a concrete key version.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R80, R80b.
 *
 * @param wrappedBytes opaque, provider-specific wrapped key material
 * @param kekRef the KEK reference that produced the wrap (may record alias
 *               resolution)
 */
public record WrapResult(ByteBuffer wrappedBytes, KekRef kekRef) {

    /**
     * @throws NullPointerException if either argument is null
     */
    public WrapResult {
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(kekRef, "kekRef must not be null");
    }
}

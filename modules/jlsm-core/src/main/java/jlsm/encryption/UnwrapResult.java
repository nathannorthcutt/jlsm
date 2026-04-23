package jlsm.encryption;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Result of a {@link KmsClient#unwrapKek} call: a {@link MemorySegment} containing the
 * plaintext key material and the {@link Arena} that owns its lifetime. The caller is
 * responsible for closing the arena — doing so zeroizes the plaintext segment
 * (via {@link Arena#close}'s native-memory release semantics when the arena is a
 * confined or shared arena over off-heap memory).
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R80, R66 (zeroize).
 *
 * @param plaintext off-heap segment holding the plaintext KEK
 * @param owner the arena whose lifetime bounds the segment
 */
public record UnwrapResult(MemorySegment plaintext, Arena owner) {

    /**
     * @throws NullPointerException if either argument is null
     */
    public UnwrapResult {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
    }
}

package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * AES Key Wrap with Padding (AES-KWP, RFC 5649) used to wrap a tier-2 Domain KEK under
 * a tier-1 Tenant KEK (R17 tier-2 wrap). AES-KWP is deterministic (no IV needed), has
 * a fixed +8-byte overhead relative to plaintext, and does not require an entropy
 * source. This makes it the right choice for wrap operations inside a KMS where IV
 * management would be problematic.
 *
 * <p>Implementation uses the JCE provider: {@code Cipher.getInstance("AESWrapPad")}.
 * WU-2 TDD verifies SunJCE availability on the target JVM; if unavailable, a
 * fallback RFC 5649 pure-Java implementation must be supplied.
 *
 * <p>The JCE {@link javax.crypto.spec.SecretKeySpec} used for the wrap KEK is
 * instantiated transiently and the caller's reference to the {@code SecretKeySpec} is
 * nulled immediately after {@code Cipher.init} per R68a.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R17 tier-2 wrap, R58.
 */
public final class AesKeyWrap {

    private AesKeyWrap() {}

    /**
     * Wrap {@code domainKekPlaintext} under {@code kek} using AES-KWP.
     *
     * @return wrapped bytes (plaintext length + 8, rounded up per RFC 5649)
     * @throws NullPointerException if either segment is null
     */
    public static byte[] wrap(MemorySegment kek, MemorySegment domainKekPlaintext) {
        throw new UnsupportedOperationException("AesKeyWrap.wrap stub — WU-2 scope");
    }

    /**
     * Unwrap {@code wrappedBytes} under {@code kek} using AES-KWP.
     *
     * @param plaintextLen expected plaintext length (for verification)
     * @param callerArena arena owning the returned segment's lifetime
     * @return plaintext segment in {@code callerArena}
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException on integrity-check failure or length mismatch
     */
    public static MemorySegment unwrap(
            MemorySegment kek,
            byte[] wrappedBytes,
            int plaintextLen,
            Arena callerArena) {
        throw new UnsupportedOperationException("AesKeyWrap.unwrap stub — WU-2 scope");
    }
}

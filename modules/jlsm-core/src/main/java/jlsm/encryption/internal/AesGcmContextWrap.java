package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.security.SecureRandom;

import jlsm.encryption.EncryptionContext;

/**
 * AES-GCM wrap of a tier-3 DEK under a tier-2 Domain KEK (R17 tier-3, R29), binding
 * the {@link EncryptionContext} as Additional Authenticated Data. The AAD encoding is
 * canonical so wrap and unwrap produce byte-identical AAD for equal contexts.
 *
 * <p>Ciphertext layout: {@code [12-byte random IV | ciphertext | 16-byte GCM tag]}.
 * The IV is drawn from the caller-provided {@link SecureRandom}.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R17 tier-3 wrap, R29,
 * R80a (context-bound AAD).
 */
public final class AesGcmContextWrap {

    private AesGcmContextWrap() {}

    /**
     * Wrap {@code dekPlaintext} under {@code domainKek}, binding {@code ctx} as AAD.
     *
     * @return {@code [IV | ciphertext | tag]} bytes
     * @throws NullPointerException if any argument is null
     */
    public static byte[] wrap(
            MemorySegment domainKek,
            MemorySegment dekPlaintext,
            EncryptionContext ctx,
            SecureRandom rng) {
        throw new UnsupportedOperationException("AesGcmContextWrap.wrap stub — WU-2 scope");
    }

    /**
     * Unwrap previously-wrapped bytes under {@code domainKek} and verify AAD matches
     * {@code ctx}. Returned segment owned by {@code callerArena}.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException on GCM tag mismatch, context mismatch, or
     *         length mismatch
     */
    public static MemorySegment unwrap(
            MemorySegment domainKek,
            byte[] wrappedBytes,
            EncryptionContext ctx,
            int plaintextLen,
            Arena callerArena) {
        throw new UnsupportedOperationException("AesGcmContextWrap.unwrap stub — WU-2 scope");
    }

    /**
     * Canonical AAD encoding: purpose ordinal + UTF-8 length-prefixed attribute keys
     * and values in sorted-key order so both sides produce identical bytes.
     *
     * @throws NullPointerException if {@code ctx} is null
     */
    private static byte[] encodeAad(EncryptionContext ctx) {
        throw new UnsupportedOperationException("AesGcmContextWrap.encodeAad stub — WU-2 scope");
    }
}

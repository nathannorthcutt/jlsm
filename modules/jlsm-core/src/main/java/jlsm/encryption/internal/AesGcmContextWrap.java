package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jlsm.encryption.EncryptionContext;

/**
 * AES-GCM wrap of a tier-3 DEK under a tier-2 Domain KEK (R17 tier-3, R29), binding the
 * {@link EncryptionContext} as Additional Authenticated Data. The AAD encoding is canonical so wrap
 * and unwrap produce byte-identical AAD for equal contexts.
 *
 * <p>
 * Ciphertext layout: {@code [12-byte random IV | ciphertext | 16-byte GCM tag]}. The IV is drawn
 * from the caller-provided {@link SecureRandom}.
 *
 * <p>
 * AAD encoding:
 *
 * <pre>
 *   [4-byte BE purpose-ordinal]
 *   [4-byte BE attribute-count]
 *   for each attribute in sorted-key order:
 *     [4-byte BE key-length] [UTF-8 key bytes]
 *     [4-byte BE value-length] [UTF-8 value bytes]
 * </pre>
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R17 tier-3 wrap, R29, R80a
 * (context-bound AAD).
 */
public final class AesGcmContextWrap {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final int TAG_LEN_BYTES = TAG_LEN_BITS / 8;

    private AesGcmContextWrap() {
    }

    /**
     * Wrap {@code dekPlaintext} under {@code domainKek}, binding {@code ctx} as AAD.
     *
     * @return {@code [IV | ciphertext | tag]} bytes
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if KEK or plaintext is zero-length
     */
    public static byte[] wrap(MemorySegment domainKek, MemorySegment dekPlaintext,
            EncryptionContext ctx, SecureRandom rng) {
        Objects.requireNonNull(domainKek, "domainKek must not be null");
        Objects.requireNonNull(dekPlaintext, "dekPlaintext must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(rng, "rng must not be null");
        final long kekLen = domainKek.byteSize();
        final long plaintextLen = dekPlaintext.byteSize();
        validateKekSize(kekLen);
        if (plaintextLen == 0) {
            throw new IllegalArgumentException("plaintext must not be zero-length (R58)");
        }
        if (plaintextLen > Integer.MAX_VALUE - IV_LEN - TAG_LEN_BYTES) {
            throw new IllegalArgumentException("plaintext too large");
        }

        byte[] kekBytes = null;
        byte[] plaintextBytes = null;
        byte[] aad = null;
        try {
            kekBytes = new byte[(int) kekLen];
            MemorySegment.copy(domainKek, ValueLayout.JAVA_BYTE, 0, kekBytes, 0, (int) kekLen);
            plaintextBytes = new byte[(int) plaintextLen];
            MemorySegment.copy(dekPlaintext, ValueLayout.JAVA_BYTE, 0, plaintextBytes, 0,
                    (int) plaintextLen);

            final byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            // R68a: SecretKeySpec is method-local; cleanup happens via zeroing kekBytes.
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kekBytes, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));

            aad = encodeAad(ctx);
            cipher.updateAAD(aad);
            final byte[] ctAndTag = cipher.doFinal(plaintextBytes);
            assert ctAndTag.length == plaintextLen + TAG_LEN_BYTES : "GCM output length mismatch";

            final byte[] result = new byte[IV_LEN + ctAndTag.length];
            System.arraycopy(iv, 0, result, 0, IV_LEN);
            System.arraycopy(ctAndTag, 0, result, IV_LEN, ctAndTag.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM wrap failed", e);
        } finally {
            if (kekBytes != null) {
                Arrays.fill(kekBytes, (byte) 0);
            }
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
            // aad is not secret (it's the context, deterministically derivable), but we clear
            // it anyway for consistency with R16c intermediate-buffer hygiene.
            if (aad != null) {
                Arrays.fill(aad, (byte) 0);
            }
        }
    }

    /**
     * Unwrap previously-wrapped bytes under {@code domainKek} and verify AAD matches {@code ctx}.
     * Returned segment owned by {@code callerArena}.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException on GCM tag mismatch, context mismatch, length mismatch, or
     *             malformed input
     */
    public static MemorySegment unwrap(MemorySegment domainKek, byte[] wrappedBytes,
            EncryptionContext ctx, int plaintextLen, Arena callerArena) {
        Objects.requireNonNull(domainKek, "domainKek must not be null");
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");
        Objects.requireNonNull(callerArena, "callerArena must not be null");
        final long kekLen = domainKek.byteSize();
        validateKekSize(kekLen);
        if (plaintextLen <= 0) {
            throw new IllegalArgumentException(
                    "plaintextLen must be positive, got " + plaintextLen);
        }
        if (wrappedBytes.length != IV_LEN + plaintextLen + TAG_LEN_BYTES) {
            throw new IllegalArgumentException("wrappedBytes length mismatch: expected "
                    + (IV_LEN + plaintextLen + TAG_LEN_BYTES) + ", got " + wrappedBytes.length);
        }

        byte[] kekBytes = null;
        byte[] plaintextBytes = null;
        byte[] aad = null;
        try {
            kekBytes = new byte[(int) kekLen];
            MemorySegment.copy(domainKek, ValueLayout.JAVA_BYTE, 0, kekBytes, 0, (int) kekLen);

            final byte[] iv = Arrays.copyOfRange(wrappedBytes, 0, IV_LEN);

            final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            // R68a: SecretKeySpec is method-local; cleanup happens via zeroing kekBytes.
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kekBytes, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LEN_BITS, iv));

            aad = encodeAad(ctx);
            cipher.updateAAD(aad);
            plaintextBytes = cipher.doFinal(wrappedBytes, IV_LEN, wrappedBytes.length - IV_LEN);
            if (plaintextBytes.length != plaintextLen) {
                throw new IllegalArgumentException("decrypted plaintext length mismatch: expected "
                        + plaintextLen + ", got " + plaintextBytes.length);
            }

            final MemorySegment out = callerArena.allocate(plaintextLen);
            MemorySegment.copy(plaintextBytes, 0, out, ValueLayout.JAVA_BYTE, 0, plaintextLen);
            return out;
        } catch (GeneralSecurityException e) {
            // AEADBadTagException or similar — authentication failed.
            throw new IllegalArgumentException(
                    "AES-GCM unwrap failed (authentication or context mismatch)", e);
        } finally {
            if (kekBytes != null) {
                Arrays.fill(kekBytes, (byte) 0);
            }
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
            if (aad != null) {
                Arrays.fill(aad, (byte) 0);
            }
        }
    }

    /**
     * Canonical AAD encoding: purpose ordinal + sorted-by-key UTF-8 length-prefixed attributes.
     * Both sides produce byte-identical AAD for equal contexts; any field drift (tenant, domain,
     * table, version, purpose) yields different bytes and therefore a GCM authentication failure.
     */
    private static byte[] encodeAad(EncryptionContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        final Map<String, String> attrs = ctx.attributes();
        final List<String> keys = new ArrayList<>(attrs.keySet());
        Collections.sort(keys);

        // Compute total size.
        int total = 4 + 4; // purpose ordinal + attribute count
        final byte[][] keyBytes = new byte[keys.size()][];
        final byte[][] valBytes = new byte[keys.size()][];
        for (int i = 0; i < keys.size(); i++) {
            keyBytes[i] = keys.get(i).getBytes(StandardCharsets.UTF_8);
            valBytes[i] = attrs.get(keys.get(i)).getBytes(StandardCharsets.UTF_8);
            total += 4 + keyBytes[i].length + 4 + valBytes[i].length;
        }

        final ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(ctx.purpose().ordinal());
        buf.putInt(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            buf.putInt(keyBytes[i].length);
            buf.put(keyBytes[i]);
            buf.putInt(valBytes[i].length);
            buf.put(valBytes[i]);
        }
        assert !buf.hasRemaining() : "encodeAad sizing miscalculation";
        return buf.array();
    }

    private static void validateKekSize(long kekLen) {
        if (kekLen == 0) {
            throw new IllegalArgumentException("domainKek must not be zero-length (R58)");
        }
        if (kekLen != 16 && kekLen != 24 && kekLen != 32) {
            throw new IllegalArgumentException(
                    "domainKek must be 16, 24, or 32 bytes, got " + kekLen);
        }
    }
}

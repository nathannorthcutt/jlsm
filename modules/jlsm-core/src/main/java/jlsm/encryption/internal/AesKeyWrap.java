package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES Key Wrap with Padding (AES-KWP, RFC 5649) used to wrap a tier-2 Domain KEK under a tier-1
 * Tenant KEK (R17 tier-2 wrap). AES-KWP is deterministic (no IV needed), has a fixed +8-byte
 * overhead relative to plaintext, and does not require an entropy source. This makes it the right
 * choice for wrap operations inside a KMS where IV management would be problematic.
 *
 * <p>
 * Implementation uses the JCE provider: {@code Cipher.getInstance("AESWrapPad")}. WU-2 TDD verifies
 * SunJCE availability on the target JVM; if unavailable, a fallback RFC 5649 pure-Java
 * implementation must be supplied.
 *
 * <p>
 * The JCE {@link javax.crypto.spec.SecretKeySpec} used for the wrap KEK is instantiated transiently
 * and nulled immediately after {@code Cipher.init} per R68a.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R17 tier-2 wrap, R58.
 */
public final class AesKeyWrap {

    private static final String WRAP_ALGORITHM = "AESWrapPad";
    private static final String KEY_ALGORITHM = "AES";

    private AesKeyWrap() {
    }

    /**
     * Wrap {@code domainKekPlaintext} under {@code kek} using AES-KWP.
     *
     * @return wrapped bytes (plaintext length + 8, rounded up per RFC 5649)
     * @throws NullPointerException if either segment is null
     * @throws IllegalArgumentException if either segment is zero-length or KEK is not a valid AES
     *             key size
     */
    public static byte[] wrap(MemorySegment kek, MemorySegment domainKekPlaintext) {
        Objects.requireNonNull(kek, "kek must not be null");
        Objects.requireNonNull(domainKekPlaintext, "domainKekPlaintext must not be null");
        final long kekLen = kek.byteSize();
        final long plaintextLen = domainKekPlaintext.byteSize();
        validateKekSize(kekLen);
        if (plaintextLen == 0) {
            throw new IllegalArgumentException("plaintext must not be zero-length (R58)");
        }
        if (plaintextLen > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("plaintext larger than Integer.MAX_VALUE");
        }

        byte[] kekBytes = null;
        byte[] plaintextBytes = null;
        try {
            kekBytes = new byte[(int) kekLen];
            MemorySegment.copy(kek, ValueLayout.JAVA_BYTE, 0, kekBytes, 0, (int) kekLen);
            plaintextBytes = new byte[(int) plaintextLen];
            MemorySegment.copy(domainKekPlaintext, ValueLayout.JAVA_BYTE, 0, plaintextBytes, 0,
                    (int) plaintextLen);

            final Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            // R68a: SecretKeySpec is method-local; SunJCE copies it defensively on construction,
            // so our only cleanup obligation is zeroing kekBytes in finally.
            cipher.init(Cipher.WRAP_MODE, new SecretKeySpec(kekBytes, KEY_ALGORITHM));
            return cipher.wrap(new SecretKeySpec(plaintextBytes, KEY_ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-KWP wrap failed", e);
        } finally {
            if (kekBytes != null) {
                Arrays.fill(kekBytes, (byte) 0);
            }
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
        }
    }

    /**
     * Unwrap {@code wrappedBytes} under {@code kek} using AES-KWP.
     *
     * @param plaintextLen expected plaintext length (for verification)
     * @param callerArena arena owning the returned segment's lifetime
     * @return plaintext segment in {@code callerArena}
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException on integrity-check failure, length mismatch, or invalid KEK
     *             size
     */
    public static MemorySegment unwrap(MemorySegment kek, byte[] wrappedBytes, int plaintextLen,
            Arena callerArena) {
        Objects.requireNonNull(kek, "kek must not be null");
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(callerArena, "callerArena must not be null");
        final long kekLen = kek.byteSize();
        validateKekSize(kekLen);
        if (plaintextLen <= 0) {
            throw new IllegalArgumentException(
                    "plaintextLen must be positive, got " + plaintextLen);
        }

        byte[] kekBytes = null;
        byte[] plaintextBytes = null;
        try {
            kekBytes = new byte[(int) kekLen];
            MemorySegment.copy(kek, ValueLayout.JAVA_BYTE, 0, kekBytes, 0, (int) kekLen);

            final Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            // R68a: SecretKeySpec is method-local; cleanup happens via zeroing kekBytes.
            cipher.init(Cipher.UNWRAP_MODE, new SecretKeySpec(kekBytes, KEY_ALGORITHM));

            // JCE cipher.unwrap returns a Key whose encoded form is the plaintext.
            final java.security.Key recovered = cipher.unwrap(wrappedBytes, KEY_ALGORITHM,
                    Cipher.SECRET_KEY);
            plaintextBytes = recovered.getEncoded();
            if (plaintextBytes == null) {
                throw new IllegalStateException("unwrap produced a key with no encoded form");
            }
            if (plaintextBytes.length != plaintextLen) {
                throw new IllegalArgumentException("plaintext length mismatch: expected "
                        + plaintextLen + ", got " + plaintextBytes.length);
            }

            final MemorySegment out = callerArena.allocate(plaintextLen);
            MemorySegment.copy(plaintextBytes, 0, out, ValueLayout.JAVA_BYTE, 0, plaintextLen);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES-KWP unwrap failed (integrity check or length)",
                    e);
        } finally {
            if (kekBytes != null) {
                Arrays.fill(kekBytes, (byte) 0);
            }
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
        }
    }

    private static void validateKekSize(long kekLen) {
        if (kekLen == 0) {
            throw new IllegalArgumentException("KEK must not be zero-length (R58)");
        }
        if (kekLen != 16 && kekLen != 24 && kekLen != 32) {
            throw new IllegalArgumentException(
                    "KEK must be 16, 24, or 32 bytes (AES-128/192/256), got " + kekLen);
        }
    }
}

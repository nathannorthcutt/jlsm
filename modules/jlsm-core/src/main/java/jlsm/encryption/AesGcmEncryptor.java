package jlsm.encryption;

import jlsm.encryption.internal.OffHeapKeyMaterial;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Contract: Opaque encryption using AES-GCM with a random IV per encryption. Provides authenticated
 * encryption — tampering or wrong-key decryption is detected via tag verification. No search
 * capability on encrypted values.
 *
 * <p>
 * Ciphertext format: {@code [12-byte IV || ciphertext || 16-byte auth tag]} — +28 bytes expansion.
 *
 * <p>
 * Governed by: .kb/algorithms/encryption/searchable-encryption-schemes.md
 */
public final class AesGcmEncryptor implements AutoCloseable {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int OVERHEAD = IV_LENGTH + TAG_BYTES;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    /** Per-thread Cipher — GCM requires init per call (new IV), but the object is reusable. */
    private final ThreadLocal<Cipher> cipher;
    /** Cached SecretKeySpec — immutable, safe to share across threads. */
    private final SecretKeySpec keySpec;
    private final ThreadLocal<SecureRandom> random;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates an AES-GCM encryptor using the given key holder.
     *
     * @param keyHolder the key holder providing a 256-bit (32-byte) key
     * @throws IllegalArgumentException if key length is not 32 bytes
     */
    public AesGcmEncryptor(OffHeapKeyMaterial keyHolder) {
        Objects.requireNonNull(keyHolder, "keyHolder must not be null");
        if (keyHolder.keyLength() != 32) {
            throw new IllegalArgumentException(
                    "AES-GCM requires a 256-bit (32-byte) key, got " + keyHolder.keyLength());
        }
        this.keySpec = new SecretKeySpec(keyHolder.getKeyBytes(), "AES");
        this.random = ThreadLocal.withInitial(SecureRandom::new);
        this.cipher = ThreadLocal.withInitial(() -> {
            try {
                return Cipher.getInstance(ALGORITHM);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to initialize AES-GCM cipher", e);
            }
        });
    }

    /**
     * Encrypts the plaintext with AES-GCM using a random IV.
     *
     * @param plaintext the data to encrypt; must not be null
     * @return the ciphertext as {@code [12-byte IV || encrypted bytes || 16-byte tag]}
     */
    // @spec encryption.ciphertext-envelope.R1b — writer produces exact byte count for Opaque
    // variant (12B IV + ciphertext + 16B tag); caller (FieldEncryptionDispatch /
    // DocumentSerializer)
    // owns the 4B DEK version prefix if/when envelope versioning is applied
    public byte[] encrypt(byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        ensureOpen();
        try {
            final byte[] iv = new byte[IV_LENGTH];
            random.get().nextBytes(iv);

            final Cipher c = cipher.get();
            final GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
            c.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            final byte[] encrypted = c.doFinal(plaintext);
            // encrypted = ciphertext || tag (GCM appends the tag)
            assert encrypted.length == plaintext.length + TAG_BYTES : "GCM output length mismatch";

            final byte[] result = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts AES-GCM ciphertext and verifies the authentication tag.
     *
     * @param ciphertext the data to decrypt (IV || encrypted || tag); must not be null
     * @return the plaintext bytes
     * @throws IllegalArgumentException if ciphertext is too short
     * @throws SecurityException if authentication tag verification fails
     */
    public byte[] decrypt(byte[] ciphertext) {
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        ensureOpen();
        // @spec encryption.ciphertext-envelope.R1b — reader rejects blobs with inconsistent byte
        // count (minimum IV+tag overhead for the Opaque variant)
        if (ciphertext.length < OVERHEAD) {
            throw new IllegalArgumentException("Ciphertext too short: minimum " + OVERHEAD
                    + " bytes, got " + ciphertext.length);
        }
        try {
            final byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_LENGTH);
            final byte[] encrypted = Arrays.copyOfRange(ciphertext, IV_LENGTH, ciphertext.length);

            final Cipher c = cipher.get();
            final GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
            c.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            return c.doFinal(encrypted);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecurityException("AES-GCM authentication failed: wrong key or tampered data",
                    e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("AES-GCM decryption failed", e);
        }
    }

    /**
     * Removes per-thread Cipher and SecureRandom entries for the calling thread and marks this
     * encryptor as closed. Subsequent calls to {@link #encrypt} or {@link #decrypt} will throw
     * {@link IllegalStateException}. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cipher.remove();
        random.remove();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AesGcmEncryptor has been closed");
        }
    }
}

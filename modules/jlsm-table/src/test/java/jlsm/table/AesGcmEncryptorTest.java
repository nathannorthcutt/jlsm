package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.table.internal.AesGcmEncryptor;
import jlsm.table.internal.EncryptionKeyHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Tests for {@link AesGcmEncryptor}: opaque AES-GCM authenticated encryption.
 */
class AesGcmEncryptorTest {

    private EncryptionKeyHolder keyHolder;

    private static byte[] key256() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static byte[] key256Alt() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 0x80);
        }
        return key;
    }

    @BeforeEach
    void setUp() {
        keyHolder = EncryptionKeyHolder.of(key256());
    }

    @AfterEach
    void tearDown() {
        keyHolder.close();
    }

    // ── Round-trip ──────────────────────────────────────────────────────────

    @Test
    void encryptDecrypt_roundTrip() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = "Hello, AES-GCM!".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext);
        final byte[] recovered = encryptor.decrypt(ciphertext);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_emptyPlaintext() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = new byte[0];

        final byte[] ciphertext = encryptor.encrypt(plaintext);
        final byte[] recovered = encryptor.decrypt(ciphertext);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_largePlaintext() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = new byte[8192];
        Arrays.fill(plaintext, (byte) 0xCD);

        final byte[] ciphertext = encryptor.encrypt(plaintext);
        final byte[] recovered = encryptor.decrypt(ciphertext);

        assertArrayEquals(plaintext, recovered);
    }

    // ── Non-determinism ─────────────────────────────────────────────────────

    @Test
    void encrypt_isNonDeterministic_differentCiphertextEachTime() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = "non-deterministic".getBytes();

        final byte[] ct1 = encryptor.encrypt(plaintext);
        final byte[] ct2 = encryptor.encrypt(plaintext);

        assertFalse(Arrays.equals(ct1, ct2),
                "Same plaintext must produce different ciphertext (random IV)");
    }

    // ── Ciphertext expansion ────────────────────────────────────────────────

    @Test
    void encrypt_ciphertextIs28BytesLongerThanPlaintext() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = "measure expansion".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext);

        assertEquals(plaintext.length + 28, ciphertext.length,
                "Ciphertext must be exactly 28 bytes longer (12 IV + 16 tag)");
    }

    @Test
    void encrypt_emptyPlaintext_ciphertextIs28Bytes() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);

        final byte[] ciphertext = encryptor.encrypt(new byte[0]);

        assertEquals(28, ciphertext.length,
                "Empty plaintext produces 28-byte ciphertext (IV + tag)");
    }

    // ── Wrong key ───────────────────────────────────────────────────────────

    @Test
    void decrypt_wrongKey_throwsSecurityException() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = "secret data".getBytes();
        final byte[] ciphertext = encryptor.encrypt(plaintext);

        try (final EncryptionKeyHolder otherKey = EncryptionKeyHolder.of(key256Alt())) {
            final AesGcmEncryptor otherEncryptor = new AesGcmEncryptor(otherKey);
            assertThrows(SecurityException.class, () -> otherEncryptor.decrypt(ciphertext),
                    "Decryption with wrong key must throw SecurityException");
        }
    }

    // ── Tampered ciphertext ─────────────────────────────────────────────────

    @Test
    void decrypt_tamperedCiphertext_throwsSecurityException() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        final byte[] plaintext = "tamper test".getBytes();
        final byte[] ciphertext = encryptor.encrypt(plaintext);

        // Flip a byte in the encrypted data portion (after the 12-byte IV)
        ciphertext[14] ^= 0xFF;

        assertThrows(SecurityException.class, () -> encryptor.decrypt(ciphertext),
                "Tampered ciphertext must throw SecurityException");
    }

    // ── Input validation ────────────────────────────────────────────────────

    @Test
    void encrypt_nullPlaintext_throwsNullPointerException() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        assertThrows(NullPointerException.class, () -> encryptor.encrypt(null));
    }

    @Test
    void decrypt_nullCiphertext_throwsNullPointerException() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        assertThrows(NullPointerException.class, () -> encryptor.decrypt(null));
    }

    @Test
    void decrypt_ciphertextTooShort_throwsIllegalArgumentException() {
        final AesGcmEncryptor encryptor = new AesGcmEncryptor(keyHolder);
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(new byte[27]));
    }

    @Test
    void constructor_rejects512BitKey() {
        final byte[] bigKey = new byte[64];
        Arrays.fill(bigKey, (byte) 0x42);
        try (final EncryptionKeyHolder holder512 = EncryptionKeyHolder.of(bigKey)) {
            assertThrows(IllegalArgumentException.class, () -> new AesGcmEncryptor(holder512));
        }
    }
}

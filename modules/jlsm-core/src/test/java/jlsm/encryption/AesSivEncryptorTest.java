package jlsm.encryption;

import jlsm.encryption.internal.OffHeapKeyMaterial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Tests for {@link AesSivEncryptor}: deterministic AES-SIV encryption per RFC 5297.
 */
class AesSivEncryptorTest {

    private OffHeapKeyMaterial keyHolder;

    private static byte[] key512() {
        final byte[] key = new byte[64];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static byte[] key512Alt() {
        final byte[] key = new byte[64];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 0x80);
        }
        return key;
    }

    @BeforeEach
    void setUp() {
        keyHolder = OffHeapKeyMaterial.of(key512());
    }

    @AfterEach
    void tearDown() {
        keyHolder.close();
    }

    // ── Round-trip ──────────────────────────────────────────────────────────

    @Test
    void encryptDecrypt_roundTrip() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "Hello, AES-SIV!".getBytes();
        final byte[] ad = "context".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext, ad);
        final byte[] recovered = encryptor.decrypt(ciphertext, ad);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_emptyAssociatedData() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "No AD test".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext, new byte[0]);
        final byte[] recovered = encryptor.decrypt(ciphertext, new byte[0]);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_nullAssociatedData() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "Null AD test".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext, null);
        final byte[] recovered = encryptor.decrypt(ciphertext, null);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_emptyPlaintext() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = new byte[0];

        final byte[] ciphertext = encryptor.encrypt(plaintext, null);
        final byte[] recovered = encryptor.decrypt(ciphertext, null);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encryptDecrypt_roundTrip_largePlaintext() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = new byte[4096];
        Arrays.fill(plaintext, (byte) 0xAB);

        final byte[] ciphertext = encryptor.encrypt(plaintext, null);
        final byte[] recovered = encryptor.decrypt(ciphertext, null);

        assertArrayEquals(plaintext, recovered);
    }

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    void encrypt_isDeterministic_samePlaintextAndAd() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "deterministic".getBytes();
        final byte[] ad = "same-ad".getBytes();

        final byte[] ct1 = encryptor.encrypt(plaintext, ad);
        final byte[] ct2 = encryptor.encrypt(plaintext, ad);

        assertArrayEquals(ct1, ct2, "Same plaintext + same AD must produce identical ciphertext");
    }

    @Test
    void encrypt_differentAd_producesDifferentCiphertext() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "same-plaintext".getBytes();

        final byte[] ct1 = encryptor.encrypt(plaintext, "ad-one".getBytes());
        final byte[] ct2 = encryptor.encrypt(plaintext, "ad-two".getBytes());

        assertFalse(Arrays.equals(ct1, ct2),
                "Different associated data must produce different ciphertext");
    }

    // ── Ciphertext expansion ────────────────────────────────────────────────

    @Test
    void encrypt_ciphertextIs16BytesLongerThanPlaintext() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "measure expansion".getBytes();

        final byte[] ciphertext = encryptor.encrypt(plaintext, null);

        assertEquals(plaintext.length + 16, ciphertext.length,
                "Ciphertext must be exactly 16 bytes longer (SIV tag)");
    }

    @Test
    void encrypt_emptyPlaintext_ciphertextIs16Bytes() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);

        final byte[] ciphertext = encryptor.encrypt(new byte[0], null);

        assertEquals(16, ciphertext.length,
                "Empty plaintext produces 16-byte ciphertext (SIV tag only)");
    }

    // ── Wrong key ───────────────────────────────────────────────────────────

    @Test
    void decrypt_wrongKey_throwsSecurityException() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        final byte[] plaintext = "secret".getBytes();
        final byte[] ad = "context".getBytes();
        final byte[] ciphertext = encryptor.encrypt(plaintext, ad);

        try (final OffHeapKeyMaterial otherKey = OffHeapKeyMaterial.of(key512Alt())) {
            final AesSivEncryptor otherEncryptor = new AesSivEncryptor(otherKey);
            assertThrows(SecurityException.class, () -> otherEncryptor.decrypt(ciphertext, ad),
                    "Decryption with wrong key must throw SecurityException");
        }
    }

    // ── Input validation ────────────────────────────────────────────────────

    @Test
    void encrypt_nullPlaintext_throwsNullPointerException() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        assertThrows(NullPointerException.class, () -> encryptor.encrypt(null, null));
    }

    @Test
    void decrypt_nullCiphertext_throwsNullPointerException() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        assertThrows(NullPointerException.class, () -> encryptor.decrypt(null, null));
    }

    @Test
    void decrypt_ciphertextTooShort_throwsIllegalArgumentException() {
        final AesSivEncryptor encryptor = new AesSivEncryptor(keyHolder);
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(new byte[15], null));
    }

    @Test
    void constructor_rejects256BitKey() {
        final byte[] smallKey = new byte[32];
        Arrays.fill(smallKey, (byte) 0x42);
        try (final OffHeapKeyMaterial holder256 = OffHeapKeyMaterial.of(smallKey)) {
            assertThrows(IllegalArgumentException.class, () -> new AesSivEncryptor(holder256));
        }
    }
}

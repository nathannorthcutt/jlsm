package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BoldyrevaOpeEncryptor}: order-preserving encryption via hypergeometric sampling.
 */
class BoldyrevaOpeEncryptorTest {

    private static final long DOMAIN = 100;
    private static final long RANGE = 10_000;

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
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);

        for (long p = 1; p <= 20; p++) {
            final long ct = encryptor.encrypt(p);
            final long recovered = encryptor.decrypt(ct);
            assertEquals(p, recovered, "Round-trip failed for plaintext " + p);
        }
    }

    @Test
    void encryptDecrypt_boundaryValues() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);

        final long ctFirst = encryptor.encrypt(1);
        assertEquals(1, encryptor.decrypt(ctFirst));

        final long ctLast = encryptor.encrypt(DOMAIN);
        assertEquals(DOMAIN, encryptor.decrypt(ctLast));
    }

    // ── Order preservation ──────────────────────────────────────────────────

    @Test
    void encrypt_preservesOrder() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);

        long prevCt = Long.MIN_VALUE;
        for (long p = 1; p <= DOMAIN; p++) {
            final long ct = encryptor.encrypt(p);
            assertTrue(ct > prevCt, "Order not preserved: encrypt(" + (p - 1) + ")=" + prevCt
                    + " >= encrypt(" + p + ")=" + ct);
            prevCt = ct;
        }
    }

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    void encrypt_isDeterministic_sameKey() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);

        final long ct1 = encryptor.encrypt(42);
        final long ct2 = encryptor.encrypt(42);

        assertEquals(ct1, ct2, "Same key and plaintext must produce same ciphertext");
    }

    // ── Different key ───────────────────────────────────────────────────────

    @Test
    void encrypt_differentKey_producesDifferentCiphertext() {
        final BoldyrevaOpeEncryptor enc1 = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);
        final long ct1 = enc1.encrypt(50);

        try (final EncryptionKeyHolder otherKey = EncryptionKeyHolder.of(key256Alt())) {
            final BoldyrevaOpeEncryptor enc2 = new BoldyrevaOpeEncryptor(otherKey, DOMAIN, RANGE);
            final long ct2 = enc2.encrypt(50);

            assertNotEquals(ct1, ct2, "Different keys should produce different ciphertext");
        }
    }

    // ── Ciphertext range ────────────────────────────────────────────────────

    @Test
    void encrypt_ciphertextWithinRange() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);

        for (long p = 1; p <= DOMAIN; p++) {
            final long ct = encryptor.encrypt(p);
            assertTrue(ct >= 1 && ct <= RANGE,
                    "Ciphertext " + ct + " out of range [1.." + RANGE + "]");
        }
    }

    // ── Input validation ────────────────────────────────────────────────────

    @Test
    void encrypt_plaintextBelowDomain_throwsIllegalArgumentException() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(0));
    }

    @Test
    void encrypt_plaintextAboveDomain_throwsIllegalArgumentException() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(DOMAIN + 1));
    }

    @Test
    void decrypt_ciphertextBelowRange_throwsIllegalArgumentException() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(0));
    }

    @Test
    void decrypt_ciphertextAboveRange_throwsIllegalArgumentException() {
        final BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN, RANGE);
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(RANGE + 1));
    }

    @Test
    void constructor_rejectsRangeNotGreaterThanDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoldyrevaOpeEncryptor(keyHolder, 100, 100));
    }

    @Test
    void constructor_rejectsRangeSmallerThanDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoldyrevaOpeEncryptor(keyHolder, 100, 50));
    }

    @Test
    void constructor_rejectsZeroDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoldyrevaOpeEncryptor(keyHolder, 0, 100));
    }
}

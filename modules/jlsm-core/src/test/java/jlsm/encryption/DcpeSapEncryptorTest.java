package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tests for {@link DcpeSapEncryptor}: authenticated distance-comparison-preserving encryption
 * (Scale-And-Perturb with detached HMAC-SHA256 tag).
 */
class DcpeSapEncryptorTest {

    private static final int DIMS = 8;
    private static final byte[] FIELD_AD = "embedding".getBytes(StandardCharsets.UTF_8);

    private EncryptionKeyHolder keyHolder;

    private static byte[] key256() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
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

    private static float[] vector(float... values) {
        return values;
    }

    private static double l2Distance(float[] a, float[] b) {
        assert a.length == b.length;
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            final double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    // ── Round-trip ──────────────────────────────────────────────────────────

    // @spec encryption.primitives-variants.R28 — round-trip recovery via seed + tag
    @Test
    void encryptDecrypt_roundTrip() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);
        final float[] recovered = encryptor.decrypt(ev, FIELD_AD);

        assertArrayEquals(original, recovered, 1e-4f,
                "Decrypted vector must match original within floating-point tolerance");
    }

    @Test
    void encryptDecrypt_roundTrip_zeroVector() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = new float[DIMS];

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);
        final float[] recovered = encryptor.decrypt(ev, FIELD_AD);

        assertArrayEquals(original, recovered, 1e-4f);
    }

    // ── Dimensionality preservation ─────────────────────────────────────────

    // @spec encryption.primitives-variants.R24 — dimensionality preserved
    @Test
    void encrypt_preservesDimensionality() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        assertEquals(DIMS, ev.values().length,
                "Encrypted vector must have same dimensionality as input");
    }

    // ── Distance approximate preservation ───────────────────────────────────

    // @spec encryption.primitives-variants.R26 — approximate distance preservation
    @Test
    void encrypt_approximatelyPreservesDistanceOrdering() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, 3);
        final float[] a = vector(0.0f, 0.0f, 0.0f);
        final float[] b = vector(1.0f, 0.0f, 0.0f); // close to a
        final float[] c = vector(10.0f, 10.0f, 10.0f); // far from a

        final DcpeSapEncryptor.EncryptedVector ea = encryptor.encrypt(a, FIELD_AD);
        final DcpeSapEncryptor.EncryptedVector eb = encryptor.encrypt(b, FIELD_AD);
        final DcpeSapEncryptor.EncryptedVector ec = encryptor.encrypt(c, FIELD_AD);

        final double distAB = l2Distance(ea.values(), eb.values());
        final double distAC = l2Distance(ea.values(), ec.values());

        assertTrue(distAB < distAC, "Encrypted distance(a,b)=" + distAB
                + " should be < distance(a,c)=" + distAC + " (approximate distance preservation)");
    }

    // ── Non-determinism ─────────────────────────────────────────────────────

    // @spec encryption.primitives-variants.R27 — each encryption uses a fresh seed
    @Test
    void encrypt_differentSeedsPerEncryption() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev1 = encryptor.encrypt(original, FIELD_AD);
        final DcpeSapEncryptor.EncryptedVector ev2 = encryptor.encrypt(original, FIELD_AD);

        assertNotEquals(ev1.seed(), ev2.seed(), "Each encryption should use a different seed");
        assertFalse(Arrays.equals(ev1.values(), ev2.values()),
                "Different seeds should produce different encrypted values");
    }

    // ── Encrypted vector usable for distance computation ────────────────────

    // @spec encryption.primitives-variants.R29 — encrypted output must be finite
    @Test
    void encryptedVector_hasFloatArrayFormat() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        assertNotNull(ev.values());
        assertEquals(DIMS, ev.values().length);
        for (final float v : ev.values()) {
            assertTrue(Float.isFinite(v), "Encrypted value must be finite: " + v);
        }
    }

    // @spec encryption.primitives-variants.R29 — non-finite output rejected with IllegalStateException
    @Test
    void encrypt_rejectsOverflowToInfinity() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] enormous = new float[DIMS];
        Arrays.fill(enormous, Float.MAX_VALUE);

        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(enormous, FIELD_AD),
                "Scaling Float.MAX_VALUE by >1 must overflow to Infinity and be rejected");
    }

    // ── MAC authentication ──────────────────────────────────────────────────

    // @spec encryption.primitives-variants.R55 — MAC binds to associated data (field name)
    @Test
    void decrypt_wrongAssociatedDataRejected() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        final byte[] otherField = "salary".getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class, () -> encryptor.decrypt(ev, otherField),
                "Decryption with different AD must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R55 — wrong key produces MAC mismatch
    @Test
    void decrypt_wrongKeyRejected() {
        final DcpeSapEncryptor encryptorA = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptorA.encrypt(original, FIELD_AD);

        final byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 0xCC);
        try (var otherHolder = EncryptionKeyHolder.of(otherKey)) {
            final DcpeSapEncryptor encryptorB = new DcpeSapEncryptor(otherHolder, DIMS);
            assertThrows(SecurityException.class, () -> encryptorB.decrypt(ev, FIELD_AD),
                    "Decryption with wrong key must throw SecurityException");
        }
    }

    // @spec encryption.primitives-variants.R55 — tampered tag rejected
    @Test
    void decrypt_tamperedTagRejected() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        final byte[] tamperedTag = ev.tag();
        tamperedTag[5] ^= (byte) 0xFF;
        final DcpeSapEncryptor.EncryptedVector tamperedEv = new DcpeSapEncryptor.EncryptedVector(
                ev.seed(), ev.values(), tamperedTag);

        assertThrows(SecurityException.class, () -> encryptor.decrypt(tamperedEv, FIELD_AD),
                "Tampered tag must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R55 — tampered ciphertext values rejected
    @Test
    void decrypt_tamperedValuesRejected() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        final float[] tamperedValues = ev.values();
        tamperedValues[3] = Float.intBitsToFloat(Float.floatToRawIntBits(tamperedValues[3]) ^ 0x42);
        final DcpeSapEncryptor.EncryptedVector tamperedEv = new DcpeSapEncryptor.EncryptedVector(
                ev.seed(), tamperedValues, ev.tag());

        assertThrows(SecurityException.class, () -> encryptor.decrypt(tamperedEv, FIELD_AD),
                "Tampered values must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R55 — tampered seed rejected
    @Test
    void decrypt_tamperedSeedRejected() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        final DcpeSapEncryptor.EncryptedVector tamperedEv = new DcpeSapEncryptor.EncryptedVector(
                ev.seed() ^ 0xDEADBEEFL, ev.values(), ev.tag());

        assertThrows(SecurityException.class, () -> encryptor.decrypt(tamperedEv, FIELD_AD),
                "Tampered seed must throw SecurityException");
    }

    // ── Blob encoding ──────────────────────────────────────────────────────

    // @spec serialization.encrypted-field-serialization.R4, F41.R22 — blob round-trips through toBlob/fromBlob
    @Test
    void blob_roundTrip() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);
        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original, FIELD_AD);

        final byte[] blob = DcpeSapEncryptor.toBlob(ev);
        assertEquals(
                DcpeSapEncryptor.SEED_BYTES + DIMS * DcpeSapEncryptor.BYTES_PER_FLOAT
                        + DcpeSapEncryptor.MAC_TAG_BYTES,
                blob.length, "Blob length must be 8 + dims*4 + 16 bytes");

        final DcpeSapEncryptor.EncryptedVector decoded = DcpeSapEncryptor.fromBlob(blob, DIMS);
        final float[] recovered = encryptor.decrypt(decoded, FIELD_AD);
        assertArrayEquals(original, recovered, 1e-4f);
    }

    // @spec encryption.primitives-variants.R49 — blob with wrong length rejected
    @Test
    void blob_wrongLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DcpeSapEncryptor.fromBlob(new byte[10], DIMS));
    }

    // ── Input validation ────────────────────────────────────────────────────

    @Test
    void encrypt_wrongDimensions_throwsIllegalArgumentException() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        assertThrows(IllegalArgumentException.class,
                () -> encryptor.encrypt(new float[DIMS + 1], FIELD_AD));
    }

    @Test
    void encrypt_nullVector_throwsNullPointerException() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        assertThrows(NullPointerException.class, () -> encryptor.encrypt(null, FIELD_AD));
    }

    @Test
    void encrypt_nullAssociatedData_throwsNullPointerException() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        assertThrows(NullPointerException.class, () -> encryptor.encrypt(new float[DIMS], null));
    }

    @Test
    void constructor_rejectsZeroDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new DcpeSapEncryptor(keyHolder, 0));
    }

    @Test
    void constructor_rejectsNegativeDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new DcpeSapEncryptor(keyHolder, -1));
    }
}

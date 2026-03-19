package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.table.internal.DcpeSapEncryptor;
import jlsm.table.internal.EncryptionKeyHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Tests for {@link DcpeSapEncryptor}: distance-comparison-preserving encryption
 * (Scale-And-Perturb).
 */
class DcpeSapEncryptorTest {

    private static final int DIMS = 8;

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

    @Test
    void encryptDecrypt_roundTrip() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original);
        final float[] recovered = encryptor.decrypt(ev.values(), ev.seed());

        assertArrayEquals(original, recovered, 1e-4f,
                "Decrypted vector must match original within floating-point tolerance");
    }

    @Test
    void encryptDecrypt_roundTrip_zeroVector() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = new float[DIMS];

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original);
        final float[] recovered = encryptor.decrypt(ev.values(), ev.seed());

        assertArrayEquals(original, recovered, 1e-4f);
    }

    // ── Dimensionality preservation ─────────────────────────────────────────

    @Test
    void encrypt_preservesDimensionality() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original);

        assertEquals(DIMS, ev.values().length,
                "Encrypted vector must have same dimensionality as input");
    }

    // ── Distance approximate preservation ───────────────────────────────────

    @Test
    void encrypt_approximatelyPreservesDistanceOrdering() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, 3);
        final float[] a = vector(0.0f, 0.0f, 0.0f);
        final float[] b = vector(1.0f, 0.0f, 0.0f); // close to a
        final float[] c = vector(10.0f, 10.0f, 10.0f); // far from a

        final DcpeSapEncryptor.EncryptedVector ea = encryptor.encrypt(a);
        final DcpeSapEncryptor.EncryptedVector eb = encryptor.encrypt(b);
        final DcpeSapEncryptor.EncryptedVector ec = encryptor.encrypt(c);

        final double distAB = l2Distance(ea.values(), eb.values());
        final double distAC = l2Distance(ea.values(), ec.values());

        assertTrue(distAB < distAC, "Encrypted distance(a,b)=" + distAB
                + " should be < distance(a,c)=" + distAC + " (approximate distance preservation)");
    }

    // ── Non-determinism ─────────────────────────────────────────────────────

    @Test
    void encrypt_differentSeedsPerEncryption() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev1 = encryptor.encrypt(original);
        final DcpeSapEncryptor.EncryptedVector ev2 = encryptor.encrypt(original);

        assertNotEquals(ev1.seed(), ev2.seed(), "Each encryption should use a different seed");
        assertFalse(Arrays.equals(ev1.values(), ev2.values()),
                "Different seeds should produce different encrypted values");
    }

    // ── Encrypted vector usable for distance computation ────────────────────

    @Test
    void encryptedVector_hasFloatArrayFormat() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        final float[] original = vector(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f);

        final DcpeSapEncryptor.EncryptedVector ev = encryptor.encrypt(original);

        assertNotNull(ev.values());
        assertEquals(DIMS, ev.values().length);
        // Verify values are finite (usable for distance computation)
        for (final float v : ev.values()) {
            assertTrue(Float.isFinite(v), "Encrypted value must be finite: " + v);
        }
    }

    // ── Input validation ────────────────────────────────────────────────────

    @Test
    void encrypt_wrongDimensions_throwsIllegalArgumentException() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(new float[DIMS + 1]));
    }

    @Test
    void encrypt_nullVector_throwsNullPointerException() {
        final DcpeSapEncryptor encryptor = new DcpeSapEncryptor(keyHolder, DIMS);
        assertThrows(NullPointerException.class, () -> encryptor.encrypt(null));
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

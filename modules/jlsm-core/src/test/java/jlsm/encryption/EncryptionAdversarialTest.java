package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for encryption primitives — targets bugs found in spec analysis of
 * encrypt-memory-data audit.
 */
class EncryptionAdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static byte[] key256() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static byte[] key512() {
        final byte[] key = new byte[64];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    // ── C1-1: EncryptionKeyHolder.close() race condition ─────────────────────────
    // Two threads racing past the volatile boolean guard both call arena.close(),
    // causing IllegalStateException on the second call.

    @Test
    void keyHolder_concurrentClose_doesNotThrow() throws InterruptedException {
        // Vector: C1-1 — concurrent close() must be idempotent
        final int iterations = 100;
        for (int iter = 0; iter < iterations; iter++) {
            final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
            final CountDownLatch startGate = new CountDownLatch(1);
            final AtomicInteger errors = new AtomicInteger(0);

            final Thread t1 = Thread.ofVirtual().start(() -> {
                try {
                    startGate.await();
                    holder.close();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            final Thread t2 = Thread.ofVirtual().start(() -> {
                try {
                    startGate.await();
                    holder.close();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });

            startGate.countDown();
            t1.join(5000);
            t2.join(5000);

            assertEquals(0, errors.get(),
                    "Concurrent close() must not throw (iteration " + iter + ")");
        }
    }

    // ── C2-1: AesGcmEncryptor key bytes not zeroed ───────────────────────────────
    // The byte[] returned by getKeyBytes() is passed to SecretKeySpec but never zeroed.
    // We can't directly test the internal array, but we can verify the contract:
    // getKeyBytes() should return a copy that the caller must zero.
    // This test documents the expected behavior.

    @Test
    void aesGcmEncryptor_getKeyBytesCopyIsIndependent() {
        // Vector: C2-1 — verify getKeyBytes returns independent copies
        final byte[] keyMaterial = key256();
        final byte[] expected = Arrays.copyOf(keyMaterial, keyMaterial.length);
        try (final EncryptionKeyHolder holder = EncryptionKeyHolder.of(keyMaterial)) {
            final byte[] copy1 = holder.getKeyBytes();
            // Zero copy1 (as the contract requires)
            Arrays.fill(copy1, (byte) 0);
            // Second call should still return the original key
            final byte[] copy2 = holder.getKeyBytes();
            assertArrayEquals(expected, copy2,
                    "Zeroing one copy must not affect subsequent getKeyBytes() calls");
        }
    }

    // ── C2-4: DcpeSapEncryptor.decrypt() does not validate dimensions ────────────

    private static final byte[] AD = "adversarial"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void dcpeSapEncryptor_decrypt_wrongDimensions_throws() {
        // Vector: C2-4 — decrypt with wrong-sized array should throw, not silently corrupt
        final byte[] keyMaterial = key256();
        final DcpeSapEncryptor enc = new DcpeSapEncryptor(
                EncryptionKeyHolder.of(Arrays.copyOf(keyMaterial, keyMaterial.length)), 8);

        final float[] vector = new float[]{ 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f };
        final DcpeSapEncryptor.EncryptedVector ev = enc.encrypt(vector, AD);

        // Construct a wrong-sized EncryptedVector (truncated to 4 dims, tag copied as-is)
        final float[] wrongSize = Arrays.copyOf(ev.values(), 4);
        final DcpeSapEncryptor.EncryptedVector wrongEv = new DcpeSapEncryptor.EncryptedVector(
                ev.seed(), wrongSize, ev.tag());
        assertThrows(IllegalArgumentException.class, () -> enc.decrypt(wrongEv, AD),
                "decrypt with wrong dimensions must throw, not silently produce wrong results");
    }

    @Test
    void dcpeSapEncryptor_decrypt_longerThanExpected_throws() {
        // Vector: C2-4 — decrypt with too many elements should also be rejected
        final byte[] keyMaterial = key256();
        final DcpeSapEncryptor enc = new DcpeSapEncryptor(
                EncryptionKeyHolder.of(Arrays.copyOf(keyMaterial, keyMaterial.length)), 4);

        final float[] vector = new float[]{ 1.0f, 2.0f, 3.0f, 4.0f };
        final DcpeSapEncryptor.EncryptedVector ev = enc.encrypt(vector, AD);

        final float[] tooLong = new float[8];
        System.arraycopy(ev.values(), 0, tooLong, 0, 4);
        final DcpeSapEncryptor.EncryptedVector tooLongEv = new DcpeSapEncryptor.EncryptedVector(
                ev.seed(), tooLong, ev.tag());
        assertThrows(IllegalArgumentException.class, () -> enc.decrypt(tooLongEv, AD),
                "decrypt with too many elements must throw");
    }

    // ── C2-5: EncryptedVector mutable array exposed via accessor ─────────────────

    @Test
    void encryptedVector_valuesAccessor_exposesInternalArray() {
        // Vector: C2-5 — mutating the returned array should not corrupt the record
        final byte[] keyMaterial = key256();
        final DcpeSapEncryptor enc = new DcpeSapEncryptor(
                EncryptionKeyHolder.of(Arrays.copyOf(keyMaterial, keyMaterial.length)), 4);

        final float[] vector = new float[]{ 1.0f, 2.0f, 3.0f, 4.0f };
        final DcpeSapEncryptor.EncryptedVector ev = enc.encrypt(vector, AD);

        final float[] original = Arrays.copyOf(ev.values(), ev.values().length);

        // Mutate through accessor
        ev.values()[0] = Float.NaN;

        // The record should be defensively copied — values should be unchanged
        assertArrayEquals(original, ev.values(),
                "EncryptedVector.values() must return a defensive copy, not the internal array");
    }

    // ── C2-2: BoldyrevaOpeEncryptor key bytes not zeroed ─────────────────────────
    // This is a security concern but can't be directly tested without reflection.
    // We document it via a test that verifies the encryptor works correctly after
    // key holder is closed (which it shouldn't if it stored a reference).

    @Test
    void boldyrevaOpeEncryptor_worksAfterKeyHolderClosed() {
        // Vector: C2-2 — encryptor should work after key holder is closed
        // (it copies key material at construction, not stores a reference)
        final EncryptionKeyHolder holder = EncryptionKeyHolder.of(key256());
        final BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(holder, 100, 1000);
        holder.close();

        // Should still work — key material was copied at construction
        final long encrypted = ope.encrypt(50);
        assertTrue(encrypted >= 1 && encrypted <= 1000);
    }
}

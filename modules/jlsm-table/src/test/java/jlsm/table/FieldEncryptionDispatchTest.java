package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.table.internal.EncryptionKeyHolder;
import jlsm.table.internal.FieldEncryptionDispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FieldEncryptionDispatchTest {

    private EncryptionKeyHolder keyHolder;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 0xAB);
        keyHolder = EncryptionKeyHolder.of(key);
    }

    @AfterEach
    void tearDown() {
        if (keyHolder != null) {
            keyHolder.close();
        }
    }

    // ── Construction with null keyHolder → all entries null ──────────────

    @Test
    void nullKeyHolder_allEncryptorsNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        var dispatch = new FieldEncryptionDispatch(schema, null);

        assertNull(dispatch.encryptorFor(0), "encryptor should be null when keyHolder is null");
        assertNull(dispatch.encryptorFor(1), "encryptor should be null when keyHolder is null");
        assertNull(dispatch.decryptorFor(0), "decryptor should be null when keyHolder is null");
        assertNull(dispatch.decryptorFor(1), "decryptor should be null when keyHolder is null");
    }

    // ── Mixed schema — correct encryptors per field ─────────────────────

    @Test
    void mixedSchema_correctEncryptorsPerField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("plain", FieldType.string())
                .field("det", FieldType.string(), EncryptionSpec.deterministic())
                .field("opq", FieldType.string(), EncryptionSpec.opaque()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);

        assertNull(dispatch.encryptorFor(0), "plain field should have no encryptor");
        assertNull(dispatch.decryptorFor(0), "plain field should have no decryptor");
        assertNotNull(dispatch.encryptorFor(1), "deterministic field should have encryptor");
        assertNotNull(dispatch.decryptorFor(1), "deterministic field should have decryptor");
        assertNotNull(dispatch.encryptorFor(2), "opaque field should have encryptor");
        assertNotNull(dispatch.decryptorFor(2), "opaque field should have decryptor");
    }

    // ── Deterministic field produces deterministic ciphertext ────────────

    @Test
    void deterministicField_sameInputProducesSameCiphertext() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var encryptor = dispatch.encryptorFor(0);
        assertNotNull(encryptor);

        byte[] plaintext = "hello world".getBytes();
        byte[] ct1 = encryptor.encrypt(plaintext);
        byte[] ct2 = encryptor.encrypt(plaintext);

        assertArrayEquals(ct1, ct2, "Deterministic encryption must produce same ciphertext");
    }

    // ── Deterministic encrypt/decrypt round trip ────────────────────────

    @Test
    void deterministicField_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = "round-trip-test".getBytes();
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] recovered = dispatch.decryptorFor(0).decrypt(ciphertext);

        assertArrayEquals(plaintext, recovered);
    }

    // ── Opaque field produces non-deterministic ciphertext ──────────────

    @Test
    void opaqueField_differentCiphertextEachTime() {
        // AES-GCM requires 32-byte key
        byte[] key32 = new byte[32];
        Arrays.fill(key32, (byte) 0xCD);
        try (var kh32 = EncryptionKeyHolder.of(key32)) {
            JlsmSchema schema = JlsmSchema.builder("test", 1)
                    .field("opq", FieldType.string(), EncryptionSpec.opaque()).build();

            var dispatch = new FieldEncryptionDispatch(schema, kh32);
            var encryptor = dispatch.encryptorFor(0);
            assertNotNull(encryptor);

            byte[] plaintext = "hello world".getBytes();
            byte[] ct1 = encryptor.encrypt(plaintext);
            byte[] ct2 = encryptor.encrypt(plaintext);

            assertFalse(Arrays.equals(ct1, ct2),
                    "Opaque encryption should produce different ciphertext each time");
        }
    }

    // ── Opaque encrypt/decrypt round trip ───────────────────────────────

    @Test
    void opaqueField_roundTrip() {
        byte[] key32 = new byte[32];
        Arrays.fill(key32, (byte) 0xCD);
        try (var kh32 = EncryptionKeyHolder.of(key32)) {
            JlsmSchema schema = JlsmSchema.builder("test", 1)
                    .field("opq", FieldType.string(), EncryptionSpec.opaque()).build();

            var dispatch = new FieldEncryptionDispatch(schema, kh32);
            byte[] plaintext = "opaque-round-trip".getBytes();
            byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);
            byte[] recovered = dispatch.decryptorFor(0).decrypt(ciphertext);

            assertArrayEquals(plaintext, recovered);
        }
    }

    // ── Unencrypted field returns null encryptor ─────────────────────────

    @Test
    void unencryptedField_returnsNullEncryptor() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("plain", FieldType.string())
                .build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);

        assertNull(dispatch.encryptorFor(0));
        assertNull(dispatch.decryptorFor(0));
    }

    // ── Thread safety ───────────────────────────────────────────────────

    @Test
    void encryptorsUsableFromMultipleThreads() throws Exception {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("det", FieldType.string(), EncryptionSpec.deterministic()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var encryptor = dispatch.encryptorFor(0);
        assertNotNull(encryptor);

        byte[] plaintext = "concurrent-test".getBytes();
        byte[] expected = encryptor.encrypt(plaintext);

        final int threadCount = 4;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < 50; i++) {
                        byte[] ct = encryptor.encrypt(plaintext);
                        assertArrayEquals(expected, ct);
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join(5000);
        }

        if (failure.get() != null) {
            fail("Thread safety test failed: " + failure.get().getMessage());
        }
    }

    // ── Domain separation: same value encrypted under different field names ──

    @Test
    void deterministicField_domainSeparationByFieldName() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("field_a", FieldType.string(), EncryptionSpec.deterministic())
                .field("field_b", FieldType.string(), EncryptionSpec.deterministic()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);

        byte[] plaintext = "same-value".getBytes();
        byte[] ctA = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] ctB = dispatch.encryptorFor(1).encrypt(plaintext);

        assertFalse(Arrays.equals(ctA, ctB),
                "Same value under different field names should produce different ciphertext");
    }
}

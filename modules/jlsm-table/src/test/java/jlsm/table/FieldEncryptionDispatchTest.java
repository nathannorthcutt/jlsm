package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.encryption.internal.OffHeapKeyMaterial;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.FieldEncryptionDispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FieldEncryptionDispatchTest {

    private OffHeapKeyMaterial keyHolder;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 0xAB);
        keyHolder = OffHeapKeyMaterial.of(key);
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
        try (var kh32 = OffHeapKeyMaterial.of(key32)) {
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
        try (var kh32 = OffHeapKeyMaterial.of(key32)) {
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

    // ── OPE MAC wrapping ────────────────────────────────────────────────────
    //
    // F03.R39 amended: OPE ciphertext format is [1B length prefix][8B OPE ciphertext long]
    // [16B detached HMAC-SHA256 tag] = 25 bytes total.
    // F03.R78 amended: MAC binds ciphertext + field name. Wrong key / tampered ciphertext /
    // cross-field substitution must throw SecurityException.
    // F41.R22 amended: on-disk OPE layout matches (DEK version tag is F41 future work).

    // @spec encryption.primitives-variants.R21,R54, encryption.ciphertext-envelope.R1 — OPE
    // envelope is exactly 29 bytes after WU-4 (4B BE DEK version + 1B length + 8B OPE + 16B MAC)
    @Test
    void orderPreservingField_envelopeIs29Bytes() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ (byte) 42 };
        byte[] envelope = dispatch.encryptorFor(0).encrypt(plaintext);

        assertEquals(29, envelope.length,
                "OPE envelope must be 29 bytes (4B DEK version + 1B length + 8B OPE + 16B MAC)");
    }

    // @spec encryption.primitives-variants.R22,R54 — round-trip works when MAC verifies
    @Test
    void orderPreservingField_roundTrip() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("score", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving())
                .build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);

        byte[] plaintext = new byte[]{ 0x01, 0x23 };
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] recovered = dispatch.decryptorFor(0).decrypt(ciphertext);

        assertArrayEquals(plaintext, recovered);
    }

    // @spec encryption.primitives-variants.R54 — tampered MAC bytes rejected with SecurityException
    @Test
    void orderPreservingField_tamperedMacRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 17 };
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);

        // After WU-4 prefix: tag occupies bytes [13..28]; flip byte at position 24 (was 20).
        ciphertext[24] ^= (byte) 0xFF;

        assertThrows(SecurityException.class, () -> dispatch.decryptorFor(0).decrypt(ciphertext),
                "Tampered MAC must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R54 — tampered OPE ciphertext bytes rejected with
    // SecurityException
    @Test
    void orderPreservingField_tamperedOpePortionRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 5 };
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);

        // After WU-4 prefix: OPE long occupies bytes [5..12]; flip byte at position 8 (was 4).
        ciphertext[8] ^= (byte) 0xFF;

        assertThrows(SecurityException.class, () -> dispatch.decryptorFor(0).decrypt(ciphertext),
                "Tampered OPE ciphertext must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R54 — tampered length prefix byte rejected with
    // SecurityException
    @Test
    void orderPreservingField_tamperedLengthPrefixRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 9 };
        byte[] ciphertext = dispatch.encryptorFor(0).encrypt(plaintext);

        // After WU-4 prefix: OPE length prefix is at byte 4 (bytes [0..3] are the DEK version
        // prefix). Tampering byte 4 changes the inner OPE format and must trip the MAC check.
        ciphertext[4] = (byte) (ciphertext[4] ^ 0x01);

        assertThrows(SecurityException.class, () -> dispatch.decryptorFor(0).decrypt(ciphertext),
                "Tampered length prefix must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R54 — decryption with a different key rejects via MAC
    @Test
    void orderPreservingField_wrongKeyRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var encryptDispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 22 };
        byte[] ciphertext = encryptDispatch.encryptorFor(0).encrypt(plaintext);

        byte[] otherKey = new byte[64];
        Arrays.fill(otherKey, (byte) 0xCC);
        try (var otherHolder = OffHeapKeyMaterial.of(otherKey)) {
            var decryptDispatch = new FieldEncryptionDispatch(schema, otherHolder);
            assertThrows(SecurityException.class,
                    () -> decryptDispatch.decryptorFor(0).decrypt(ciphertext),
                    "Decryption with wrong key must throw SecurityException");
        }
    }

    // @spec encryption.primitives-variants.R54 — cross-field substitution rejected (MAC binds to
    // field name)
    @Test
    void orderPreservingField_crossFieldSubstitutionRejected() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("a", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving())
                .field("b", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 7 };
        byte[] ctA = dispatch.encryptorFor(0).encrypt(plaintext);

        assertThrows(SecurityException.class, () -> dispatch.decryptorFor(1).decrypt(ctA),
                "Using field A's ciphertext on field B's decryptor must throw SecurityException");
    }

    // @spec encryption.primitives-variants.R54 — two encryptions of the same plaintext in the same
    // field produce
    // identical ciphertext (OPE + deterministic MAC = deterministic overall)
    @Test
    void orderPreservingField_deterministicAcrossCalls() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving()).build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        byte[] plaintext = new byte[]{ 33 };
        byte[] ct1 = dispatch.encryptorFor(0).encrypt(plaintext);
        byte[] ct2 = dispatch.encryptorFor(0).encrypt(plaintext);

        assertArrayEquals(ct1, ct2,
                "OPE + deterministic MAC must produce identical ciphertext on repeated calls");
    }

    // @spec encryption.primitives-variants.R19 — OPE preserves ordering on the 8-byte OPE portion;
    // MAC does not
    // disturb this property because range comparisons operate on bytes [1..8].
    @Test
    void orderPreservingField_preservesOrderOnOpePortion() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("level", FieldType.Primitive.INT16, EncryptionSpec.orderPreserving())
                .build();

        var dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        var enc = dispatch.encryptorFor(0);

        byte[] p1 = new byte[]{ 0x00, 0x01 };
        byte[] p2 = new byte[]{ 0x00, 0x42 };
        byte[] p3 = new byte[]{ 0x7F, 0x00 };

        long ope1 = readOpePortion(enc.encrypt(p1));
        long ope2 = readOpePortion(enc.encrypt(p2));
        long ope3 = readOpePortion(enc.encrypt(p3));

        assertTrue(ope1 < ope2, "OPE must preserve p1 < p2 on the 8-byte portion");
        assertTrue(ope2 < ope3, "OPE must preserve p2 < p3 on the 8-byte portion");
    }

    /**
     * Extracts the 8-byte OPE ciphertext long (big-endian) from bytes [5..12] of the 29-byte WU-4
     * envelope (skips 4B DEK version prefix + 1B length prefix).
     */
    private static long readOpePortion(byte[] ciphertext) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            // 4B DEK version + 1B length = 5 leading bytes before the encrypted long
            v = (v << 8) | (ciphertext[5 + i] & 0xFFL);
        }
        return v;
    }
}

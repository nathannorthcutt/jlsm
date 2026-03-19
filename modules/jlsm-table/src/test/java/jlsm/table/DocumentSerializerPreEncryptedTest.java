package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import jlsm.core.io.MemorySerializer;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentSerializerPreEncryptedTest {

    private EncryptionKeyHolder keyHolder64;

    @BeforeEach
    void setUp() {
        byte[] key64 = new byte[64];
        Arrays.fill(key64, (byte) 0xAB);
        keyHolder64 = EncryptionKeyHolder.of(key64);
    }

    @AfterEach
    void tearDown() {
        if (keyHolder64 != null) {
            keyHolder64.close();
        }
    }

    // ── Pre-encrypted round-trip: valid ciphertext → correct plaintext ──

    @Test
    void preEncrypted_roundTrip_deterministicField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("age", FieldType.int32()).build();

        // First, encrypt "Alice" using the same mechanism the serializer would use
        byte[] key64Copy = keyHolder64.getKeyBytes();
        byte[] sivKey = new byte[64];
        System.arraycopy(key64Copy, 0, sivKey, 0, 32);
        System.arraycopy(key64Copy, 0, sivKey, 32, 32);
        Arrays.fill(key64Copy, (byte) 0);
        EncryptionKeyHolder sivKeyHolder = EncryptionKeyHolder.of(sivKey);
        AesSivEncryptor siv = new AesSivEncryptor(sivKeyHolder);

        // Serialize "Alice" to bytes the same way DocumentSerializer does
        byte[] plainBytes = "Alice".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // The serializer serializes the field value (varint length + UTF-8 bytes)
        byte[] serializedPlain = new byte[1 + plainBytes.length]; // varint(5) = 1 byte
        serializedPlain[0] = (byte) plainBytes.length;
        System.arraycopy(plainBytes, 0, serializedPlain, 1, plainBytes.length);
        byte[] associatedData = "name".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ciphertext = siv.encrypt(serializedPlain, associatedData);

        sivKeyHolder.close();

        // Create pre-encrypted document
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "name", ciphertext, "age", 30);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Alice", out.getString("name"));
        assertEquals(30, out.getInt("age"));
    }

    // ── Pre-encrypted with invalid ciphertext length → throws ───────────

    @Test
    void preEncrypted_invalidCiphertextLength_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();

        // AES-SIV requires >= 16 bytes; provide only 5
        byte[] tooShort = new byte[5];
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "secret", tooShort);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        assertThrows(IllegalArgumentException.class, () -> ser.serialize(doc));
    }

    // ── Pre-encrypted with null ciphertext on encrypted field → throws ──

    @Test
    void preEncrypted_nullCiphertextOnEncryptedField_roundTripsAsNull() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();

        // null is always allowed — means the field is absent
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "secret", null);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);
        assertTrue(out.isNull("secret"));
    }

    // ── Mixed: encrypted fields pass through, unencrypted encode normally ─

    @Test
    void preEncrypted_mixedSchema_encryptedPassThroughUnencryptedNormal() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()) // unencrypted
                .field("email", FieldType.string(), EncryptionSpec.deterministic()) // encrypted
                .field("age", FieldType.int32()) // unencrypted
                .build();

        // Encrypt "bob@test.com" for the email field
        byte[] key64Copy = keyHolder64.getKeyBytes();
        byte[] sivKey = new byte[64];
        System.arraycopy(key64Copy, 0, sivKey, 0, 32);
        System.arraycopy(key64Copy, 0, sivKey, 32, 32);
        Arrays.fill(key64Copy, (byte) 0);
        EncryptionKeyHolder sivKeyHolder = EncryptionKeyHolder.of(sivKey);
        AesSivEncryptor siv = new AesSivEncryptor(sivKeyHolder);

        byte[] plainBytes = "bob@test.com".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] serializedPlain = new byte[1 + plainBytes.length];
        serializedPlain[0] = (byte) plainBytes.length;
        System.arraycopy(plainBytes, 0, serializedPlain, 1, plainBytes.length);
        byte[] associatedData = "email".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ciphertext = siv.encrypt(serializedPlain, associatedData);

        sivKeyHolder.close();

        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "name", "Alice", "email", ciphertext,
                "age", 25);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Alice", out.getString("name"));
        assertEquals("bob@test.com", out.getString("email"));
        assertEquals(25, out.getInt("age"));
    }

    // ── Non-pre-encrypted document still encrypts on write ──────────────

    @Test
    void nonPreEncrypted_stillEncryptsOnWrite() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic())
                .field("age", FieldType.int32()).build();

        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        assertFalse(doc.isPreEncrypted());

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("Alice", out.getString("name"));
        assertEquals(30, out.getInt("age"));
    }

    // ── Opaque (AES-GCM) pre-encrypted round-trip ───────────────────────

    @Test
    void preEncrypted_roundTrip_opaqueField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();

        // Create a key holder for GCM (32 bytes)
        byte[] key64Copy = keyHolder64.getKeyBytes();
        byte[] gcmKey = Arrays.copyOfRange(key64Copy, 0, 32);
        Arrays.fill(key64Copy, (byte) 0);
        EncryptionKeyHolder gcmKeyHolder = EncryptionKeyHolder.of(gcmKey);
        jlsm.encryption.AesGcmEncryptor gcm = new jlsm.encryption.AesGcmEncryptor(gcmKeyHolder);

        byte[] plainBytes = "top-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] serializedPlain = new byte[1 + plainBytes.length];
        serializedPlain[0] = (byte) plainBytes.length;
        System.arraycopy(plainBytes, 0, serializedPlain, 1, plainBytes.length);
        byte[] ciphertext = gcm.encrypt(serializedPlain);

        gcmKeyHolder.close();

        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "secret", ciphertext);

        MemorySerializer<JlsmDocument> ser = DocumentSerializer.forSchema(schema, keyHolder64);
        MemorySegment bytes = ser.serialize(doc);
        JlsmDocument out = ser.deserialize(bytes);

        assertEquals("top-secret", out.getString("secret"));
    }
}

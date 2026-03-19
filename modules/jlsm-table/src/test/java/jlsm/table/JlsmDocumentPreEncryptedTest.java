package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.Test;

class JlsmDocumentPreEncryptedTest {

    // ── isPreEncrypted flag ────────────────────────────────────────────────

    @Test
    void of_returnsPreEncryptedFalse() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");
        assertFalse(doc.isPreEncrypted());
    }

    @Test
    void preEncrypted_returnsPreEncryptedTrue() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("name", FieldType.string(), EncryptionSpec.deterministic()).build();
        // Pre-encrypted factory accepts byte[] for encrypted fields
        byte[] ciphertext = new byte[32]; // valid AES-SIV ciphertext (>= 16 bytes)
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "name", ciphertext);
        assertTrue(doc.isPreEncrypted());
    }

    // ── Pre-encrypted factory accepts byte[] for encrypted fields ───────

    @Test
    void preEncrypted_acceptsByteArrayForEncryptedField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();
        byte[] ciphertext = new byte[64];
        assertDoesNotThrow(() -> JlsmDocument.preEncrypted(schema, "secret", ciphertext));
    }

    // ── Pre-encrypted factory validates unencrypted fields normally ──────

    @Test
    void preEncrypted_validatesUnencryptedFieldsNormally() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()) // no
                                                                                            // encryption
                                                                                            // —
                                                                                            // EncryptionSpec.NONE
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();
        byte[] ciphertext = new byte[32];
        // name = "Alice" should be type-validated normally
        assertDoesNotThrow(
                () -> JlsmDocument.preEncrypted(schema, "name", "Alice", "secret", ciphertext));
    }

    @Test
    void preEncrypted_wrongTypeForUnencryptedField_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()) // no
                                                                                          // encryption
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();
        byte[] ciphertext = new byte[32];
        // age = "not-an-int" should fail type validation
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, "age", "not-an-int", "secret", ciphertext));
    }

    // ── Mixed schema: encrypted fields get byte[], unencrypted get normal ─

    @Test
    void preEncrypted_mixedSchema() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()) // plaintext
                .field("email", FieldType.string(), EncryptionSpec.deterministic()) // encrypted
                .field("age", FieldType.int32()) // plaintext
                .build();
        byte[] ciphertext = new byte[32];
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "name", "Alice", "email", ciphertext,
                "age", 30);
        assertTrue(doc.isPreEncrypted());
    }

    // ── Null values still allowed for encrypted fields ──────────────────

    @Test
    void preEncrypted_nullValueForEncryptedField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();
        // null is always accepted (absent field)
        assertDoesNotThrow(() -> JlsmDocument.preEncrypted(schema, "secret", null));
    }

    // ── Odd nameValuePairs length → IllegalArgumentException ────────────

    @Test
    void preEncrypted_oddNameValuePairsLength_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, "name"));
    }

    // ── Unknown field name → IllegalArgumentException ───────────────────

    @Test
    void preEncrypted_unknownFieldName_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, "unknown", "value"));
    }

    // ── Null schema → NullPointerException ──────────────────────────────

    @Test
    void preEncrypted_nullSchema_throws() {
        assertThrows(NullPointerException.class,
                () -> JlsmDocument.preEncrypted(null, "name", "value"));
    }
}

package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.Test;

class JlsmDocumentPreEncryptedTest {

    // ── isPreEncrypted flag ────────────────────────────────────────────────

    // @spec F14.R12,R50 — of() produces preEncrypted=false
    @Test
    void of_returnsPreEncryptedFalse() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice");
        assertFalse(doc.isPreEncrypted());
    }

    // @spec F14.R18,R19,R50 — preEncrypted factory sets flag and accepts byte[] ciphertext
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

    // @spec F14.R18 — encrypted field accepts byte[] ciphertext
    @Test
    void preEncrypted_acceptsByteArrayForEncryptedField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.opaque()).build();
        byte[] ciphertext = new byte[64];
        assertDoesNotThrow(() -> JlsmDocument.preEncrypted(schema, "secret", ciphertext));
    }

    // ── Pre-encrypted factory validates unencrypted fields normally ──────

    // @spec F14.R17 — unencrypted fields validated normally via validateType
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

    // @spec F14.R17,R29 — type mismatch on unencrypted field → IAE
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

    // @spec F14.R17,R18,R19 — mixed schema: unencrypted gets normal validation, encrypted gets
    // byte[]
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

    // @spec F14.R18 — null is accepted for encrypted fields (absent field)
    @Test
    void preEncrypted_nullValueForEncryptedField() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("secret", FieldType.string(), EncryptionSpec.deterministic()).build();
        // null is always accepted (absent field)
        assertDoesNotThrow(() -> JlsmDocument.preEncrypted(schema, "secret", null));
    }

    // ── Odd nameValuePairs length → IllegalArgumentException ────────────

    // @spec F14.R14 — preEncrypted rejects odd-length nameValuePairs with IAE
    @Test
    void preEncrypted_oddNameValuePairsLength_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, "name"));
    }

    // ── Unknown field name → IllegalArgumentException ───────────────────

    // @spec F14.R16 — preEncrypted rejects unknown field name with IAE
    @Test
    void preEncrypted_unknownFieldName_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, "unknown", "value"));
    }

    // ── Null schema → NullPointerException ──────────────────────────────

    // @spec F14.R13 — preEncrypted rejects null schema with NPE
    @Test
    void preEncrypted_nullSchema_throws() {
        assertThrows(NullPointerException.class,
                () -> JlsmDocument.preEncrypted(null, "name", "value"));
    }

    // ── Non-String at even index → IllegalArgumentException ─────────────

    // @spec F14.R15 — preEncrypted rejects non-String at even index with IAE
    @Test
    void preEncrypted_nonStringAtEvenIndex_throws() {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.preEncrypted(schema, 42, "value"));
    }

    // ── preEncrypted defensively copies VectorType ──────────────────────

    // @spec F14.R20,R32 — preEncrypted defensively clones VECTOR(FLOAT32) fields (consistent with
    // of())
    @Test
    void preEncrypted_defensivelyClonesFloat32Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT32, 3).build();
        float[] original = { 1.0f, 2.0f, 3.0f };
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "vec", original);
        original[0] = 999.0f;
        assertEquals(1.0f, doc.getFloat32Vector("vec")[0],
                "preEncrypted must clone vector input to match of()'s defensive-copy behavior");
    }

    // @spec F14.R20,R32 — preEncrypted defensively clones VECTOR(FLOAT16) fields
    @Test
    void preEncrypted_defensivelyClonesFloat16Vector() {
        JlsmSchema schema = JlsmSchema.builder("test", 1)
                .vectorField("vec", FieldType.Primitive.FLOAT16, 3).build();
        short[] original = { Float16.fromFloat(1.0f), Float16.fromFloat(2.0f),
                Float16.fromFloat(3.0f) };
        JlsmDocument doc = JlsmDocument.preEncrypted(schema, "vec", original);
        original[0] = Float16.fromFloat(999.0f);
        assertEquals(Float16.fromFloat(1.0f), doc.getFloat16Vector("vec")[0]);
    }
}

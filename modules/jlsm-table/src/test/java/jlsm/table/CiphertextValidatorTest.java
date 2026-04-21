package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.CiphertextValidator;
import org.junit.jupiter.api.Test;

class CiphertextValidatorTest {

    // ── AES-SIV (Deterministic): accept >= 16 bytes ────────────────────────

    @Test
    void deterministic_accepts16Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[16];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void deterministic_acceptsLargerThan16Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[64];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void deterministic_rejectsLessThan16Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[15];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("email"), "error should include field name");
        assertTrue(ex.getMessage().contains("16"), "error should include expected constraint");
    }

    // ── AES-GCM (Opaque): accept >= 28 bytes ───────────────────────────────

    @Test
    void opaque_accepts28Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[28];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void opaque_acceptsLargerThan28Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[100];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void opaque_rejectsLessThan28Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[27];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("secret"), "error should include field name");
        assertTrue(ex.getMessage().contains("28"), "error should include expected constraint");
    }

    // ── OPE (OrderPreserving): accept exactly 25 bytes ─────────────────────
    // 1-byte length + 8-byte encrypted long + 16-byte HMAC-SHA256 tag (F03.R39,R72,R78)

    // @spec encryption.primitives-variants.R48 — 25-byte OPE ciphertext accepted
    @Test
    void orderPreserving_accepts25Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[25];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R48 — OPE ciphertext shorter than 25 bytes rejected
    @Test
    void orderPreserving_rejects24Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[24];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("rank"), "error should include field name");
        assertTrue(ex.getMessage().contains("25"), "error should include expected constraint");
    }

    // @spec encryption.primitives-variants.R48 — OPE ciphertext longer than 25 bytes rejected
    @Test
    void orderPreserving_rejects26Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[26];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R48 — pre-v2 9-byte format no longer accepted
    @Test
    void orderPreserving_rejectsLegacy9ByteFormat() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[9];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext),
                "Pre-v2 9-byte OPE format must be rejected in v2 (MAC-wrapped format is 25 bytes)");
    }

    // ── DCPE (DistancePreserving): 8 + dims*4 + 16 bytes ────────────────────
    // 8B seed + dims*4 encrypted floats + 16B HMAC-SHA256 tag (F03.R73,R79)

    // @spec encryption.primitives-variants.R49 — DCPE blob length accepted when 8 + dims*4 + 16
    @Test
    void distancePreserving_acceptsCorrectLength() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[8 + dimensions * 4 + 16]; // 536 bytes
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R49 — off-by-one DCPE length rejected
    @Test
    void distancePreserving_rejectsWrongLength() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[8 + dimensions * 4 + 16 + 1];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("embedding"), "error should include field name");
    }

    // @spec encryption.primitives-variants.R49 — pre-v2 dims*4 format no longer accepted
    @Test
    void distancePreserving_rejectsLegacyFormat() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[dimensions * 4];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext),
                "Pre-v2 dims*4 DCPE format must be rejected in v2 (seed+MAC wraps to 8+4N+16)");
    }

    // ── None: throws if called ──────────────────────────────────────────────

    @Test
    void none_throwsIfCalled() {
        FieldDefinition field = new FieldDefinition("plain", FieldType.string());
        byte[] ciphertext = new byte[32];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
    }

    // ── Null ciphertext → NullPointerException ──────────────────────────────

    @Test
    void nullCiphertext_throwsNpe() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        assertThrows(NullPointerException.class, () -> CiphertextValidator.validate(field, null));
    }

    // ── Null field → NullPointerException ───────────────────────────────────

    @Test
    void nullField_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> CiphertextValidator.validate(null, new byte[16]));
    }

    // ── Empty ciphertext → IllegalArgumentException ─────────────────────────

    @Test
    void emptyCiphertext_throwsIae() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, new byte[0]));
        assertTrue(ex.getMessage().contains("email"), "error should include field name");
    }
}

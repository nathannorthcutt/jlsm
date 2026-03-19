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

    // ── OPE (OrderPreserving): accept exactly 9 bytes (1-byte length + 8-byte encrypted long)

    @Test
    void orderPreserving_accepts9Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[9];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void orderPreserving_rejects8Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[8];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("rank"), "error should include field name");
        assertTrue(ex.getMessage().contains("9"), "error should include expected constraint");
    }

    @Test
    void orderPreserving_rejects10Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
    }

    // ── DCPE (DistancePreserving): dimensions * 4 bytes ─────────────────────

    @Test
    void distancePreserving_acceptsCorrectLength() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[dimensions * 4]; // 512 bytes
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void distancePreserving_rejectsWrongLength() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[dimensions * 4 + 1];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("embedding"), "error should include field name");
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

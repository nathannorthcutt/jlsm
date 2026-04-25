package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import jlsm.encryption.EncryptionSpec;
import jlsm.table.internal.CiphertextValidator;
import org.junit.jupiter.api.Test;

class CiphertextValidatorTest {

    // ── AES-SIV (Deterministic): accept >= 20 bytes (4B prefix + 16B SIV) ───

    // @spec encryption.ciphertext-envelope.R1,R1b,R3a — Deterministic envelope = [4B BE DEK
    // version | 16B SIV | ciphertext]; minimum 20 bytes accepted
    @Test
    void deterministic_accepts20Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[20];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void deterministic_acceptsLargerThan20Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[64];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.ciphertext-envelope.R1b,R3a — reader rejects Deterministic ciphertext
    // whose byte count violates the 4B-prefix + 16B-SIV-or-longer formula
    @Test
    void deterministic_rejectsLessThan20Bytes() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[19];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("email"), "error should include field name");
        assertTrue(ex.getMessage().contains("20"), "error should include new minimum 20");
    }

    // ── AES-GCM (Opaque): accept >= 32 bytes (4B prefix + 12B IV + 16B tag) ─

    // @spec encryption.ciphertext-envelope.R1,R1b,R3a — Opaque envelope = [4B BE DEK version
    // | 12B IV | ciphertext | 16B tag]; minimum 32 bytes accepted
    @Test
    void opaque_accepts32Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[32];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    @Test
    void opaque_acceptsLargerThan32Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[100];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.ciphertext-envelope.R1b,R3a — reader rejects Opaque ciphertext violating
    // the new minimum overhead formula (4B prefix + 12B IV + 16B GCM tag = 32)
    @Test
    void opaque_rejectsLessThan32Bytes() {
        FieldDefinition field = new FieldDefinition("secret", FieldType.string(),
                EncryptionSpec.opaque());
        byte[] ciphertext = new byte[31];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("secret"), "error should include field name");
        assertTrue(ex.getMessage().contains("32"), "error should include new minimum 32");
    }

    // ── OPE (OrderPreserving): accept exactly 29 bytes (4B prefix + 25B OPE) ──

    // @spec encryption.primitives-variants.R48, encryption.ciphertext-envelope.R1,R1b,R3a —
    // OPE envelope = [4B BE DEK version | 1B length | 8B OPE ct | 16B MAC] = 29 bytes
    @Test
    void orderPreserving_acceptsExactly29Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[29];
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R48, encryption.ciphertext-envelope.R1b,R3a —
    // OPE blob whose byte count differs from the fixed 29-byte formula is rejected
    @Test
    void orderPreserving_rejects28Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[28];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("rank"), "error should include field name");
        assertTrue(ex.getMessage().contains("29"), "error should include new constraint 29");
    }

    @Test
    void orderPreserving_rejects30Bytes() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[30];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R48 — pre-WU-4 25-byte format (no version prefix) is
    // no longer accepted: the WU-4 envelope minimum is 29 bytes for OPE.
    @Test
    void orderPreserving_rejectsLegacy25ByteFormatWithoutPrefix() {
        FieldDefinition field = new FieldDefinition("rank", FieldType.int32(),
                EncryptionSpec.orderPreserving());
        byte[] ciphertext = new byte[25];
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext),
                "Pre-WU-4 25-byte OPE format (no DEK version prefix) must be rejected");
    }

    // ── DCPE (DistancePreserving): 12 + dims*4 + 16 bytes ────────────────────
    // 4B prefix + 8B seed + dims*4 encrypted floats + 16B HMAC-SHA256 tag

    // @spec encryption.primitives-variants.R49, encryption.ciphertext-envelope.R1,R1b,R3a —
    // DCPE envelope = [4B BE DEK version | 8B seed | 4N values | 16B MAC] = 12 + 4N + 16 bytes
    @Test
    void distancePreserving_acceptsCorrectLengthWithPrefix() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[12 + dimensions * 4 + 16]; // 540 bytes
        assertDoesNotThrow(() -> CiphertextValidator.validate(field, ciphertext));
    }

    // @spec encryption.primitives-variants.R49, encryption.ciphertext-envelope.R1b,R3a —
    // DCPE blob deviating from the 12 + 4N + 16 formula is rejected
    @Test
    void distancePreserving_rejectsWrongLengthWithPrefix() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[12 + dimensions * 4 + 16 + 1];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext));
        assertTrue(ex.getMessage().contains("embedding"), "error should include field name");
    }

    // @spec encryption.primitives-variants.R49 — pre-WU-4 8 + 4N + 16 (no prefix) format
    // no longer accepted: minimum DCPE envelope length is 12 + 4N + 16.
    @Test
    void distancePreserving_rejectsLegacyFormatWithoutPrefix() {
        int dimensions = 128;
        FieldDefinition field = new FieldDefinition("embedding",
                FieldType.vector(FieldType.Primitive.FLOAT32, dimensions),
                EncryptionSpec.distancePreserving());
        byte[] ciphertext = new byte[8 + dimensions * 4 + 16]; // pre-WU-4
        assertThrows(IllegalArgumentException.class,
                () -> CiphertextValidator.validate(field, ciphertext),
                "Pre-WU-4 8+4N+16 DCPE format (no DEK version prefix) must be rejected");
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

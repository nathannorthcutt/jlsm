package jlsm.table.internal;

import jlsm.core.json.JsonObject;
import jlsm.core.json.JsonPrimitive;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in the JSON value adapter layer.
 */
class ContractBoundariesAdversarialTest {

    // Finding: F-R1.cb.1.1
    // Bug: FLOAT64 inbound conversion accepted non-finite values (NaN, Infinity)
    // while FLOAT16 and FLOAT32 rejected them with IllegalArgumentException.
    // Fix: Two layers of defense were added:
    // 1. JsonPrimitive.ofNumber() now validates via BigDecimal, rejecting NaN/Infinity
    // with NumberFormatException at construction time.
    // 2. JsonValueAdapter FLOAT64 case has a Double.isFinite() guard (defense-in-depth,
    // unreachable via public API since layer 1 prevents construction).
    // These tests verify layer 1: non-finite values cannot be constructed as JSON number
    // primitives.
    @Test
    void test_JsonValueAdapter_FLOAT64_rejectsNaN() {
        // NaN cannot be constructed as a JSON number primitive — BigDecimal rejects it.
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber("NaN"),
                "NaN must be rejected by JsonPrimitive.ofNumber()");
    }

    @Test
    void test_JsonValueAdapter_FLOAT64_rejectsPositiveInfinity() {
        // Infinity cannot be constructed as a JSON number primitive — BigDecimal rejects it.
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber("Infinity"),
                "Infinity must be rejected by JsonPrimitive.ofNumber()");
    }

    @Test
    void test_JsonValueAdapter_FLOAT64_rejectsNegativeInfinity() {
        // -Infinity cannot be constructed as a JSON number primitive — BigDecimal rejects it.
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber("-Infinity"),
                "-Infinity must be rejected by JsonPrimitive.ofNumber()");
    }

    // Finding: F-R1.cb.1.5
    // Bug: INT32 inbound conversion uses Integer.parseInt directly, so both overflow
    // (e.g. "3000000000") and format errors (e.g. "12.5") produce the same
    // "out of range" message via the single NumberFormatException catch.
    // Correct behavior: Format errors ("12.5", "abc") should say "not a valid integer";
    // overflow (e.g. "3000000000") should say "out of range" —
    // consistent with INT8 and INT16.
    // Fix location: JsonValueAdapter.java lines 241-249, INT32 case
    // Regression watch: Ensure valid INT32 values and actual overflow still work correctly.
    @Test
    void test_JsonValueAdapter_INT32_distinguishesOverflowFromFormatError() {
        JlsmSchema schema = JlsmSchema.builder("t", 1).field("v", FieldType.Primitive.INT32)
                .build();

        // "12.5" is not a valid integer — should say "not a valid integer", not "out of range"
        JsonObject decimalInput = JsonObject.builder().put("v", JsonPrimitive.ofNumber("12.5"))
                .build();
        IllegalArgumentException formatEx = assertThrows(IllegalArgumentException.class,
                () -> JsonValueAdapter.fromJsonValue(decimalInput, schema));
        assertTrue(formatEx.getMessage().contains("not a valid integer"),
                "Expected 'not a valid integer' for decimal input but got: "
                        + formatEx.getMessage());

        // "3000000000" exceeds Integer.MAX_VALUE — should say "out of range"
        JsonObject overflowInput = JsonObject.builder()
                .put("v", JsonPrimitive.ofNumber("3000000000")).build();
        IllegalArgumentException overflowEx = assertThrows(IllegalArgumentException.class,
                () -> JsonValueAdapter.fromJsonValue(overflowInput, schema));
        assertTrue(overflowEx.getMessage().contains("out of range"),
                "Expected 'out of range' for overflow input but got: " + overflowEx.getMessage());
    }

    // Finding: F-R1.contract_boundaries.3.2
    // Bug: CiphertextValidator.validate enforces only per-variant length checks; it does NOT
    // verify that the leading 4-byte BE DEK version prefix encodes a positive integer
    // (R2 / R1c). A pre-encrypted Deterministic ciphertext whose first 4 bytes are
    // 0x00000000 (or any negative-int representation) is admitted and persisted to disk,
    // producing an envelope that the read path then rejects as "non-positive version" — a
    // write-side spec violation that creates poisoned-on-disk entries.
    // Correct behavior: validate must reject a ciphertext whose 4B BE prefix decodes to
    // a non-positive int with IllegalArgumentException, symmetric with
    // EnvelopeCodec.prefixVersion's writer-side guard.
    // Fix location: CiphertextValidator.java lines 61-122 — add a parseVersion-equivalent
    // positivity check after the per-variant length checks pass.
    // Regression watch: ensure valid envelopes (positive version prefix) are still accepted
    // for all four variants; ensure NPE/empty/length errors still surface correctly.
    @Test
    void test_CiphertextValidator_versionPrefixPositivity_rejectsZeroPrefix() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        // 20-byte AES-SIV envelope whose 4B BE prefix is 0x00 0x00 0x00 0x00 — passes the
        // length check (>=20), but R2 says version 0 is corrupt.
        byte[] ciphertext = new byte[20];
        // Leading 4 bytes left as 0x00 — encodes int 0 which is non-positive.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> jlsm.table.internal.CiphertextValidator.validate(field, ciphertext),
                "Zero version prefix must be rejected at write-side validation per R2");
        assertTrue(ex.getMessage().contains("email"), "error should include field name");
    }

    @Test
    void test_CiphertextValidator_versionPrefixPositivity_rejectsNegativePrefix() {
        FieldDefinition field = new FieldDefinition("email", FieldType.string(),
                EncryptionSpec.deterministic());
        byte[] ciphertext = new byte[20];
        // 0x80 0x00 0x00 0x00 = Integer.MIN_VALUE (negative).
        ciphertext[0] = (byte) 0x80;
        ciphertext[1] = 0x00;
        ciphertext[2] = 0x00;
        ciphertext[3] = 0x00;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> jlsm.table.internal.CiphertextValidator.validate(field, ciphertext),
                "Negative version prefix must be rejected at write-side validation per R2");
        assertTrue(ex.getMessage().contains("email"), "error should include field name");
    }
}

package jlsm.table.internal;

import jlsm.core.json.JsonObject;
import jlsm.core.json.JsonPrimitive;
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
}

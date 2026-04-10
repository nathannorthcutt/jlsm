package jlsm.core.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JsonValue sealed interface hierarchy.
 */
class JsonValueTest {

    @Test
    void sealedInterfacePermitsAllFourSubtypes() {
        // Verify each permitted type implements JsonValue
        JsonValue obj = JsonObject.empty();
        JsonValue arr = JsonArray.empty();
        JsonValue prim = JsonPrimitive.ofString("hello");
        JsonValue nil = JsonNull.INSTANCE;

        assertInstanceOf(JsonObject.class, obj);
        assertInstanceOf(JsonArray.class, arr);
        assertInstanceOf(JsonPrimitive.class, prim);
        assertInstanceOf(JsonNull.class, nil);
    }

    @Test
    void patternMatchingSwitchIsExhaustive() {
        // This test verifies that a switch on JsonValue covering all four
        // permitted types compiles and executes correctly.
        JsonValue[] values = { JsonObject.empty(), JsonArray.empty(), JsonPrimitive.ofBoolean(true),
                JsonNull.INSTANCE };

        for (JsonValue v : values) {
            String label = switch (v) {
                case JsonObject _ -> "object";
                case JsonArray _ -> "array";
                case JsonPrimitive _ -> "primitive";
                case JsonNull _ -> "null";
            };
            assertNotNull(label);
        }
    }
}

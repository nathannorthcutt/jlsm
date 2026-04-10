package jlsm.core.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonNull enum singleton.
 */
class JsonNullTest {

    @Test
    void instanceIsSingleton() {
        assertSame(JsonNull.INSTANCE, JsonNull.INSTANCE);
    }

    @Test
    void implementsJsonValue() {
        assertInstanceOf(JsonValue.class, JsonNull.INSTANCE);
    }

    @Test
    void equalsUsesIdentity() {
        assertEquals(JsonNull.INSTANCE, JsonNull.INSTANCE);
        // Enum identity — there is only one instance
        assertSame(JsonNull.INSTANCE, JsonNull.valueOf("INSTANCE"));
    }

    @Test
    void toStringIsReadable() {
        // Enum toString returns the constant name
        assertEquals("INSTANCE", JsonNull.INSTANCE.toString());
    }
}

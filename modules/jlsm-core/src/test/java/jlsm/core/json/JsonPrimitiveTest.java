package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonPrimitive — string, number, and boolean primitives.
 */
class JsonPrimitiveTest {

    // === Factory null checks ===

    @Test
    void ofStringRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonPrimitive.ofString(null));
    }

    @Test
    void ofNumberRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonPrimitive.ofNumber(null));
    }

    // === String primitives ===

    @Test
    void stringPrimitiveIsString() {
        JsonPrimitive p = JsonPrimitive.ofString("hello");
        assertTrue(p.isString());
        assertFalse(p.isNumber());
        assertFalse(p.isBoolean());
    }

    @Test
    void stringPrimitiveAsStringReturnsValue() {
        JsonPrimitive p = JsonPrimitive.ofString("world");
        assertEquals("world", p.asString());
    }

    @Test
    void stringPrimitiveAsBooleanThrows() {
        JsonPrimitive p = JsonPrimitive.ofString("hello");
        assertThrows(IllegalStateException.class, p::asBoolean);
    }

    @Test
    void stringPrimitiveAsNumberTextThrows() {
        JsonPrimitive p = JsonPrimitive.ofString("hello");
        assertThrows(IllegalStateException.class, p::asNumberText);
    }

    @Test
    void stringPrimitiveAsIntThrows() {
        JsonPrimitive p = JsonPrimitive.ofString("hello");
        assertThrows(IllegalStateException.class, p::asInt);
    }

    // === Boolean primitives ===

    @Test
    void booleanPrimitiveIsBoolean() {
        JsonPrimitive p = JsonPrimitive.ofBoolean(true);
        assertTrue(p.isBoolean());
        assertFalse(p.isString());
        assertFalse(p.isNumber());
    }

    @Test
    void booleanPrimitiveAsBooleanReturnsValue() {
        assertTrue(JsonPrimitive.ofBoolean(true).asBoolean());
        assertFalse(JsonPrimitive.ofBoolean(false).asBoolean());
    }

    @Test
    void booleanPrimitiveAsStringThrows() {
        assertThrows(IllegalStateException.class, () -> JsonPrimitive.ofBoolean(true).asString());
    }

    // === Number primitives ===

    @Test
    void numberPrimitiveIsNumber() {
        JsonPrimitive p = JsonPrimitive.ofNumber("42");
        assertTrue(p.isNumber());
        assertFalse(p.isString());
        assertFalse(p.isBoolean());
    }

    @Test
    void numberPrimitiveAsNumberTextReturnsRawText() {
        assertEquals("42", JsonPrimitive.ofNumber("42").asNumberText());
        assertEquals("1.5e10", JsonPrimitive.ofNumber("1.5e10").asNumberText());
    }

    @Test
    void numberPrimitiveAsInt() {
        assertEquals(42, JsonPrimitive.ofNumber("42").asInt());
    }

    @Test
    void numberPrimitiveAsLong() {
        assertEquals(9999999999L, JsonPrimitive.ofNumber("9999999999").asLong());
    }

    @Test
    void numberPrimitiveAsDouble() {
        assertEquals(3.14, JsonPrimitive.ofNumber("3.14").asDouble(), 0.001);
    }

    @Test
    void numberPrimitiveAsBigDecimal() {
        assertEquals(new BigDecimal("1.23456789012345678901234567890"),
                JsonPrimitive.ofNumber("1.23456789012345678901234567890").asBigDecimal());
    }

    @Test
    void numberPrimitiveAsIntThrowsOnBadText() {
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber("abc").asInt());
    }

    @Test
    void numberPrimitiveAsLongThrowsOnBadText() {
        assertThrows(NumberFormatException.class,
                () -> JsonPrimitive.ofNumber("not-a-number").asLong());
    }

    @Test
    void numberPrimitiveAsDoubleThrowsOnBadText() {
        // Double.parseDouble accepts many formats; "abc" should throw
        assertThrows(NumberFormatException.class, () -> JsonPrimitive.ofNumber("abc").asDouble());
    }

    @Test
    void numberPrimitiveAsBigDecimalThrowsOnBadText() {
        assertThrows(NumberFormatException.class,
                () -> JsonPrimitive.ofNumber("xyz").asBigDecimal());
    }

    @Test
    void numberPrimitiveAsStringThrows() {
        assertThrows(IllegalStateException.class, () -> JsonPrimitive.ofNumber("42").asString());
    }

    @Test
    void numberPrimitiveAsBooleanThrows() {
        assertThrows(IllegalStateException.class, () -> JsonPrimitive.ofNumber("42").asBoolean());
    }

    // === Equality ===

    @Test
    void stringEqualityIsStandard() {
        assertEquals(JsonPrimitive.ofString("hello"), JsonPrimitive.ofString("hello"));
        assertNotEquals(JsonPrimitive.ofString("hello"), JsonPrimitive.ofString("world"));
    }

    @Test
    void booleanEqualityIsStandard() {
        assertEquals(JsonPrimitive.ofBoolean(true), JsonPrimitive.ofBoolean(true));
        assertNotEquals(JsonPrimitive.ofBoolean(true), JsonPrimitive.ofBoolean(false));
    }

    @Test
    void numberEqualityUsesBigDecimalCompareTo() {
        // "1.0" and "1.00" should be equal (compareTo == 0)
        assertEquals(JsonPrimitive.ofNumber("1.0"), JsonPrimitive.ofNumber("1.00"));
        assertEquals(JsonPrimitive.ofNumber("1.0"), JsonPrimitive.ofNumber("1"));
    }

    @Test
    void differentKindsAreNotEqual() {
        assertNotEquals(JsonPrimitive.ofString("42"), JsonPrimitive.ofNumber("42"));
        assertNotEquals(JsonPrimitive.ofBoolean(true), JsonPrimitive.ofString("true"));
    }

    @Test
    void hashCodeConsistentWithEquals() {
        assertEquals(JsonPrimitive.ofString("a").hashCode(),
                JsonPrimitive.ofString("a").hashCode());
        assertEquals(JsonPrimitive.ofBoolean(true).hashCode(),
                JsonPrimitive.ofBoolean(true).hashCode());
        // Numbers equal by compareTo should have same hashCode
        assertEquals(JsonPrimitive.ofNumber("1.0").hashCode(),
                JsonPrimitive.ofNumber("1.00").hashCode());
    }

    @Test
    void notEqualToNull() {
        assertNotEquals(null, JsonPrimitive.ofString("a"));
    }

    @Test
    void notEqualToDifferentType() {
        assertNotEquals("hello", JsonPrimitive.ofString("hello"));
    }

    // === toString ===

    @Test
    void toStringForString() {
        String result = JsonPrimitive.ofString("hello").toString();
        assertNotNull(result);
        assertTrue(result.contains("hello"));
    }

    @Test
    void toStringForNumber() {
        String result = JsonPrimitive.ofNumber("42").toString();
        assertNotNull(result);
        assertTrue(result.contains("42"));
    }

    @Test
    void toStringForBoolean() {
        String result = JsonPrimitive.ofBoolean(true).toString();
        assertNotNull(result);
        assertTrue(result.contains("true"));
    }
}

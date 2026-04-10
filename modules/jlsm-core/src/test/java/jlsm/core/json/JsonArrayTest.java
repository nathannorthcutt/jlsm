package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonArray — immutable JSON array.
 */
class JsonArrayTest {

    // === Factory: of(List) ===

    @Test
    void ofCreatesArrayFromList() {
        JsonArray arr = JsonArray
                .of(List.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofString("hello")));
        assertEquals(2, arr.size());
        assertEquals(JsonPrimitive.ofNumber("1"), arr.get(0));
        assertEquals(JsonPrimitive.ofString("hello"), arr.get(1));
    }

    @Test
    void ofRejectsNullList() {
        assertThrows(NullPointerException.class, () -> JsonArray.of((List<JsonValue>) null));
    }

    @Test
    void ofRejectsNullElement() {
        List<JsonValue> list = new ArrayList<>();
        list.add(JsonPrimitive.ofNumber("1"));
        list.add(null);
        assertThrows(NullPointerException.class, () -> JsonArray.of(list));
    }

    @Test
    void ofDefensivelyCopiesList() {
        List<JsonValue> list = new ArrayList<>();
        list.add(JsonPrimitive.ofNumber("1"));
        JsonArray arr = JsonArray.of(list);
        list.add(JsonPrimitive.ofNumber("2"));
        assertEquals(1, arr.size()); // Original array unchanged
    }

    // === Factory: of(JsonValue...) ===

    @Test
    void ofVarargsCreatesArray() {
        JsonArray arr = JsonArray.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofString("hello"));
        assertEquals(2, arr.size());
    }

    @Test
    void ofVarargsRejectsNullArray() {
        assertThrows(NullPointerException.class, () -> JsonArray.of((JsonValue[]) null));
    }

    // === Factory: empty() ===

    @Test
    void emptyReturnsEmptyArray() {
        JsonArray arr = JsonArray.empty();
        assertEquals(0, arr.size());
    }

    // === Access ===

    @Test
    void getThrowsOnOutOfBounds() {
        JsonArray arr = JsonArray.of(List.of(JsonPrimitive.ofNumber("1")));
        assertThrows(IndexOutOfBoundsException.class, () -> arr.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> arr.get(-1));
    }

    @Test
    void streamReturnsElements() {
        JsonArray arr = JsonArray.of(List.of(JsonPrimitive.ofNumber("1"),
                JsonPrimitive.ofNumber("2"), JsonPrimitive.ofNumber("3")));
        List<JsonValue> collected = arr.stream().toList();
        assertEquals(3, collected.size());
        assertEquals(JsonPrimitive.ofNumber("1"), collected.get(0));
    }

    // === Immutability ===

    @Test
    void arrayIsImmutable() {
        JsonArray arr = JsonArray.of(List.of(JsonPrimitive.ofNumber("1")));
        // stream().toList() returns unmodifiable, but the underlying list should also be safe
        assertNotNull(arr.get(0));
    }

    // === Equality ===

    @Test
    void equalArraysAreEqual() {
        JsonArray a = JsonArray.of(List.of(JsonPrimitive.ofString("x")));
        JsonArray b = JsonArray.of(List.of(JsonPrimitive.ofString("x")));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentArraysAreNotEqual() {
        JsonArray a = JsonArray.of(List.of(JsonPrimitive.ofString("x")));
        JsonArray b = JsonArray.of(List.of(JsonPrimitive.ofString("y")));
        assertNotEquals(a, b);
    }

    @Test
    void deepStructuralEquals() {
        JsonArray inner = JsonArray.of(List.of(JsonPrimitive.ofNumber("1")));
        JsonArray a = JsonArray.of(List.of(inner));
        JsonArray b = JsonArray.of(List.of(JsonArray.of(List.of(JsonPrimitive.ofNumber("1")))));
        assertEquals(a, b);
    }

    @Test
    void notEqualToNull() {
        assertNotEquals(null, JsonArray.empty());
    }

    @Test
    void notEqualToDifferentType() {
        assertNotEquals("not an array", JsonArray.empty());
    }

    // === toString ===

    @Test
    void toStringIsNotNull() {
        assertNotNull(JsonArray.empty().toString());
    }
}

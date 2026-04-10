package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonObject — immutable, insertion-ordered JSON object.
 */
class JsonObjectTest {

    // === Factory: of(Map) ===

    @Test
    void ofCreatesObjectFromMap() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("a", JsonPrimitive.ofNumber("1"));
        map.put("b", JsonPrimitive.ofString("hello"));

        JsonObject obj = JsonObject.of(map);
        assertEquals(2, obj.size());
        assertEquals(JsonPrimitive.ofNumber("1"), obj.get("a"));
        assertEquals(JsonPrimitive.ofString("hello"), obj.get("b"));
    }

    @Test
    void ofRejectsNullMap() {
        assertThrows(NullPointerException.class, () -> JsonObject.of(null));
    }

    @Test
    void ofRejectsNullValue() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("a", null);
        assertThrows(NullPointerException.class, () -> JsonObject.of(map));
    }

    @Test
    void ofDefensivelyCopiesMap() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("a", JsonPrimitive.ofNumber("1"));
        JsonObject obj = JsonObject.of(map);
        map.put("b", JsonPrimitive.ofNumber("2"));
        assertEquals(1, obj.size()); // Original object unchanged
    }

    // === Factory: empty() ===

    @Test
    void emptyReturnsEmptyObject() {
        JsonObject obj = JsonObject.empty();
        assertEquals(0, obj.size());
    }

    // === Factory: builder() ===

    @Test
    void builderCreatesObject() {
        JsonObject obj = JsonObject.builder().put("x", JsonPrimitive.ofBoolean(true))
                .put("y", JsonNull.INSTANCE).build();
        assertEquals(2, obj.size());
        assertEquals(JsonPrimitive.ofBoolean(true), obj.get("x"));
        assertEquals(JsonNull.INSTANCE, obj.get("y"));
    }

    @Test
    void builderRejectsDuplicateKeys() {
        JsonObject.Builder b = JsonObject.builder().put("a", JsonPrimitive.ofNumber("1"));
        assertThrows(IllegalArgumentException.class, () -> b.put("a", JsonPrimitive.ofNumber("2")));
    }

    @Test
    void builderRejectsNullKey() {
        assertThrows(NullPointerException.class,
                () -> JsonObject.builder().put(null, JsonPrimitive.ofNumber("1")));
    }

    @Test
    void builderRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> JsonObject.builder().put("a", null));
    }

    // === Blank key rejection ===

    @Test
    void ofRejectsBlankKey() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("", JsonPrimitive.ofNumber("1"));
        assertThrows(IllegalArgumentException.class, () -> JsonObject.of(map));
    }

    @Test
    void ofRejectsWhitespaceOnlyKey() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("  ", JsonPrimitive.ofNumber("1"));
        assertThrows(IllegalArgumentException.class, () -> JsonObject.of(map));
    }

    @Test
    void builderRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonObject.builder().put("", JsonPrimitive.ofNumber("1")));
    }

    // === Map-like access ===

    @Test
    void getReturnsNullForAbsentKey() {
        JsonObject obj = JsonObject.empty();
        assertNull(obj.get("missing"));
    }

    @Test
    void getRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> JsonObject.empty().get(null));
    }

    @Test
    void getOrDefaultReturnsDefaultForAbsentKey() {
        JsonValue def = JsonPrimitive.ofString("default");
        assertEquals(def, JsonObject.empty().getOrDefault("missing", def));
    }

    @Test
    void getOrDefaultRejectsNullKey() {
        assertThrows(NullPointerException.class,
                () -> JsonObject.empty().getOrDefault(null, JsonNull.INSTANCE));
    }

    @Test
    void containsKeyWorks() {
        JsonObject obj = JsonObject.builder().put("a", JsonPrimitive.ofNumber("1")).build();
        assertTrue(obj.containsKey("a"));
        assertFalse(obj.containsKey("b"));
    }

    @Test
    void containsKeyRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonObject.empty().containsKey(null));
    }

    // === Insertion order ===

    @Test
    void keysPreservesInsertionOrder() {
        JsonObject obj = JsonObject.builder().put("c", JsonPrimitive.ofNumber("3"))
                .put("a", JsonPrimitive.ofNumber("1")).put("b", JsonPrimitive.ofNumber("2"))
                .build();
        assertEquals(List.of("c", "a", "b"), obj.keys());
    }

    @Test
    void entrySetPreservesInsertionOrder() {
        JsonObject obj = JsonObject.builder().put("z", JsonNull.INSTANCE)
                .put("a", JsonNull.INSTANCE).build();
        var keys = obj.entrySet().stream().map(Map.Entry::getKey).toList();
        assertEquals(List.of("z", "a"), keys);
    }

    // === Immutability ===

    @Test
    void keysListIsUnmodifiable() {
        JsonObject obj = JsonObject.builder().put("a", JsonPrimitive.ofNumber("1")).build();
        assertThrows(UnsupportedOperationException.class, () -> obj.keys().add("b"));
    }

    @Test
    void entrySetIsUnmodifiable() {
        JsonObject obj = JsonObject.builder().put("a", JsonPrimitive.ofNumber("1")).build();
        assertThrows(UnsupportedOperationException.class, () -> obj.entrySet().clear());
    }

    // === Equality ===

    @Test
    void equalObjectsAreEqual() {
        JsonObject a = JsonObject.builder().put("k", JsonPrimitive.ofString("v")).build();
        JsonObject b = JsonObject.builder().put("k", JsonPrimitive.ofString("v")).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentObjectsAreNotEqual() {
        JsonObject a = JsonObject.builder().put("k", JsonPrimitive.ofString("v1")).build();
        JsonObject b = JsonObject.builder().put("k", JsonPrimitive.ofString("v2")).build();
        assertNotEquals(a, b);
    }

    @Test
    void deepStructuralEquals() {
        // Nested objects
        JsonObject inner = JsonObject.builder().put("x", JsonPrimitive.ofNumber("1")).build();
        JsonObject a = JsonObject.builder().put("nested", inner).build();
        JsonObject b = JsonObject.builder()
                .put("nested", JsonObject.builder().put("x", JsonPrimitive.ofNumber("1")).build())
                .build();
        assertEquals(a, b);
    }

    @Test
    void notEqualToNull() {
        assertNotEquals(null, JsonObject.empty());
    }

    @Test
    void notEqualToDifferentType() {
        assertNotEquals("not an object", JsonObject.empty());
    }

    // === toString ===

    @Test
    void toStringIsNotNull() {
        assertNotNull(JsonObject.empty().toString());
    }
}

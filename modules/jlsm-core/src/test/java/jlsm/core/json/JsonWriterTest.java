package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonWriter — compact and pretty-printed JSON serialization.
 */
class JsonWriterTest {

    // === Null and invalid input ===

    @Test
    void writeRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonWriter.write(null));
    }

    @Test
    void writePrettyRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonWriter.write(null, 2));
    }

    @Test
    void writePrettyRejectsNegativeIndent() {
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(JsonNull.INSTANCE, -1));
    }

    // === Primitives ===

    @Test
    void writeNull() {
        assertEquals("null", JsonWriter.write(JsonNull.INSTANCE));
    }

    @Test
    void writeString() {
        assertEquals("\"hello\"", JsonWriter.write(JsonPrimitive.ofString("hello")));
    }

    @Test
    void writeNumber() {
        assertEquals("42", JsonWriter.write(JsonPrimitive.ofNumber("42")));
        assertEquals("3.14", JsonWriter.write(JsonPrimitive.ofNumber("3.14")));
    }

    @Test
    void writeBooleanTrue() {
        assertEquals("true", JsonWriter.write(JsonPrimitive.ofBoolean(true)));
    }

    @Test
    void writeBooleanFalse() {
        assertEquals("false", JsonWriter.write(JsonPrimitive.ofBoolean(false)));
    }

    // === String escaping ===

    @Test
    void writeStringEscapesQuotes() {
        assertEquals("\"he said \\\"hi\\\"\"",
                JsonWriter.write(JsonPrimitive.ofString("he said \"hi\"")));
    }

    @Test
    void writeStringEscapesBackslash() {
        assertEquals("\"a\\\\b\"", JsonWriter.write(JsonPrimitive.ofString("a\\b")));
    }

    @Test
    void writeStringEscapesControlChars() {
        assertEquals("\"line1\\nline2\"", JsonWriter.write(JsonPrimitive.ofString("line1\nline2")));
        assertEquals("\"tab\\there\"", JsonWriter.write(JsonPrimitive.ofString("tab\there")));
    }

    @Test
    void writeStringEscapesCarriageReturn() {
        assertEquals("\"cr\\r\"", JsonWriter.write(JsonPrimitive.ofString("cr\r")));
    }

    @Test
    void writeStringEscapesBackspace() {
        assertEquals("\"bs\\b\"", JsonWriter.write(JsonPrimitive.ofString("bs\b")));
    }

    @Test
    void writeStringEscapesFormFeed() {
        assertEquals("\"ff\\f\"", JsonWriter.write(JsonPrimitive.ofString("ff\f")));
    }

    @Test
    void writeStringEscapesLowControlChars() {
        // U+0001 should be escaped as \u0001
        assertEquals("\"\\u0001\"", JsonWriter.write(JsonPrimitive.ofString("\u0001")));
    }

    // === Empty containers ===

    @Test
    void writeEmptyObject() {
        assertEquals("{}", JsonWriter.write(JsonObject.empty()));
    }

    @Test
    void writeEmptyArray() {
        assertEquals("[]", JsonWriter.write(JsonArray.empty()));
    }

    // === Object ===

    @Test
    void writeSimpleObject() {
        JsonObject obj = JsonObject.builder().put("key", JsonPrimitive.ofString("value")).build();
        assertEquals("{\"key\":\"value\"}", JsonWriter.write(obj));
    }

    @Test
    void writeObjectWithMultipleEntries() {
        JsonObject obj = JsonObject.builder().put("a", JsonPrimitive.ofNumber("1"))
                .put("b", JsonPrimitive.ofBoolean(true)).build();
        assertEquals("{\"a\":1,\"b\":true}", JsonWriter.write(obj));
    }

    // === Array ===

    @Test
    void writeSimpleArray() {
        JsonArray arr = JsonArray
                .of(List.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofNumber("2")));
        assertEquals("[1,2]", JsonWriter.write(arr));
    }

    @Test
    void writeArrayWithMixedTypes() {
        JsonArray arr = JsonArray.of(List.of(JsonPrimitive.ofString("hello"),
                JsonPrimitive.ofNumber("42"), JsonPrimitive.ofBoolean(false), JsonNull.INSTANCE));
        assertEquals("[\"hello\",42,false,null]", JsonWriter.write(arr));
    }

    // === Nested structures ===

    @Test
    void writeNestedObjectInArray() {
        JsonObject inner = JsonObject.builder().put("x", JsonPrimitive.ofNumber("1")).build();
        JsonArray arr = JsonArray.of(List.of(inner));
        assertEquals("[{\"x\":1}]", JsonWriter.write(arr));
    }

    @Test
    void writeNestedArrayInObject() {
        JsonArray inner = JsonArray.of(List.of(JsonPrimitive.ofNumber("1")));
        JsonObject obj = JsonObject.builder().put("arr", inner).build();
        assertEquals("{\"arr\":[1]}", JsonWriter.write(obj));
    }

    @Test
    void writeDeeplyNestedStructure() {
        // 3 levels of nesting
        JsonArray deepArr = JsonArray.of(List.of(JsonPrimitive.ofNumber("42")));
        JsonObject inner = JsonObject.builder().put("data", deepArr).build();
        JsonObject outer = JsonObject.builder().put("nested", inner).build();
        assertEquals("{\"nested\":{\"data\":[42]}}", JsonWriter.write(outer));
    }

    // === Pretty printing ===

    @Test
    void writePrettyNull() {
        assertEquals("null", JsonWriter.write(JsonNull.INSTANCE, 2));
    }

    @Test
    void writePrettySimpleObject() {
        JsonObject obj = JsonObject.builder().put("k", JsonPrimitive.ofString("v")).build();
        String expected = """
                {
                  "k": "v"
                }""";
        assertEquals(expected, JsonWriter.write(obj, 2));
    }

    @Test
    void writePrettySimpleArray() {
        JsonArray arr = JsonArray
                .of(List.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofNumber("2")));
        String expected = """
                [
                  1,
                  2
                ]""";
        assertEquals(expected, JsonWriter.write(arr, 2));
    }

    @Test
    void writePrettyNestedStructure() {
        JsonObject obj = JsonObject.builder()
                .put("arr",
                        JsonArray.of(
                                List.of(JsonPrimitive.ofNumber("1"), JsonPrimitive.ofNumber("2"))))
                .build();
        String expected = """
                {
                  "arr": [
                    1,
                    2
                  ]
                }""";
        assertEquals(expected, JsonWriter.write(obj, 2));
    }

    @Test
    void writePrettyWithIndentZeroIsCompact() {
        JsonObject obj = JsonObject.builder().put("k", JsonPrimitive.ofString("v")).build();
        assertEquals("{\"k\":\"v\"}", JsonWriter.write(obj, 0));
    }

    @Test
    void writePrettyWithIndentFour() {
        JsonObject obj = JsonObject.builder().put("k", JsonPrimitive.ofString("v")).build();
        String expected = """
                {
                    "k": "v"
                }""";
        assertEquals(expected, JsonWriter.write(obj, 4));
    }

    @Test
    void writePrettyEmptyObject() {
        assertEquals("{}", JsonWriter.write(JsonObject.empty(), 2));
    }

    @Test
    void writePrettyEmptyArray() {
        assertEquals("[]", JsonWriter.write(JsonArray.empty(), 2));
    }
}

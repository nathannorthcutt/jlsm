package jlsm.core.json;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonParser} — two-stage on-demand JSON parser.
 */
class JsonParserTest {

    // ─── Input validation ───────────────────────────────────────────

    @Test
    void parseRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonParser.parse(null));
    }

    @Test
    void parseWithDepthRejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonParser.parse(null, 10));
    }

    @Test
    void parseWithDepthRejectsNonPositiveDepth() {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{}", 0));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{}", -1));
    }

    // ─── Primitives ─────────────────────────────────────────────────

    @Nested
    class Primitives {

        @Test
        void parseNull() {
            JsonValue result = JsonParser.parse("null");
            assertSame(JsonNull.INSTANCE, result);
        }

        @Test
        void parseTrue() {
            JsonValue result = JsonParser.parse("true");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertTrue(p.isBoolean());
            assertTrue(p.asBoolean());
        }

        @Test
        void parseFalse() {
            JsonValue result = JsonParser.parse("false");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertTrue(p.isBoolean());
            assertFalse(p.asBoolean());
        }

        @Test
        void parseInteger() {
            JsonValue result = JsonParser.parse("42");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertTrue(p.isNumber());
            assertEquals("42", p.asNumberText());
        }

        @Test
        void parseNegativeInteger() {
            JsonValue result = JsonParser.parse("-17");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertTrue(p.isNumber());
            assertEquals("-17", p.asNumberText());
        }

        @Test
        void parseFloat() {
            JsonValue result = JsonParser.parse("3.14");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertEquals("3.14", p.asNumberText());
        }

        @Test
        void parseExponent() {
            JsonValue result = JsonParser.parse("1.5e10");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertEquals("1.5e10", p.asNumberText());
        }

        @Test
        void parseNegativeExponent() {
            JsonValue result = JsonParser.parse("2.5E-3");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertEquals("2.5E-3", p.asNumberText());
        }

        @Test
        void parseZero() {
            JsonValue result = JsonParser.parse("0");
            assertInstanceOf(JsonPrimitive.class, result);
            assertEquals("0", ((JsonPrimitive) result).asNumberText());
        }

        @Test
        void parseSimpleString() {
            JsonValue result = JsonParser.parse("\"hello\"");
            assertInstanceOf(JsonPrimitive.class, result);
            JsonPrimitive p = (JsonPrimitive) result;
            assertTrue(p.isString());
            assertEquals("hello", p.asString());
        }

        @Test
        void parseEmptyString() {
            JsonValue result = JsonParser.parse("\"\"");
            assertInstanceOf(JsonPrimitive.class, result);
            assertEquals("", ((JsonPrimitive) result).asString());
        }
    }

    // ─── String escapes ─────────────────────────────────────────────

    @Nested
    class StringEscapes {

        @Test
        void escapedQuote() {
            JsonValue result = JsonParser.parse("\"he said \\\"hi\\\"\"");
            assertEquals("he said \"hi\"", ((JsonPrimitive) result).asString());
        }

        @Test
        void escapedBackslash() {
            JsonValue result = JsonParser.parse("\"a\\\\b\"");
            assertEquals("a\\b", ((JsonPrimitive) result).asString());
        }

        @Test
        void escapedForwardSlash() {
            JsonValue result = JsonParser.parse("\"a\\/b\"");
            assertEquals("a/b", ((JsonPrimitive) result).asString());
        }

        @Test
        void escapedControlCharacters() {
            JsonValue result = JsonParser.parse("\"a\\b\\f\\n\\r\\tb\"");
            assertEquals("a\b\f\n\r\tb", ((JsonPrimitive) result).asString());
        }

        @Test
        void unicodeEscapeBmp() {
            // \u0041 = 'A'
            JsonValue result = JsonParser.parse("\"\\u0041\"");
            assertEquals("A", ((JsonPrimitive) result).asString());
        }

        @Test
        void unicodeEscapeSurrogatePair() {
            // U+1F600 (grinning face) = \uD83D\uDE00
            JsonValue result = JsonParser.parse("\"\\uD83D\\uDE00\"");
            String expected = new String(Character.toChars(0x1F600));
            assertEquals(expected, ((JsonPrimitive) result).asString());
        }

        @Test
        void unicodeEscapeMultiple() {
            // \u0048\u0065\u006C\u006C\u006F = "Hello"
            JsonValue result = JsonParser.parse("\"\\u0048\\u0065\\u006C\\u006C\\u006F\"");
            assertEquals("Hello", ((JsonPrimitive) result).asString());
        }
    }

    // ─── Objects ─────────────────────────────────────────────────────

    @Nested
    class Objects {

        @Test
        void emptyObject() {
            JsonValue result = JsonParser.parse("{}");
            assertInstanceOf(JsonObject.class, result);
            assertEquals(0, ((JsonObject) result).size());
        }

        @Test
        void singleKeyValue() {
            JsonValue result = JsonParser.parse("{\"key\":\"value\"}");
            assertInstanceOf(JsonObject.class, result);
            JsonObject obj = (JsonObject) result;
            assertEquals(1, obj.size());
            assertEquals(JsonPrimitive.ofString("value"), obj.get("key"));
        }

        @Test
        void multipleKeyValues() {
            JsonValue result = JsonParser.parse("{\"a\":1,\"b\":2,\"c\":3}");
            assertInstanceOf(JsonObject.class, result);
            JsonObject obj = (JsonObject) result;
            assertEquals(3, obj.size());
            assertEquals(JsonPrimitive.ofNumber("1"), obj.get("a"));
            assertEquals(JsonPrimitive.ofNumber("2"), obj.get("b"));
            assertEquals(JsonPrimitive.ofNumber("3"), obj.get("c"));
        }

        @Test
        void nestedObject() {
            JsonValue result = JsonParser.parse("{\"outer\":{\"inner\":true}}");
            assertInstanceOf(JsonObject.class, result);
            JsonObject outer = (JsonObject) result;
            JsonObject inner = (JsonObject) outer.get("outer");
            assertEquals(JsonPrimitive.ofBoolean(true), inner.get("inner"));
        }

        @Test
        void objectWithAllValueTypes() {
            String json = """
                    {"str":"hello","num":42,"bool":true,"nil":null,"arr":[1],"obj":{}}""";
            JsonValue result = JsonParser.parse(json);
            assertInstanceOf(JsonObject.class, result);
            JsonObject obj = (JsonObject) result;
            assertEquals(6, obj.size());
            assertEquals(JsonPrimitive.ofString("hello"), obj.get("str"));
            assertEquals(JsonPrimitive.ofNumber("42"), obj.get("num"));
            assertEquals(JsonPrimitive.ofBoolean(true), obj.get("bool"));
            assertSame(JsonNull.INSTANCE, obj.get("nil"));
            assertInstanceOf(JsonArray.class, obj.get("arr"));
            assertInstanceOf(JsonObject.class, obj.get("obj"));
        }
    }

    // ─── Arrays ──────────────────────────────────────────────────────

    @Nested
    class Arrays {

        @Test
        void emptyArray() {
            JsonValue result = JsonParser.parse("[]");
            assertInstanceOf(JsonArray.class, result);
            assertEquals(0, ((JsonArray) result).size());
        }

        @Test
        void singleElement() {
            JsonValue result = JsonParser.parse("[42]");
            assertInstanceOf(JsonArray.class, result);
            JsonArray arr = (JsonArray) result;
            assertEquals(1, arr.size());
            assertEquals(JsonPrimitive.ofNumber("42"), arr.get(0));
        }

        @Test
        void multipleElements() {
            JsonValue result = JsonParser.parse("[1,\"two\",true,null]");
            assertInstanceOf(JsonArray.class, result);
            JsonArray arr = (JsonArray) result;
            assertEquals(4, arr.size());
            assertEquals(JsonPrimitive.ofNumber("1"), arr.get(0));
            assertEquals(JsonPrimitive.ofString("two"), arr.get(1));
            assertEquals(JsonPrimitive.ofBoolean(true), arr.get(2));
            assertSame(JsonNull.INSTANCE, arr.get(3));
        }

        @Test
        void nestedArrays() {
            JsonValue result = JsonParser.parse("[[1,2],[3,4]]");
            assertInstanceOf(JsonArray.class, result);
            JsonArray outer = (JsonArray) result;
            assertEquals(2, outer.size());
            JsonArray inner1 = (JsonArray) outer.get(0);
            assertEquals(2, inner1.size());
        }

        @Test
        void arrayOfObjects() {
            JsonValue result = JsonParser.parse("[{\"a\":1},{\"b\":2}]");
            assertInstanceOf(JsonArray.class, result);
            JsonArray arr = (JsonArray) result;
            assertEquals(2, arr.size());
            assertInstanceOf(JsonObject.class, arr.get(0));
            assertInstanceOf(JsonObject.class, arr.get(1));
        }
    }

    // ─── Whitespace handling ─────────────────────────────────────────

    @Nested
    class Whitespace {

        @Test
        void leadingAndTrailingWhitespace() {
            JsonValue result = JsonParser.parse("  42  ");
            assertEquals(JsonPrimitive.ofNumber("42"), result);
        }

        @Test
        void whitespaceInsideObject() {
            JsonValue result = JsonParser.parse("{ \"a\" : 1 , \"b\" : 2 }");
            assertInstanceOf(JsonObject.class, result);
            assertEquals(2, ((JsonObject) result).size());
        }

        @Test
        void whitespaceInsideArray() {
            JsonValue result = JsonParser.parse("[ 1 , 2 , 3 ]");
            assertInstanceOf(JsonArray.class, result);
            assertEquals(3, ((JsonArray) result).size());
        }

        @Test
        void newlinesAndTabs() {
            JsonValue result = JsonParser.parse("{\n\t\"a\"\t:\n1\n}");
            assertInstanceOf(JsonObject.class, result);
            assertEquals(1, ((JsonObject) result).size());
        }
    }

    // ─── Error cases ─────────────────────────────────────────────────

    @Nested
    class ErrorCases {

        @Test
        void emptyInput() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse(""));
        }

        @Test
        void whitespaceOnly() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("   "));
        }

        @Test
        void trailingContent() {
            JsonParseException ex = assertThrows(JsonParseException.class,
                    () -> JsonParser.parse("42 43"));
            assertTrue(ex.offset() >= 0, "Must report a byte offset");
        }

        @Test
        void trailingContentAfterObject() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\":1} extra"));
        }

        @Test
        void truncatedObject() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\":"));
        }

        @Test
        void truncatedArray() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("[1,"));
        }

        @Test
        void truncatedString() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("\"unterminated"));
        }

        @Test
        void duplicateKeys() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\":1,\"a\":2}"));
        }

        @Test
        void maxDepthExceeded() {
            // Build deeply nested array: [[[...]]]
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++)
                sb.append('[');
            sb.append('1');
            for (int i = 0; i < 5; i++)
                sb.append(']');
            // With maxDepth=3, nesting of 5 should fail
            assertThrows(JsonParseException.class, () -> JsonParser.parse(sb.toString(), 3));
        }

        @Test
        void defaultMaxDepthExceeded() {
            // Build nesting of 257 levels (exceeds default 256)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 257; i++)
                sb.append('[');
            sb.append('1');
            for (int i = 0; i < 257; i++)
                sb.append(']');
            assertThrows(JsonParseException.class, () -> JsonParser.parse(sb.toString()));
        }

        @Test
        void malformedNumber() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("01"));
        }

        @Test
        void leadingPlusOnNumber() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("+1"));
        }

        @Test
        void trailingCommaInObject() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\":1,}"));
        }

        @Test
        void trailingCommaInArray() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("[1,]"));
        }

        @Test
        void missingColonInObject() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\" 1}"));
        }

        @Test
        void nonStringKeyInObject() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{1:2}"));
        }

        @Test
        void bareWord() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("undefined"));
        }

        @Test
        void singleComma() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse(","));
        }

        @Test
        void singleColon() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse(":"));
        }

        @Test
        void unmatchedOpenBrace() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("{"));
        }

        @Test
        void unmatchedCloseBrace() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("}"));
        }

        @Test
        void unmatchedOpenBracket() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("["));
        }

        @Test
        void unmatchedCloseBracket() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("]"));
        }

        @Test
        void invalidEscapeSequence() {
            assertThrows(JsonParseException.class, () -> JsonParser.parse("\"\\x\""));
        }

        @Test
        void incompleteSurrogatePair() {
            // High surrogate without low surrogate
            assertThrows(JsonParseException.class, () -> JsonParser.parse("\"\\uD83D\""));
        }

        @Test
        void loneSurrogateInString() {
            // High surrogate followed by non-surrogate
            assertThrows(JsonParseException.class, () -> JsonParser.parse("\"\\uD83D\\u0041\""));
        }
    }

    // ─── Complex / integration ──────────────────────────────────────

    @Nested
    class ComplexCases {

        @Test
        void deeplyNestedButWithinLimit() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++)
                sb.append('{').append("\"k\":");
            sb.append("1");
            for (int i = 0; i < 100; i++)
                sb.append('}');
            JsonValue result = JsonParser.parse(sb.toString());
            assertInstanceOf(JsonObject.class, result);
        }

        @Test
        void mixedNesting() {
            String json = "{\"a\":[{\"b\":[1,2,{\"c\":true}]},null],\"d\":\"e\"}";
            JsonValue result = JsonParser.parse(json);
            assertInstanceOf(JsonObject.class, result);
            JsonObject root = (JsonObject) result;
            assertInstanceOf(JsonArray.class, root.get("a"));
            assertEquals(JsonPrimitive.ofString("e"), root.get("d"));
        }

        @Test
        void stringWithAllEscapeTypes() {
            String json = "\"\\\"\\\\\\/ \\b\\f\\n\\r\\t \\u0041\"";
            JsonValue result = JsonParser.parse(json);
            assertEquals("\"\\/ \b\f\n\r\t A", ((JsonPrimitive) result).asString());
        }

        @Test
        void numberVariants() {
            // Verify various number formats parse correctly
            assertEquals(JsonPrimitive.ofNumber("0"), JsonParser.parse("0"));
            assertEquals(JsonPrimitive.ofNumber("-0"), JsonParser.parse("-0"));
            assertEquals(JsonPrimitive.ofNumber("123"), JsonParser.parse("123"));
            assertEquals(JsonPrimitive.ofNumber("0.5"), JsonParser.parse("0.5"));
            assertEquals(JsonPrimitive.ofNumber("-0.5"), JsonParser.parse("-0.5"));
            assertEquals(JsonPrimitive.ofNumber("1e10"), JsonParser.parse("1e10"));
            assertEquals(JsonPrimitive.ofNumber("1E10"), JsonParser.parse("1E10"));
            assertEquals(JsonPrimitive.ofNumber("1e+10"), JsonParser.parse("1e+10"));
            assertEquals(JsonPrimitive.ofNumber("1e-10"), JsonParser.parse("1e-10"));
            assertEquals(JsonPrimitive.ofNumber("1.5e10"), JsonParser.parse("1.5e10"));
        }

        @Test
        void emptyContainersInArray() {
            JsonValue result = JsonParser.parse("[{},[],({})]".replace("(", "").replace(")", ""));
            // Actually: "[{},[],{}]"
            // Let me just use the direct form
            JsonValue result2 = JsonParser.parse("[{},[],{}]");
            assertInstanceOf(JsonArray.class, result2);
            JsonArray arr = (JsonArray) result2;
            assertEquals(3, arr.size());
        }

        @Test
        void parseExceptionHasOffset() {
            JsonParseException ex = assertThrows(JsonParseException.class,
                    () -> JsonParser.parse("{\"a\":}"));
            assertTrue(ex.offset() >= 0, "Exception must include byte offset");
        }
    }
}

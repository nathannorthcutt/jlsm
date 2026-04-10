package jlsm.core.json.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScalarStage1} — tier 3 scalar structural scanner.
 */
class ScalarStage1Test {

    @Test
    void scanEmptyObject() {
        byte[] input = "{}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        // Should find { at 0 and } at 1
        assertContainsPositions(positions, input, '{', '}');
    }

    @Test
    void scanEmptyArray() {
        byte[] input = "[]".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        assertContainsPositions(positions, input, '[', ']');
    }

    @Test
    void scanSimpleObjectWithStringValue() {
        byte[] input = "{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        // Structural chars: { at 0, " at 1, " at 3, : at 4, " at 5, " at 7, } at 8
        assertTrue(positions.length >= 2, "Must find at least { and }");
        // { should be at position 0
        assertEquals((byte) '{', input[positions[0]]);
        // } should be at last structural position
        assertEquals((byte) '}', input[positions[positions.length - 1]]);
    }

    @Test
    void scanNestedStructures() {
        byte[] input = "{\"a\":[1,2,{\"b\":3}]}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        assertTrue(positions.length >= 4, "Must find multiple structural chars");
        // Positions must be in ascending order
        for (int i = 1; i < positions.length; i++) {
            assertTrue(positions[i] > positions[i - 1], "Positions must be in ascending order: "
                    + positions[i - 1] + " >= " + positions[i]);
        }
    }

    @Test
    void scanStringWithEscapedQuote() {
        // {"a":"he said \"hi\""}
        byte[] input = "{\"a\":\"he said \\\"hi\\\"\"}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        // The escaped quotes inside the string must NOT split the string
        // { at 0, quotes around "a", :, quotes around the value, } at end
        assertEquals((byte) '{', input[positions[0]]);
        assertEquals((byte) '}', input[positions[positions.length - 1]]);
    }

    @Test
    void scanStringWithEscapedBackslashBeforeQuote() {
        // {"a":"trail\\"} — the \\\\ is two backslashes, so the quote after them is structural
        byte[] input = "{\"a\":\"trail\\\\\"}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        assertEquals((byte) '{', input[positions[0]]);
        assertEquals((byte) '}', input[positions[positions.length - 1]]);
    }

    @Test
    void scanStringWithUnicodeEscape() {
        byte[] input = "{\"a\":\"\\u0041\"}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        assertEquals((byte) '{', input[positions[0]]);
        assertEquals((byte) '}', input[positions[positions.length - 1]]);
    }

    @Test
    void scanRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> ScalarStage1.scan(null));
    }

    @Test
    void scanEmptyInput() {
        byte[] input = new byte[0];
        int[] positions = ScalarStage1.scan(input);
        assertNotNull(positions);
        assertEquals(0, positions.length);
    }

    @Test
    void positionsAreAscending() {
        byte[] input = "[{\"k\":\"v\"},{\"k2\":\"v2\"}]".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        for (int i = 1; i < positions.length; i++) {
            assertTrue(positions[i] > positions[i - 1]);
        }
    }

    @Test
    void scanCommasAndColons() {
        byte[] input = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        // Should include colons and commas as structural
        boolean foundColon = false;
        boolean foundComma = false;
        for (int pos : positions) {
            if (input[pos] == ':')
                foundColon = true;
            if (input[pos] == ',')
                foundComma = true;
        }
        assertTrue(foundColon, "Must find colon as structural char");
        assertTrue(foundComma, "Must find comma as structural char");
    }

    @Test
    void quotesInsideStringNotStructural() {
        // The colon inside the string value should NOT appear as structural
        byte[] input = "{\"a\":\"x:y\"}".getBytes(StandardCharsets.UTF_8);
        int[] positions = ScalarStage1.scan(input);
        // Only one colon should be structural (the one after "a")
        int colonCount = 0;
        for (int pos : positions) {
            if (input[pos] == ':')
                colonCount++;
        }
        assertEquals(1, colonCount,
                "Only the structural colon should appear, not the one inside the string");
    }

    private void assertContainsPositions(int[] positions, byte[] input, char... expectedChars) {
        for (char c : expectedChars) {
            boolean found = false;
            for (int pos : positions) {
                if (input[pos] == (byte) c) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Expected to find structural char '" + c + "' in positions");
        }
    }
}

package jlsm.core.json.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StructuralIndexer} — stage 1 orchestrator.
 */
class StructuralIndexerTest {

    @Test
    void indexRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> StructuralIndexer.index(null));
    }

    @Test
    void indexEmptyObject() {
        byte[] input = "{}".getBytes(StandardCharsets.UTF_8);
        var index = StructuralIndexer.index(input);
        assertNotNull(index);
        assertSame(input, index.input());
        assertNotNull(index.positions());
        assertTrue(index.positions().length >= 2, "Must find at least { and }");
    }

    @Test
    void indexPreservesInputReference() {
        byte[] input = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        var index = StructuralIndexer.index(input);
        assertSame(input, index.input(), "StructuralIndex must reference the original input array");
    }

    @Test
    void indexPositionsAreAscending() {
        byte[] input = "{\"a\":[1,2],\"b\":{\"c\":3}}".getBytes(StandardCharsets.UTF_8);
        var index = StructuralIndexer.index(input);
        int[] positions = index.positions();
        for (int i = 1; i < positions.length; i++) {
            assertTrue(positions[i] > positions[i - 1],
                    "Positions must be ascending: " + positions[i - 1] + " >= " + positions[i]);
        }
    }

    @Test
    void indexFiltersOutCharsInsideStrings() {
        // The colon inside the string should NOT appear in structural positions
        byte[] input = "{\"a:b\":1}".getBytes(StandardCharsets.UTF_8);
        var index = StructuralIndexer.index(input);
        int colonCount = 0;
        for (int pos : index.positions()) {
            if (input[pos] == ':')
                colonCount++;
        }
        assertEquals(1, colonCount, "Only the structural colon (after key) should be indexed");
    }
}

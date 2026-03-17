package jlsm.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoredEntryTest {

    private JlsmDocument minimalDoc() {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        return JlsmDocument.of(schema, "name", "value");
    }

    @Test
    void constructsValidEntry() {
        var doc = minimalDoc();
        var entry = new ScoredEntry<>("key-1", doc, 0.95);
        assertEquals("key-1", entry.key());
        assertEquals(doc, entry.document());
        assertEquals(0.95, entry.score());
    }

    @Test
    void rejectsNullKey() {
        var doc = minimalDoc();
        assertThrows(NullPointerException.class, () -> new ScoredEntry<>(null, doc, 1.0));
    }

    @Test
    void rejectsNullDocument() {
        assertThrows(NullPointerException.class, () -> new ScoredEntry<>("key", null, 1.0));
    }

    @Test
    void allowsNegativeScore() {
        var doc = minimalDoc();
        assertDoesNotThrow(() -> new ScoredEntry<>("key", doc, -1.0));
    }

    @Test
    void allowsZeroScore() {
        var doc = minimalDoc();
        assertDoesNotThrow(() -> new ScoredEntry<>("key", doc, 0.0));
    }

    @Test
    void allowsHighScore() {
        var doc = minimalDoc();
        assertDoesNotThrow(() -> new ScoredEntry<>("key", doc, Double.MAX_VALUE));
    }

    @Test
    void equalsByFields() {
        var doc = minimalDoc();
        var e1 = new ScoredEntry<>("key", doc, 0.5);
        var e2 = new ScoredEntry<>("key", doc, 0.5);
        assertEquals(e1, e2);
    }
}

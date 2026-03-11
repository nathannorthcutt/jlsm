package jlsm.core.indexing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnglishStopWords}.
 */
class EnglishStopWordsTest {

    @Test
    void words_notNull() {
        assertNotNull(EnglishStopWords.WORDS, "EnglishStopWords.WORDS must not be null");
    }

    /**
     * Since {@link EnglishStopWords#WORDS} is currently {@code Set.of()} (an empty stub), every
     * {@code contains} assertion here will FAIL. That is correct — it confirms the test fails
     * before the implementation populates WORDS with real stop words.
     */
    @Test
    void words_containsCommonWords() {
        assertTrue(EnglishStopWords.WORDS.contains("the"), "WORDS must contain 'the'");
        assertTrue(EnglishStopWords.WORDS.contains("a"), "WORDS must contain 'a'");
        assertTrue(EnglishStopWords.WORDS.contains("an"), "WORDS must contain 'an'");
        assertTrue(EnglishStopWords.WORDS.contains("and"), "WORDS must contain 'and'");
        assertTrue(EnglishStopWords.WORDS.contains("is"), "WORDS must contain 'is'");
        assertTrue(EnglishStopWords.WORDS.contains("in"), "WORDS must contain 'in'");
        assertTrue(EnglishStopWords.WORDS.contains("of"), "WORDS must contain 'of'");
        assertTrue(EnglishStopWords.WORDS.contains("to"), "WORDS must contain 'to'");
    }

    @Test
    void words_doesNotContainContentWords() {
        // Content words must never appear in the stop-word set regardless of implementation.
        // This test must pass even with the current empty-stub.
        assertFalse(EnglishStopWords.WORDS.contains("cat"),
                "WORDS must not contain the content word 'cat'");
        assertFalse(EnglishStopWords.WORDS.contains("run"),
                "WORDS must not contain the content word 'run'");
        assertFalse(EnglishStopWords.WORDS.contains("index"),
                "WORDS must not contain the content word 'index'");
    }

    @Test
    void words_isUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> EnglishStopWords.WORDS.add("foo"),
                "WORDS must be an unmodifiable set");
    }
}

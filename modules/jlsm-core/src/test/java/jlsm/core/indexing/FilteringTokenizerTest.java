package jlsm.core.indexing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilteringTokenizer}.
 */
class FilteringTokenizerTest {

    // -----------------------------------------------------------------------
    // A minimal whitespace tokenizer for use in tests — avoids depending on
    // any package-private tokenizer from jlsm.indexing.
    // -----------------------------------------------------------------------

    /** Splits on whitespace and lowercases each part; no other processing. */
    private static final Tokenizer SPACE_TOKENIZER = text -> Arrays
            .asList(text.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+"));

    // -----------------------------------------------------------------------
    // Basic filtering
    // -----------------------------------------------------------------------

    @Test
    void basicFiltering_stopWordRemoved() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER, Set.of("the"));
        Collection<String> tokens = tokenizer.tokenize("the cat sat");
        assertFalse(tokens.contains("the"), "'the' must be filtered out, got: " + tokens);
        assertTrue(tokens.contains("cat"), "expected 'cat' in result, got: " + tokens);
        assertTrue(tokens.contains("sat"), "expected 'sat' in result, got: " + tokens);
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    @Test
    void multipleStopWords_bothRemoved() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER, Set.of("a", "and"));
        Collection<String> tokens = tokenizer.tokenize("a dog and cat");
        assertFalse(tokens.contains("a"), "'a' must be filtered out, got: " + tokens);
        assertFalse(tokens.contains("and"), "'and' must be filtered out, got: " + tokens);
        assertTrue(tokens.contains("dog"), "expected 'dog' in result, got: " + tokens);
        assertTrue(tokens.contains("cat"), "expected 'cat' in result, got: " + tokens);
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    @Test
    void noStopWordsMatch_tokensUnchanged() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER, Set.of("the"));
        Collection<String> tokens = tokenizer.tokenize("cat sat");
        assertTrue(tokens.contains("cat"), "expected 'cat' in result, got: " + tokens);
        assertTrue(tokens.contains("sat"), "expected 'sat' in result, got: " + tokens);
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    @Test
    void emptyStopWordSet_noFiltering() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER, Set.of());
        Collection<String> tokens = tokenizer.tokenize("the cat");
        assertTrue(tokens.contains("the"),
                "expected 'the' in result (no stop words), got: " + tokens);
        assertTrue(tokens.contains("cat"), "expected 'cat' in result, got: " + tokens);
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    /**
     * Since {@link EnglishStopWords#WORDS} is currently {@code Set.of()} (an empty stub), this test
     * will FAIL — "the", "a", and "an" will NOT be filtered, so the result will not be empty. This
     * is correct: it confirms the test fails before implementation.
     */
    @Test
    void allTokensFiltered_returnsEmpty() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER,
                EnglishStopWords.WORDS);
        Collection<String> tokens = tokenizer.tokenize("the a an");
        assertTrue(tokens.isEmpty(),
                "all tokens are English stop words and must be filtered out, got: " + tokens);
    }

    @Test
    void delegateTokenization_preserved() {
        // A comma-splitting tokenizer — verifies that the delegate's splitting logic is
        // used even when stop-word filtering is applied.
        Tokenizer commaSplit = text -> {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String part : text.split(",")) {
                String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        };

        FilteringTokenizer tokenizer = new FilteringTokenizer(commaSplit, Set.of("the"));
        // "the,cat,sat" split by comma → ["the", "cat", "sat"]; "the" filtered out
        Collection<String> tokens = tokenizer.tokenize("the,cat,sat");
        assertFalse(tokens.contains("the"), "'the' must be filtered, got: " + tokens);
        assertTrue(tokens.contains("cat"), "expected 'cat' in result, got: " + tokens);
        assertTrue(tokens.contains("sat"), "expected 'sat' in result, got: " + tokens);
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    // -----------------------------------------------------------------------
    // Construction validation
    // -----------------------------------------------------------------------

    @Test
    void nullDelegate_throwsNPE() {
        assertThrows(NullPointerException.class, () -> new FilteringTokenizer(null, Set.of()));
    }

    @Test
    void nullStopWords_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new FilteringTokenizer(SPACE_TOKENIZER, null));
    }

    @Test
    void nullText_throwsNPE() {
        FilteringTokenizer tokenizer = new FilteringTokenizer(SPACE_TOKENIZER, Set.of("the"));
        assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null));
    }
}

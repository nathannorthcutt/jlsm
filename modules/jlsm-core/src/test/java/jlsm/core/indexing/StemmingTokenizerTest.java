package jlsm.core.indexing;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StemmingTokenizer}.
 */
class StemmingTokenizerTest {

    // -----------------------------------------------------------------------
    // A minimal whitespace tokenizer for use in tests (avoids depending on
    // LsmFullTextIndex.WhitespaceTokenizer, which is package-private to
    // jlsm.indexing).
    // -----------------------------------------------------------------------

    /** Splits on single spaces, lowercases, no other processing. */
    private static final Tokenizer SPACE_TOKENIZER = text -> List
            .of(text.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+"));

    // -----------------------------------------------------------------------
    // Construction validation
    //
    // Both tests combine the null-guard check with a subsequent tokenize() call
    // on the successfully constructed instance, ensuring the tests exercise the
    // tokenize() implementation and will fail until it is written.
    // -----------------------------------------------------------------------

    @Test
    void constructor_nullDelegateThrowsNullPointerException() {
        // Null delegate must be rejected
        assertThrows(NullPointerException.class,
                () -> new StemmingTokenizer(null, PorterStemmer.INSTANCE));
        // A valid construction must also produce correct results via tokenize()
        StemmingTokenizer valid = new StemmingTokenizer(SPACE_TOKENIZER, PorterStemmer.INSTANCE);
        Collection<String> tokens = valid.tokenize("cats");
        assertTrue(tokens.contains("cat"),
                "valid instance must stem 'cats' to 'cat', got: " + tokens);
    }

    @Test
    void constructor_nullStemmerThrowsNullPointerException() {
        // Null stemmer must be rejected
        assertThrows(NullPointerException.class,
                () -> new StemmingTokenizer(SPACE_TOKENIZER, null));
        // A valid construction must also produce correct results via tokenize()
        StemmingTokenizer valid = new StemmingTokenizer(SPACE_TOKENIZER, PorterStemmer.INSTANCE);
        Collection<String> tokens = valid.tokenize("running");
        assertTrue(tokens.contains("run"),
                "valid instance must stem 'running' to 'run', got: " + tokens);
    }

    // -----------------------------------------------------------------------
    // Basic stemming
    // -----------------------------------------------------------------------

    @Test
    void basic_runningAndCatsAreStemmed() {
        StemmingTokenizer t = new StemmingTokenizer(SPACE_TOKENIZER, PorterStemmer.INSTANCE);
        Collection<String> tokens = t.tokenize("running cats");
        assertTrue(tokens.contains("run"), "expected 'run' but got: " + tokens);
        assertTrue(tokens.contains("cat"), "expected 'cat' but got: " + tokens);
    }

    @Test
    void basic_tokenCountMatchesDelegateTokenCount() {
        // The stemmer maps 1:1 with each token; empty-stem case is separate
        StemmingTokenizer t = new StemmingTokenizer(SPACE_TOKENIZER, PorterStemmer.INSTANCE);
        Collection<String> tokens = t.tokenize("running cats");
        // Both "running" → "run" and "cats" → "cat" are non-empty, so 2 tokens
        assertEquals(2, tokens.size(), "expected exactly 2 tokens, got: " + tokens);
    }

    // -----------------------------------------------------------------------
    // Empty stem is dropped
    // -----------------------------------------------------------------------

    @Test
    void emptyStemIsDropped() {
        // A stemmer that returns "" for tokens equal to "drop" and identity otherwise
        Stemmer dropStemmer = token -> "drop".equals(token) ? "" : token;
        StemmingTokenizer t = new StemmingTokenizer(SPACE_TOKENIZER, dropStemmer);

        Collection<String> tokens = t.tokenize("keep drop also");
        assertFalse(tokens.contains("drop"), "token with empty stem must not appear in result");
        assertTrue(tokens.contains("keep"), "token 'keep' must be present");
        assertTrue(tokens.contains("also"), "token 'also' must be present");
        assertEquals(2, tokens.size(), "only 2 non-empty-stem tokens expected, got: " + tokens);
    }

    // -----------------------------------------------------------------------
    // Delegate splitting behavior is preserved
    // -----------------------------------------------------------------------

    @Test
    void delegateSplittingBehaviorIsPreserved() {
        // A custom tokenizer that splits on commas and trims each part
        Tokenizer commaSplit = text -> {
            String[] parts = text.split(",");
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        };

        // Identity stemmer so we can check token shapes
        Stemmer identity = token -> token;
        StemmingTokenizer t = new StemmingTokenizer(commaSplit, identity);

        Collection<String> tokens = t.tokenize("one,two, three");
        assertTrue(tokens.contains("one"), "expected 'one'");
        assertTrue(tokens.contains("two"), "expected 'two'");
        assertTrue(tokens.contains("three"), "expected 'three'");
        assertEquals(3, tokens.size(), "expected exactly 3 tokens, got: " + tokens);
    }
}

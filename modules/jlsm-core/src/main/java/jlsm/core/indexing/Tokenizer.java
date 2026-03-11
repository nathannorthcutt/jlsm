package jlsm.core.indexing;

import java.util.Collection;

/**
 * Stateless, deterministic text tokenizer.
 *
 * <p>
 * Implementations must be stateless: two calls with the same input must produce the same output,
 * and all state required for tokenization must be captured at construction time.
 */
@FunctionalInterface
public interface Tokenizer {

    /**
     * Splits {@code text} into a collection of tokens.
     *
     * @param text the text to tokenize; must not be null
     * @return a collection of tokens; never null; may be empty if no tokens are found
     */
    Collection<String> tokenize(String text);
}

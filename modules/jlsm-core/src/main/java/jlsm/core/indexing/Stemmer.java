package jlsm.core.indexing;

/**
 * Reduces a token to its stem.
 *
 * <p>
 * Implementations must be stateless and deterministic.
 */
@FunctionalInterface
public interface Stemmer {

    /**
     * Returns the stem of the given token. Implementations must be stateless and deterministic.
     *
     * @param token the token to stem; must not be null
     * @return the stem of the token; never null
     */
    String stem(String token);
}

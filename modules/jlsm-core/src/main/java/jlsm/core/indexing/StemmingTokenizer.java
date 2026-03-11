package jlsm.core.indexing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Tokenizer} decorator that applies a {@link Stemmer} to every token produced by a
 * delegate tokenizer.
 */
public final class StemmingTokenizer implements Tokenizer {

    private final Tokenizer delegate;
    private final Stemmer stemmer;

    /**
     * Constructs a {@code StemmingTokenizer} that applies {@code stemmer} to each token returned by
     * {@code delegate}.
     *
     * @param delegate the tokenizer to delegate to; must not be null
     * @param stemmer the stemmer to apply to each token; must not be null
     */
    public StemmingTokenizer(Tokenizer delegate, Stemmer stemmer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.stemmer = Objects.requireNonNull(stemmer, "stemmer must not be null");
    }

    @Override
    public Collection<String> tokenize(String text) {
        Objects.requireNonNull(text, "text must not be null");
        Collection<String> raw = delegate.tokenize(text);
        List<String> result = new ArrayList<>(raw.size());
        for (String token : raw) {
            String stemmed = stemmer.stem(token);
            if (!stemmed.isEmpty()) {
                result.add(stemmed);
            }
        }
        return result;
    }
}
